package com.rescueauth.v2.legacy

import com.rescueauth.v2.export.SnapshotScope
import com.rescueauth.v2.export.VaultAccount
import com.rescueauth.v2.export.VaultAndroidSigningKey
import com.rescueauth.v2.export.VaultApiCredential
import com.rescueauth.v2.export.VaultDeveloperEntry
import com.rescueauth.v2.export.VaultEnvironmentVariableSet
import com.rescueauth.v2.export.VaultGenericSecret
import com.rescueauth.v2.export.VaultKeyValue
import com.rescueauth.v2.export.VaultRecoveryCode
import com.rescueauth.v2.export.VaultRecoveryCodeSet
import com.rescueauth.v2.export.VaultSnapshot
import com.rescueauth.v2.export.VaultSshKey
import com.rescueauth.v2.export.VaultTotpCredential
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

/**
 * Maps a decrypted legacy vault ([LegacyImportBundle]) to the **shared v2
 * logical [VaultSnapshot]** (Phase 5A).
 *
 * ## Contract (ROADMAP §9 / docs/LEGACY_IMPORT.md §5)
 *
 * - The output is a pure logical snapshot with **no** Android / Room objects.
 * - It is validated by the shared `PackageValidator` (single logical
 *   validation rules) and can be funnelled through the existing
 *   `MergePlanner` / `VaultRepository.applySnapshot` transactional path.
 * - Developer entries (all five legacy types) are mapped **faithfully**
 *   field-by-field; no merging/dedupe happens here (that is the
 *   MergePlanner's job).
 *
 * ## Deterministic stable identity — durable-id FIRST (ADR-0010 §4)
 *
 * Legacy objects in frozen v1 (tag `v1.2.0`) carry **durable persisted
 * identities** — every persisted record's `id` is a UUID v4 minted once at
 * creation and stored inside the vault JSON (verified against
 * `vault_session.dart` / `vault_models.dart`):
 *
 * | legacy object | durable id source |
 * | --- | --- |
 * | Provider (schema 3 `providers[].id`) | persisted UUID v4 |
 * | Account (schema 3 `accounts[].id`) | persisted UUID v4 |
 * | TOTP credential (schema 3 `credentials[].id`, schema 1/2 `totpEntries[].id`) | persisted UUID v4 |
 * | Recovery Code Set (schema 3 `credentials[].id`, schema 1/2 `recoveryCodeSets[].id`) | persisted UUID v4 |
 * | Recovery Code | **NO durable id** — only `codes: List<String>` (positional) |
 * | Developer Entry (schema 1/2/3 `developerEntries[].id`) | persisted UUID v4 |
 *
 * Because these durable ids identify the **logical object itself** (not the
 * encrypted container), the stableId is derived from the durable id — it must
 * **NOT** depend on the source file fingerprint. The same logical object
 * re-encrypted into two different `.rakvault` backups (different encrypted
 * bytes → different source fingerprints) still yields the **same** stableId:
 *
 * ```
 * stableId = "legacy:" + kind + ":" + b64url(sha256("legacy\0" + kind + "\0" + durablePath))
 * ```
 *
 * - `durablePath` is a stable, collision-safe object path built **only** from
 *   durable ids (e.g. `totp:<credentialId>`, `account:<accountId>`,
 *   `recovery:<setId>/<index>`).
 * - [sourceFingerprint] is **not** part of the stableId; it is retained solely
 *   as the legacy import **source identity** for a future Phase 5B
 *   `ImportRecord` (ADR-0010 §5).
 * - The only object without a durable id is the individual Recovery Code; it
 *   uses a deterministic **structural fallback** keyed by its parent set's
 *   durable id + its positional index (ADR-0010 §4, rule B).
 *
 * Properties:
 * - **Deterministic**: same durable id → same stableId, across any number of
 *   re-encrypted backups.
 * - **Namespaced**: `legacy` prefix + per-kind discriminator prevent collision
 *   with native v2 records and between different legacy object kinds.
 * - **No plaintext secret in the stableId** (SHA-256 only, never the raw
 *   secret / password / decrypted payload).
 *
 * ## Source fingerprint
 *
 * [sourceFingerprint] is the SHA-256 of the **original encrypted**
 * `.rakvault` bytes (base64url). It contains neither the password nor the
 * decrypted payload and is stable for byte-identical files. Phase 5B can use
 * it as the `ImportRecord.sourceFingerprint` / ImportRecord identity.
 *
 * ## Error taxonomy
 *
 * - [LegacyMappingException] (a `MappingFailed` analogue) wraps any mapping
 *   failure; it never embeds plaintext credential material.
 * - The source parser / decryptor already classify file-format / schema /
 *   authentication failures separately ([LegacyRakVaultImporter.ImportException]).
 */
