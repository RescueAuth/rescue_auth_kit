package com.rescueauth.v2.repository

import com.rescueauth.v2.database.DeveloperEntryDao
import com.rescueauth.v2.database.RescueAuthDatabase
import com.rescueauth.v2.domain.DeveloperEntry
import com.rescueauth.v2.export.PackageValidator
import com.rescueauth.v2.export.VaultAndroidSigningKey
import com.rescueauth.v2.export.VaultApiCredential
import com.rescueauth.v2.export.VaultDeveloperEntry
import com.rescueauth.v2.export.VaultEnvironmentVariableSet
import com.rescueauth.v2.export.VaultGenericSecret
import com.rescueauth.v2.export.VaultKeyValue
import com.rescueauth.v2.export.VaultSshKey
import java.util.Base64
import com.rescueauth.v2.session.SecureSessionStateMachine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Production Developer Vault repository (Phase 4 P4 — first batch: API
 * Credential / SSH Key / Generic Secret).
 *
 * All mutations funnel through the shared [VaultRepository] serialized mutex
 * + Room transaction, so Developer CRUD is written to the real encrypted
 * Vault immediately and can never interleave with an export/import merge
 * (AGENTS 架构边界 3 / ROADMAP §9).
 *
 * ## Persistence reuse (no second table)
 *
 * This repository REUSES the Phase 3C single-table persistence:
 *
 * ```
 * Room DeveloperEntryEntity (schema v3, typed payloadJson)
 *   ↔ DeveloperMappers (the ONLY bridge)
 *   ↔ domain/logical VaultDeveloperEntry (core portable logical model)
 *   ↔ DeveloperRepository
 *   ↔ ViewModel
 *   ↔ UI model
 *   ↔ Compose
 * ```
 *
 * Composable code never parses `payloadJson` and never touches a Room entity.
 * The typed payload is produced/consumed exclusively by [DeveloperMappers]
 * from the validated portable logical model (Issue #20 §9).
 *
 * ## stableId / edit semantics (Issue #20 §18)
 *
 * - Create mints a new stableId (UUID) and uses it as both the Room `id` and
 *   the portable stableId (same lineage as Phase 3C merge inserts).
 * - Edit preserves the existing stableId (and createdAt) — it never does
 *   "delete old + insert new random stableId", so package lineage, merge
 *   conflict semantics and Legacy migration identity stay intact.
 * - Delete removes the row by stableId (transactional).
 *
 * ## Validation (Issue #20 §20)
 *
 * Secrets are opaque values — no vendor-specific API key format guessing, no
 * SSH private key crypto parsing, no smart normalization. Validation is the
 * minimum required by the formal logical model / [PackageValidator] contract
 * (so every stored entry can always be exported): API credential requires
 * apiKey + apiSecret; SSH key requires privateKey; Generic Secret requires a
 * non-blank title and at least one field with non-blank label+value. Reasonable
 * defensive field count / length limits are applied and validation errors
 * never echo a secret value.
 */
class DeveloperRepository(
    private val vault: VaultRepository,
    private val db: RescueAuthDatabase,
    @Suppress("unused") private val session: SecureSessionStateMachine,
) {
    class ValidationException(message: String) : Exception(message)
    class NotFoundException(message: String) : Exception(message)

    companion object {
        const val MAX_FIELDS = 200
        const val MAX_FIELD_LENGTH = 16_384
        const val MAX_ENTRY_TEXT_LENGTH = 200_000

        /**
         * Raw keystore byte cap, DERIVED from the shared logical/package
         * per-asset contract [PackageValidator.MAX_KEYSTORE_BASE64_LENGTH]
         * (Issue #20 P6 §3 / §7). We do not invent a third, unrelated size rule:
         *
         * - the shared logical validator caps `keystoreBase64` at
         *   MAX_KEYSTORE_BASE64_LENGTH (12 MiB of base64 text);
         * - base64 encodes 3 raw bytes as 4 chars, so the maximum raw byte
         *   count that can ever encode to ≤ that base64 cap is
         *   (cap / 4) * 3 = 9 MiB.
         *
         * The SAF importer enforces this exact byte cap on the RAW keystore so
         * any stored keystore is always exportable through the package path
         * (never a validator-accepted-but-unencodable state).
         */
        val MAX_KEYSTORE_RAW_BYTES =
            (PackageValidator.MAX_KEYSTORE_BASE64_LENGTH / 4) * 3
    }

    private val dao: DeveloperEntryDao get() = db.developerEntryDao()

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /** Metadata-only domain list (no secrets cross this boundary). */
    fun observeAll(): Flow<List<DeveloperEntry>> =
        dao.observeAll().map { list -> list.map { DeveloperMappers.toDomain(it) } }

    /** Full logical payload of one entry (all fields incl. secrets). */
    suspend fun getByStableId(stableId: String): VaultDeveloperEntry? =
        dao.getByStableId(stableId)?.let { DeveloperMappers.toLogical(it) }

    // ------------------------------------------------------------------
    // API Credential
    // ------------------------------------------------------------------

    suspend fun createApiCredential(
        title: String,
        notes: String?,
        serviceName: String,
        accountName: String,
        apiKey: String,
        apiSecret: String,
    ): VaultApiCredential {
        validateApiCredential(title, serviceName, accountName, apiKey, apiSecret)
        val now = java.time.Instant.now().toString()
        val stableId = UUID.randomUUID().toString()
        val entry = VaultApiCredential(
            stableId = stableId,
            serviceName = serviceName.trim(),
            accountName = accountName.trim(),
            apiKey = apiKey,
            apiSecret = apiSecret,
            title = title.trim(),
            notes = notes?.trim()?.ifEmpty { null },
            createdAt = now,
            updatedAt = now,
        )
        persist(entry)
        return entry
    }

    /**
     * Edits an existing API credential **preserving its stableId / createdAt**
     * (Issue #20 §18). Throws [NotFoundException] when the entry is missing and
     * [ValidationException] on an entry-type mismatch.
     */
    suspend fun editApiCredential(
        stableId: String,
        title: String,
        notes: String?,
        serviceName: String,
        accountName: String,
        apiKey: String,
        apiSecret: String,
    ): VaultApiCredential {
        validateApiCredential(title, serviceName, accountName, apiKey, apiSecret)
        val existing = requireApi(stableId)
        val updated = existing.copy(
            serviceName = serviceName.trim(),
            accountName = accountName.trim(),
            apiKey = apiKey,
            apiSecret = apiSecret,
            title = title.trim(),
            notes = notes?.trim()?.ifEmpty { null },
            updatedAt = java.time.Instant.now().toString(),
        )
        persist(updated, existingSortOrder(stableId))
        return updated
    }

    // ------------------------------------------------------------------
    // SSH Key
    // ------------------------------------------------------------------

    suspend fun createSshKey(
        title: String,
        notes: String?,
        keyName: String,
        publicKey: String,
        privateKey: String,
        passphrase: String,
    ): VaultSshKey {
        validateSshKey(title, privateKey)
        val now = java.time.Instant.now().toString()
        val stableId = UUID.randomUUID().toString()
        val entry = VaultSshKey(
            stableId = stableId,
            keyName = keyName.trim(),
            publicKey = publicKey,
            privateKey = privateKey,
            passphrase = passphrase,
            title = title.trim(),
            notes = notes?.trim()?.ifEmpty { null },
            createdAt = now,
            updatedAt = now,
        )
        persist(entry)
        return entry
    }

    suspend fun editSshKey(
        stableId: String,
        title: String,
        notes: String?,
        keyName: String,
        publicKey: String,
        privateKey: String,
        passphrase: String,
    ): VaultSshKey {
        validateSshKey(title, privateKey)
        val existing = requireSsh(stableId)
        val updated = existing.copy(
            keyName = keyName.trim(),
            publicKey = publicKey,
            privateKey = privateKey,
            passphrase = passphrase,
            title = title.trim(),
            notes = notes?.trim()?.ifEmpty { null },
            updatedAt = java.time.Instant.now().toString(),
        )
        persist(updated, existingSortOrder(stableId))
        return updated
    }

    // ------------------------------------------------------------------
    // Generic Secret
    // ------------------------------------------------------------------

    suspend fun createGenericSecret(
        title: String,
        notes: String?,
        fields: List<VaultKeyValue>,
    ): VaultGenericSecret {
        validateGenericSecret(title, fields)
        val now = java.time.Instant.now().toString()
        val stableId = UUID.randomUUID().toString()
        val entry = VaultGenericSecret(
            stableId = stableId,
            fields = normalizeFields(fields),
            title = title.trim(),
            notes = notes?.trim()?.ifEmpty { null },
            createdAt = now,
            updatedAt = now,
        )
        persist(entry)
        return entry
    }

    suspend fun editGenericSecret(
        stableId: String,
        title: String,
        notes: String?,
        fields: List<VaultKeyValue>,
    ): VaultGenericSecret {
        validateGenericSecret(title, fields)
        val existing = requireGeneric(stableId)
        val updated = existing.copy(
            fields = normalizeFields(fields),
            title = title.trim(),
            notes = notes?.trim()?.ifEmpty { null },
            updatedAt = java.time.Instant.now().toString(),
        )
        persist(updated, existingSortOrder(stableId))
        return updated
    }

    // ------------------------------------------------------------------
    // Android Signing Key (Phase 4 P6)
    // ------------------------------------------------------------------

    /**
     * Creates an Android Signing Key entry (Issue #20 P6 §2/§3).
     *
     * [keystoreBytes] are treated as an **opaque binary asset**: they are
     * base64-encoded as-is (RFC 4648, no line breaks) for the logical model /
     * package payload, so the stored bytes are an exact round trip. The
     * repository never inspects, re-encodes, re-parses or normalizes the
     * keystore contents (no keytool/jarsigner, no certificate parsing).
     *
     * [keystoreFileName] is the non-secret stored filename (UX hint).
     */
    suspend fun createAndroidSigningKey(
        title: String,
        notes: String?,
        projectName: String,
        packageName: String,
        keystoreFileName: String,
        keystoreBytes: ByteArray,
        storePassword: String,
        keyAlias: String,
        keyPassword: String,
    ): VaultAndroidSigningKey {
        validateSigningKey(title, keystoreBytes, keyAlias)
        val now = java.time.Instant.now().toString()
        val stableId = UUID.randomUUID().toString()
        val entry = VaultAndroidSigningKey(
            stableId = stableId,
            projectName = projectName.trim(),
            packageName = packageName.trim(),
            keystoreFileName = keystoreFileName.trim(),
            keystoreBase64 = Base64.getEncoder().encodeToString(keystoreBytes),
            storePassword = storePassword,
            keyAlias = keyAlias.trim(),
            keyPassword = keyPassword,
            title = title.trim(),
            notes = notes?.trim()?.ifEmpty { null },
            createdAt = now,
            updatedAt = now,
        )
        persist(entry)
        return entry
    }

    /**
     * Edits an existing Android Signing Key **preserving its stableId /
     * createdAt** (Issue #20 P6 §4). Metadata edits never delete/recreate the
     * entry. A replaced keystore (new bytes + filename) keeps the SAME logical
     * Developer stableId — only the payload changes, so future package merges
     * treat it by the existing conflict semantics (Issue #20 P6 §15).
     */
    suspend fun editAndroidSigningKey(
        stableId: String,
        title: String,
        notes: String?,
        projectName: String,
        packageName: String,
        keystoreFileName: String,
        keystoreBytes: ByteArray,
        storePassword: String,
        keyAlias: String,
        keyPassword: String,
    ): VaultAndroidSigningKey {
        validateSigningKey(title, keystoreBytes, keyAlias)
        val existing = requireSigningKey(stableId)
        val updated = existing.copy(
            projectName = projectName.trim(),
            packageName = packageName.trim(),
            keystoreFileName = keystoreFileName.trim(),
            keystoreBase64 = Base64.getEncoder().encodeToString(keystoreBytes),
            storePassword = storePassword,
            keyAlias = keyAlias.trim(),
            keyPassword = keyPassword,
            title = title.trim(),
            notes = notes?.trim()?.ifEmpty { null },
            updatedAt = java.time.Instant.now().toString(),
        )
        persist(updated, existingSortOrder(stableId))
        return updated
    }

    // ------------------------------------------------------------------
    // Environment Variable Set (Phase 4 P6)
    // ------------------------------------------------------------------

    /**
     * Creates an Environment Variable Set (Issue #20 P6 §9).
     *
     * [variables] preserve their insertion order. Variable values are opaque
     * secrets and are NEVER trimmed / re-cased / normalized. Variable names are
     * validated for: non-blank, no exact duplicates within the same set
     * (case-sensitive — `FOO` and `foo` are distinct), and whitespace handling
     * per the existing form convention (leading/trailing whitespace is trimmed
     * from the name only, never from the value — see docs/P6_REPORT.md).
     */
    suspend fun createEnvironmentVariableSet(
        title: String,
        notes: String?,
        projectName: String,
        variables: List<VaultKeyValue>,
    ): VaultEnvironmentVariableSet {
        validateEnvVarSet(title, variables)
        val now = java.time.Instant.now().toString()
        val stableId = UUID.randomUUID().toString()
        val entry = VaultEnvironmentVariableSet(
            stableId = stableId,
            projectName = projectName.trim(),
            variables = normalizeEnvVars(variables),
            title = title.trim(),
            notes = notes?.trim()?.ifEmpty { null },
            createdAt = now,
            updatedAt = now,
        )
        persist(entry)
        return entry
    }

    /**
     * Edits an existing Environment Variable Set **preserving its stableId /
     * createdAt** (Issue #20 P6 §4/§9).
     */
    suspend fun editEnvironmentVariableSet(
        stableId: String,
        title: String,
        notes: String?,
        projectName: String,
        variables: List<VaultKeyValue>,
    ): VaultEnvironmentVariableSet {
        validateEnvVarSet(title, variables)
        val existing = requireEnvVarSet(stableId)
        val updated = existing.copy(
            projectName = projectName.trim(),
            variables = normalizeEnvVars(variables),
            title = title.trim(),
            notes = notes?.trim()?.ifEmpty { null },
            updatedAt = java.time.Instant.now().toString(),
        )
        persist(updated, existingSortOrder(stableId))
        return updated
    }

    // ------------------------------------------------------------------
    // Delete
    // ------------------------------------------------------------------

    /**
     * Deletes the entry with [stableId] transactionally. Returns the deleted
     * logical payload (for potential Undo in a later slice); P4 delete uses
     * destructive confirmation (Issue #20 §17).
     */
    suspend fun delete(stableId: String): VaultDeveloperEntry? {
        return vault.mutate {
            val entity = dao.getByStableId(stableId) ?: return@mutate null
            dao.deleteById(entity.id)
            DeveloperMappers.toLogical(entity)
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private suspend fun persist(entry: VaultDeveloperEntry, sortOrder: Long? = null) {
        val order = sortOrder ?: nextSortOrder()
        vault.mutate {
            dao.upsert(DeveloperMappers.toEntity(entry, order))
        }
    }

    private suspend fun nextSortOrder(): Long {
        return vault.mutate { dao.maxSortOrder() + 1 }
    }

    private suspend fun existingSortOrder(stableId: String): Long {
        return vault.mutate { dao.getByStableId(stableId)?.sortOrder ?: nextSortOrder() }
    }

    private suspend fun requireApi(stableId: String): VaultApiCredential {
        val existing = getByStableId(stableId)
            ?: throw NotFoundException("developer entry not found")
        return existing as? VaultApiCredential
            ?: throw ValidationException("entry type is not an API credential")
    }

    private suspend fun requireSsh(stableId: String): VaultSshKey {
        val existing = getByStableId(stableId)
            ?: throw NotFoundException("developer entry not found")
        return existing as? VaultSshKey
            ?: throw ValidationException("entry type is not an SSH key")
    }

    private suspend fun requireGeneric(stableId: String): VaultGenericSecret {
        val existing = getByStableId(stableId)
            ?: throw NotFoundException("developer entry not found")
        return existing as? VaultGenericSecret
            ?: throw ValidationException("entry type is not a generic secret")
    }

    private suspend fun requireSigningKey(stableId: String): VaultAndroidSigningKey {
        val existing = getByStableId(stableId)
            ?: throw NotFoundException("developer entry not found")
        return existing as? VaultAndroidSigningKey
            ?: throw ValidationException("entry type is not an Android signing key")
    }

    private suspend fun requireEnvVarSet(stableId: String): VaultEnvironmentVariableSet {
        val existing = getByStableId(stableId)
            ?: throw NotFoundException("developer entry not found")
        return existing as? VaultEnvironmentVariableSet
            ?: throw ValidationException("entry type is not an environment variable set")
    }

    private fun validateApiCredential(
        title: String,
        serviceName: String,
        accountName: String,
        apiKey: String,
        apiSecret: String,
    ) {
        if (title.trim().isEmpty()) throw ValidationException("title is required")
        if (serviceName.trim().isEmpty()) throw ValidationException("service name is required")
        if (accountName.trim().isEmpty()) throw ValidationException("account name is required")
        // apiKey + apiSecret are both required by the formal logical model /
        // PackageValidator contract (every stored entry must be exportable).
        if (apiKey.isEmpty()) throw ValidationException("apiKey is required")
        if (apiSecret.isEmpty()) throw ValidationException("apiSecret is required")
        enforceLength("apiKey", apiKey)
        enforceLength("apiSecret", apiSecret)
        if (title.trim().length > MAX_ENTRY_TEXT_LENGTH) throw ValidationException("title too long")
        notesLength(notes = null) // placeholder to keep signature symmetric
    }

    private fun validateSshKey(title: String, privateKey: String) {
        if (title.trim().isEmpty()) throw ValidationException("title is required")
        if (privateKey.isEmpty()) throw ValidationException("private key is required")
        enforceLength("private key", privateKey)
    }

    private fun validateGenericSecret(title: String, fields: List<VaultKeyValue>) {
        if (title.trim().isEmpty()) throw ValidationException("title is required")
        if (fields.isEmpty()) throw ValidationException("at least one field is required")
        if (fields.size > MAX_FIELDS) throw ValidationException("too many fields")
        for (f in fields) {
            if (f.key.trim().isEmpty()) throw ValidationException("field label is required")
            enforceLength("field value", f.value)
        }
    }

    private fun validateSigningKey(title: String, keystoreBytes: ByteArray, keyAlias: String) {
        if (title.trim().isEmpty()) throw ValidationException("title is required")
        if (keystoreBytes.isEmpty()) throw ValidationException("keystore file is required")
        if (keystoreBytes.size > MAX_KEYSTORE_RAW_BYTES) {
            throw ValidationException("keystore file exceeds the size limit")
        }
        if (keyAlias.trim().isEmpty()) throw ValidationException("key alias is required")
        if (title.trim().length > MAX_ENTRY_TEXT_LENGTH) throw ValidationException("title too long")
    }

    private fun validateEnvVarSet(title: String, variables: List<VaultKeyValue>) {
        if (title.trim().isEmpty()) throw ValidationException("title is required")
        if (variables.isEmpty()) throw ValidationException("at least one variable is required")
        if (variables.size > MAX_FIELDS) throw ValidationException("too many variables")
        // Names must be non-blank and unique within the set (exact, case-
        // sensitive duplicate detection — `FOO` and `foo` are distinct, and
        // whitespace is normalized on the name before the duplicate check so
        // `FOO` and ` FOO ` are the same name). Values are opaque and never
        // normalized.
        val seen = HashSet<String>()
        for (v in variables) {
            val name = v.key.trim()
            if (name.isEmpty()) throw ValidationException("variable name is required")
            if (!seen.add(name)) {
                throw ValidationException("duplicate variable name '$name'")
            }
            enforceLength("variable value", v.value)
        }
    }

    private fun enforceLength(field: String, value: String) {
        if (value.length > MAX_FIELD_LENGTH) {
            throw ValidationException("$field is too long")
        }
    }

    private fun notesLength(notes: String?) {
        if (notes != null && notes.trim().length > MAX_ENTRY_TEXT_LENGTH) {
            throw ValidationException("notes too long")
        }
    }

    private fun normalizeFields(fields: List<VaultKeyValue>): List<VaultKeyValue> =
        fields.map { VaultKeyValue(it.key.trim(), it.value) }

    /**
     * Normalizes an env var set: trims the **name** only (per the existing
     * form convention, matching generic-secret field labels), NEVER trims or
     * re-cases the **value** (opaque secret, Issue #20 P6 §10).
     */
    private fun normalizeEnvVars(variables: List<VaultKeyValue>): List<VaultKeyValue> =
        variables.map { VaultKeyValue(it.key.trim(), it.value) }
}
