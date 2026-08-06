package com.rescueauth.v2.legacy

import kotlinx.serialization.json.JsonObject

/**
 * Uniform, schema-version-agnostic representation of a decrypted legacy vault
 * payload. Produced by [LegacyPayloadParser] and consumed by the preview /
 * mapper stages. Read-only; never serialized back to the legacy format.
 */
data class LegacyImportBundle(
    val schemaVersion: Int,
    val totpEntries: List<LegacyTotpEntry>,
    val recoveryCodeSets: List<LegacyRecoveryCodeSet>,
    val developerEntries: List<LegacyDeveloperEntry>,
    val developerSettings: Boolean = false,
) {
    val accountCount: Int
        get() = totpEntries.groupBy { it.issuer.lowercase().trim() }.size +
            recoveryCodeSets.size

    val developerCount: Int get() = developerEntries.size
}

data class LegacyTotpEntry(
    val id: String,
    val issuer: String,
    val accountName: String,
    val secretBase32: String,
    val algorithm: String,
    val digits: Int,
    val period: Int,
    val createdAt: String?,
    val legacySourceId: String? = null,
)

data class LegacyRecoveryCodeSet(
    val id: String,
    val title: String,
    val codes: List<String>,
    val createdAt: String?,
    val legacySourceId: String? = null,
)

data class LegacyDeveloperEntry(
    val id: String,
    val type: String,
    val title: String,
    val notes: String,
    val createdAt: String?,
    val updatedAt: String?,
    val payload: JsonObject?,
)