object LegacyVaultSnapshotMapper {

    /** Raised when a logically-valid legacy bundle cannot be mapped. */
    class LegacyMappingException(message: String, cause: Throwable? = null) :
        Exception(message, cause)

    /** StableId namespace prefix for all legacy-derived v2 logical records. */
    const val LEGACY_NAMESPACE = "legacy"

    /** StableId `kind` discriminators (also part of the hash input). */
    const val KIND_ACCOUNT = "account"
    const val KIND_TOTP = "totp"
    const val KIND_RECOVERY_SET = "recovery_set"
    const val KIND_RECOVERY_CODE = "recovery_code"

    /**
     * Maps [bundle] into a [VaultSnapshot] (FULL_VAULT scope).
     *
     * @param sourceFingerprintBase64Url source identity of the original
     *   encrypted legacy file (see [fingerprintOfEncryptedBytes]). Kept for
     *   future Phase 5B `ImportRecord` identity; **not** part of the stableId
     *   derivation (ADR-0010 §4 — durable-id first).
     */
    fun map(
        bundle: LegacyImportBundle,
        sourceFingerprintBase64Url: String,
    ): VaultSnapshot {
        require(sourceFingerprintBase64Url.isNotBlank()) {
            "sourceFingerprint must not be blank"
        }

        val accounts = when (bundle.schemaVersion) {
            1, 2 -> mapEntryCentric(bundle)
            3 -> mapSchema3(bundle)
            else -> throw LegacyMappingException(
                "Unsupported legacy schemaVersion ${bundle.schemaVersion}",
            )
        }

        val developers = mapDeveloperEntries(bundle.developerEntries)

        return VaultSnapshot(
            accounts = accounts,
            developerEntries = developers,
            scope = SnapshotScope.FULL_VAULT,
        )
    }

    /**
     * SHA-256 of the original encrypted `.rakvault` bytes, base64url (no
     * padding). No password / plaintext is involved.
     */
    fun fingerprintOfEncryptedBytes(encryptedBytes: ByteArray): String =
        base64Url(sha256(encryptedBytes))

