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
 * ## Deterministic stable identity (ROADMAP §5.7 / §9, Phase 5A §9)
 *
 * Legacy objects carry durable `id`s (schema 3 account/credential ids,
 * schema 1/2 entry ids). Those ids are **not** collision-safe globally
 * across different files, so this mapper derives every stableId as:
 *
 * ```
 * stableId = "legacy:" + <base64url(sha256(sourceFingerprint))> + ":" + <base64url(sha256(type + '\0' + legacyObjectPath))>
 * ```
 *
 * Properties:
 * - **Deterministic**: same file bytes + same object path → same stableId.
 * - **Namespaced by source**: the same object in a different `.rakvault`
 *   cannot collide.
 * - **No plaintext secret in the stableId** (SHA-256 only, never the raw
 *   secret / password / decrypted payload).
 * - Repeated import of the same file produces identical stableIds, so the
 *   shared MergePlanner sees the second import as DUPLICATE / keep both for
 *   different stableIds — no spurious new logical objects.
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

    /** Schema 3 credential discriminator (`kind`). */
    private const val KIND_TOTP = "totp"
    private const val KIND_RECOVERY = "recoveryCodes"

    /**
     * Maps [bundle] into a [VaultSnapshot] (FULL_VAULT scope).
     *
     * @param sourceFingerprintBase64Url stable identity of the original
     *   encrypted legacy file (see [fingerprintOfEncryptedBytes]).
     */
    fun map(
        bundle: LegacyImportBundle,
        sourceFingerprintBase64Url: String,
    ): VaultSnapshot {
        require(sourceFingerprintBase64Url.isNotBlank()) {
            "sourceFingerprint must not be blank"
        }

        val accounts = when (bundle.schemaVersion) {
            1, 2 -> mapEntryCentric(bundle, sourceFingerprintBase64Url)
            3 -> mapSchema3(bundle, sourceFingerprintBase64Url)
            else -> throw LegacyMappingException(
                "Unsupported legacy schemaVersion ${bundle.schemaVersion}",
            )
        }

        val developers = mapDeveloperEntries(bundle.developerEntries, sourceFingerprintBase64Url)

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
     * Deterministic stableId for one logical record.
     *
     * @param sourceFingerprint stable source identity (namespacing).
     * @param kind record kind, e.g. "account" / "totp" / "recovery_set" /
     *   "recovery_code" / "developer:androidSigningKey".
     * @param objectPath stable legacy object path/identity, e.g.
     *   "account:acc-1" / "totp:acc-1/cred-1" — NOT the secret itself.
     */
    private fun stableIdFor(
        sourceFingerprint: String,
        kind: String,
        objectPath: String,
    ): String {
        val digest = sha256("$kind\u0000$objectPath".toByteArray(Charsets.UTF_8))
        return "legacy:${base64Url(sha256(sourceFingerprint.toByteArray(Charsets.UTF_8)))}" +
            ":$kind:${base64Url(digest)}"
    }

    // ------------------------------------------------------------------
    // Schema 1/2 — entry-centric (never issuer-bucketed)
    // ------------------------------------------------------------------

    private fun mapEntryCentric(
        bundle: LegacyImportBundle,
        sourceFingerprint: String,
    ): List<VaultAccount> {
        val accounts = mutableListOf<VaultAccount>()
        var order = 0L
        val now = java.time.Instant.now().toString()

        for (entry in bundle.totpEntries) {
            val validated = LegacyTotpValidator.validate(entry)
            if (validated.usability != LegacyImportBundle.TOTP_USABLE) continue
            val createdAt = validated.createdAt ?: now
            val accountStableId = stableIdFor(
                sourceFingerprint, "account", "totp:${validated.id}",
            )
            val totp = VaultTotpCredential(
                stableId = stableIdFor(
                    sourceFingerprint, "totp", "totp:${validated.id}",
                ),
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
            val accountStableId = stableIdFor(
                sourceFingerprint, "account", "recovery:${set.id}",
            )
            val setStableId = stableIdFor(
                sourceFingerprint, "recovery_set", "recovery:${set.id}",
            )
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
                                stableId = stableIdFor(
                                    sourceFingerprint, "recovery_code", "recovery:${set.id}/$i",
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

    private fun mapSchema3(
        bundle: LegacyImportBundle,
        sourceFingerprint: String,
    ): List<VaultAccount> {
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
                stableId = stableIdFor(sourceFingerprint, "account", "account:$legacyId"),
                serviceName = serviceName,
                accountName = accountName,
                sortOrder = order++,
                createdAt = createdAt,
                updatedAt = createdAt,
                totpCredentials = totps.map { t ->
                    VaultTotpCredential(
                        stableId = stableIdFor(
                            sourceFingerprint, "totp", "account:$legacyId/totp:${t.id}",
                        ),
                        secretBase32 = t.secretBase32,
                        algorithm = t.algorithm.trim().uppercase(),
                        digits = t.digits ?: 0,
                        periodSeconds = t.period ?: 0,
                        createdAt = t.createdAt ?: createdAt,
                    )
                },
                recoveryCodeSets = recovery.map { set ->
                    VaultRecoveryCodeSet(
                        stableId = stableIdFor(
                            sourceFingerprint, "recovery_set", "account:$legacyId/recovery:${set.id}",
                        ),
                        title = set.title.trim().ifEmpty { "Recovery codes" },
                        createdAt = set.createdAt ?: createdAt,
                        codes = set.codes.mapIndexed { i, code ->
                            VaultRecoveryCode(
                                stableId = stableIdFor(
                                    sourceFingerprint,
                                    "recovery_code",
                                    "account:$legacyId/recovery:${set.id}/$i",
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
        sourceFingerprint: String,
    ): List<VaultDeveloperEntry> {
        return entries.mapIndexed { index, e ->
            val legacyType = e.type.ifBlank { "genericSecret" }
            val createdAt = e.createdAt ?: java.time.Instant.now().toString()
            val updatedAt = e.updatedAt ?: createdAt
            val path = "developer:$index:${e.id}"
            val stableId = stableIdFor(sourceFingerprint, "developer:$legacyType", path)
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