    // ------------------------------------------------------------------
    // Stable identity derivation
    // ------------------------------------------------------------------

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    private fun base64Url(data: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(data)

    /**
     * Deterministic stableId for one logical record, derived **from the legacy
     * object's durable identity** ([durablePath]) — never from the source file
     * fingerprint.
     *
     * @param kind record kind, e.g. [KIND_ACCOUNT] / [KIND_TOTP] /
     *   [KIND_RECOVERY_SET] / [KIND_RECOVERY_CODE] / `developer:<legacyType>`.
     * @param durablePath stable legacy object identity built only from durable
     *   ids / structural position, e.g. `totp:<credId>` / `account:<accId>` /
     *   `recovery:<setId>/<index>` — NOT the secret itself.
     */
    private fun stableIdForDurable(kind: String, durablePath: String): String {
        val digest = sha256("$LEGACY_NAMESPACE\u0000$kind\u0000$durablePath".toByteArray(Charsets.UTF_8))
        return "$LEGACY_NAMESPACE:$kind:${base64Url(digest)}"
    }

    // ------------------------------------------------------------------
    // Schema 1/2 — entry-centric (never issuer-bucketed)
    // ------------------------------------------------------------------

    private fun mapEntryCentric(bundle: LegacyImportBundle): List<VaultAccount> {
        val accounts = mutableListOf<VaultAccount>()
        var order = 0L
        val now = java.time.Instant.now().toString()

        for (entry in bundle.totpEntries) {
            val validated = LegacyTotpValidator.validate(entry)
            if (validated.usability != LegacyImportBundle.TOTP_USABLE) continue
            val createdAt = validated.createdAt ?: now
            // Durable identity = the persisted TOTP entry id.
            val durableTotpPath = "$KIND_TOTP:${validated.id}"
            val accountStableId = stableIdForDurable(KIND_ACCOUNT, durableTotpPath)
            val totp = VaultTotpCredential(
                stableId = stableIdForDurable(KIND_TOTP, durableTotpPath),
                secretBase32 = validated.secretBase32,
                algorithm = validated.algorithm.trim().uppercase(),
                digits = validated.digits ?: 0,
                periodSeconds = validated.period ?: 0,
                createdAt = createdAt,
            )
            accounts += VaultAccount(
                stableId = accountStableId,
                serviceName = normalizedIssuer(validated.issuer),
                accountName = normalizedAccountName(validated.accountName, validated.issuer),
                sortOrder = order++,
                createdAt = createdAt,
                updatedAt = createdAt,
                totpCredentials = listOf(totp),
            )
        }

        for (set in bundle.recoveryCodeSets) {
            val createdAt = set.createdAt ?: now
            // Durable identity = the persisted recovery-code-set id.
            val durableSetPath = "recovery:${set.id}"
            val accountStableId = stableIdForDurable(KIND_ACCOUNT, durableSetPath)
            val setStableId = stableIdForDurable(KIND_RECOVERY_SET, durableSetPath)
            val title = set.title.trim().ifEmpty { "Recovery codes" }
            val account = VaultAccount(
                stableId = accountStableId,
                serviceName = title,
                accountName = normalizedAccountName(set.title, null),
                sortOrder = order++,
                createdAt = createdAt,
                updatedAt = createdAt,
                recoveryCodeSets = listOf(
                    VaultRecoveryCodeSet(
                        stableId = setStableId,
                        title = title,
                        createdAt = createdAt,
                        codes = set.codes.mapIndexed { i, code ->
                            VaultRecoveryCode(
                                // No durable id on individual codes — structural
                                // fallback keyed by the set's durable id + index.
                                stableId = stableIdForDurable(
                                    KIND_RECOVERY_CODE,
                                    "$durableSetPath/$i",
                                ),
                                value = code,
                                status = "UNUSED",
                                usedAt = null,
                                sortOrder = i,
                            )
                        },
                    ),
                ),
            )
            accounts += account
        }

        return accounts
    }

    // ------------------------------------------------------------------
    // Schema 3 — one legacy Account → one VaultAccount
    // ------------------------------------------------------------------

    private fun mapSchema3(bundle: LegacyImportBundle): List<VaultAccount> {
        val accounts = mutableListOf<VaultAccount>()
        var order = 0L
        val now = java.time.Instant.now().toString()

        // Group TOTP + recovery by legacy account id (both credentials share
        // the owning account's durable id).
        val totpByAccount = bundle.totpEntries
            .filter { LegacyTotpValidator.validate(it).usability == LegacyImportBundle.TOTP_USABLE }
            .groupBy { it.legacyAccountId ?: "" }
        val recoveryByAccount = bundle.recoveryCodeSets
            .groupBy { it.legacyAccountId ?: "" }

        val legacyIds = LinkedHashSet<String>()
        totpByAccount.keys.forEach { if (it.isNotEmpty()) legacyIds.add(it) }
        recoveryByAccount.keys.forEach { if (it.isNotEmpty()) legacyIds.add(it) }

        for (legacyId in legacyIds) {
            val totps = totpByAccount[legacyId].orEmpty()
            val recovery = recoveryByAccount[legacyId].orEmpty()

            val firstTotp = totps.firstOrNull()
            val firstRecovery = recovery.firstOrNull()
            val createdAt = firstTotp?.createdAt ?: firstRecovery?.createdAt ?: now

            val serviceName = if (firstTotp != null) {
                normalizedIssuer(firstTotp.issuer)
            } else {
                normalizedIssuer(firstRecovery?.title ?: "")
            }
            val accountName = if (firstTotp != null) {
                normalizedAccountName(firstTotp.accountName, firstTotp.issuer)
            } else {
                normalizedAccountName(firstRecovery?.title ?: "", null)
            }

            accounts += VaultAccount(
                // Durable identity = the persisted legacy Account id.
                stableId = stableIdForDurable(KIND_ACCOUNT, "account:$legacyId"),
                serviceName = serviceName,
                accountName = accountName,
                sortOrder = order++,
                createdAt = createdAt,
                updatedAt = createdAt,
                totpCredentials = totps.map { t ->
                    // Durable identity = the persisted TOTP credential id.
                    val durableTotpPath = "$KIND_TOTP:${t.id}"
                    VaultTotpCredential(
                        stableId = stableIdForDurable(KIND_TOTP, durableTotpPath),
                        secretBase32 = t.secretBase32,
                        algorithm = t.algorithm.trim().uppercase(),
                        digits = t.digits ?: 0,
                        periodSeconds = t.period ?: 0,
                        createdAt = t.createdAt ?: createdAt,
                    )
                },
                recoveryCodeSets = recovery.map { set ->
                    // Durable identity = the persisted recovery credential id.
                    val durableSetPath = "recovery:${set.id}"
                    VaultRecoveryCodeSet(
                        stableId = stableIdForDurable(KIND_RECOVERY_SET, durableSetPath),
                        title = set.title.trim().ifEmpty { "Recovery codes" },
                        createdAt = set.createdAt ?: createdAt,
                        codes = set.codes.mapIndexed { i, code ->
                            VaultRecoveryCode(
                                stableId = stableIdForDurable(
                                    KIND_RECOVERY_CODE,
                                    "$durableSetPath/$i",
                                ),
                                value = code,
                                status = "UNUSED",
                                usedAt = null,
                                sortOrder = i,
                            )
                        },
                    )
                },
            )
        }

        return accounts
    }

    // ------------------------------------------------------------------
    // Developer entries — faithful five-type mapping
    // ------------------------------------------------------------------

    private fun mapDeveloperEntries(
        entries: List<LegacyDeveloperEntry>,
    ): List<VaultDeveloperEntry> {
        return entries.map { e ->
            val legacyType = e.type.ifBlank { "genericSecret" }
            val createdAt = e.createdAt ?: java.time.Instant.now().toString()
            val updatedAt = e.updatedAt ?: createdAt
            // Durable identity = the persisted developer entry id.
            val durablePath = "developer:${e.id}"
            val stableId = stableIdForDurable("developer:$legacyType", durablePath)
            val payload = e.payload ?: JsonObject(emptyMap())

            try {
                mapDeveloper(legacyType, stableId, e.title, e.notes, createdAt, updatedAt, payload)
            } catch (ex: Exception) {
                throw LegacyMappingException(
                    "Failed to map legacy developer entry '${e.id}' (type $legacyType): " +
                        ex.message,
                    ex,
                )
            }
        }
    }

    private fun mapDeveloper(
        legacyType: String,
        stableId: String,
        title: String,
        notes: String,
        createdAt: String,
        updatedAt: String,
        payload: JsonObject,
    ): VaultDeveloperEntry = when (legacyType) {
        "androidSigningKey" -> VaultAndroidSigningKey(
            stableId = stableId,
            projectName = stringField(payload, "projectName"),
            packageName = stringField(payload, "packageName"),
            keystoreFileName = stringField(payload, "keystoreFileName"),
            keystoreBase64 = stringField(payload, "keystoreBytesBase64"),
            storePassword = stringField(payload, "storePassword"),
            keyAlias = stringField(payload, "keyAlias"),
            keyPassword = stringField(payload, "keyPassword"),
            title = title,
            notes = notes.ifBlank { null },
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

        "apiCredential" -> VaultApiCredential(
            stableId = stableId,
            serviceName = stringField(payload, "serviceName"),
            accountName = stringField(payload, "accountName"),
            apiKey = stringField(payload, "apiKey"),
            apiSecret = stringField(payload, "apiSecret"),
            title = title,
            notes = notes.ifBlank { null },
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

        "sshKey" -> VaultSshKey(
            stableId = stableId,
            keyName = stringField(payload, "keyName"),
            publicKey = stringField(payload, "publicKey"),
            privateKey = stringField(payload, "privateKey"),
            passphrase = stringField(payload, "passphrase"),
            title = title,
            notes = notes.ifBlank { null },
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

        "envVarSet" -> VaultEnvironmentVariableSet(
            stableId = stableId,
            projectName = stringField(payload, "projectName"),
            variables = parseKeyValuePairs(payload["variables"], "name", "value"),
            title = title,
            notes = notes.ifBlank { null },
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

        "genericSecret" -> VaultGenericSecret(
            stableId = stableId,
            fields = parseKeyValuePairs(payload["fields"], "label", "value"),
            title = title,
            notes = notes.ifBlank { null },
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

        else -> throw LegacyMappingException("Unsupported legacy developer type '$legacyType'")
    }

    private fun parseKeyValuePairs(
        element: kotlinx.serialization.json.JsonElement?,
        keyField: String,
        valueField: String,
    ): List<VaultKeyValue> {
        if (element !is JsonArray) return emptyList()
        val out = mutableListOf<VaultKeyValue>()
        for (item in element) {
            val obj = item as? JsonObject ?: continue
            val key = (obj[keyField] as? JsonPrimitive)?.content
                ?: (obj["label"] as? JsonPrimitive)?.content
                ?: ""
            val value = (obj[valueField] as? JsonPrimitive)?.content ?: ""
            if (key.isBlank()) continue
            out += VaultKeyValue(key = key, value = value)
        }
        return out
    }

    private fun stringField(obj: JsonObject, key: String): String =
        (obj[key] as? JsonPrimitive)?.content ?: ""

    private fun normalizedIssuer(issuer: String): String =
        issuer.trim().ifEmpty { "Untitled" }

    private fun normalizedAccountName(accountName: String, issuer: String?): String {
        val trimmed = accountName.trim()
        return when {
            trimmed.isNotEmpty() -> trimmed
            !issuer.isNullOrBlank() -> issuer.trim()
            else -> "Account"
        }
    }
}
