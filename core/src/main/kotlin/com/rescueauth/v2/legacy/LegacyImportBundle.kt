package com.rescueauth.v2.legacy

import kotlinx.serialization.json.JsonObject

/**
 * Schema-version-agnostic representation of a decrypted legacy vault payload
 * (Phase 1). Produced by [LegacyPayloadParser] and consumed by the preview /
 * mapper stages. Read-only; never serialized back to the legacy format.
 *
 * ## Phase 1 mapping contract
 *
 * The mapping to the v2 `AuthAccount` model is **entry-centric**, never
 * issuer-bucketed:
 *
 * - Schema 1/2: **each** `totpEntries[i]` becomes its **own** `AuthAccount`
 *   (holding its own single `TotpCredential`), preserving its own `issuer`,
 *   `accountName` and credential parameters verbatim. The same `serviceName`
 *   may only be used to **group rows in the UI** — it must never merge
 *   accounts or drop any accountName. `recoveryCodeSets[i]` similarly becomes
 *   its own `AuthAccount` + `RecoveryCodeSet`.
 * - Schema 3: each legacy `Account` stays one `AuthAccount` (its TOTP /
 *   recoveryCodes credentials become child rows) — unchanged.
 *
 * ## Invalid TOTP parameter policy
 *
 * Unknown or illegal `algorithm` / `digits` / `period` are **never** silently
 * replaced by SHA1 / 6 / 30 and imported as a normal credential. The original
 * values are preserved verbatim on the entry and the entry is flagged
 * [LegacyTotpEntry.usability] = INVALID; it is listed in the "not imported"
 * report and is excluded from the final DB import.
 */
data class LegacyImportBundle(
    val schemaVersion: Int,
    val totpEntries: List<LegacyTotpEntry>,
    val recoveryCodeSets: List<LegacyRecoveryCodeSet>,
    val developerEntries: List<LegacyDeveloperEntry>,
    val developerSettings: Boolean = false,
) {
    /** Number of v2 AuthAccounts this bundle maps to (after validation). */
    val accountCount: Int
        get() = totpEntries.count { it.usability == TOTP_USABLE } +
            recoveryCodeSets.size

    /** Number of TOTP entries that must be excluded from the import. */
    val invalidTotpCount: Int
        get() = totpEntries.count { it.usability != TOTP_USABLE }

    val developerCount: Int get() = developerEntries.size

    companion object {
        const val TOTP_USABLE = "usable"
        const val TOTP_INVALID = "invalid"
    }
}

/**
 * One legacy TOTP entry. Raw fields are preserved verbatim from the source;
 * validation happens in [LegacyTotpValidator] and does NOT rewrite them.
 */
data class LegacyTotpEntry(
    val id: String,
    val issuer: String,
    val accountName: String,
    val secretBase32: String,
    /** Raw algorithm string from the source (may be unknown/empty). */
    val algorithm: String,
    /** Raw digits value from the source (may be 0 / negative / absent→null). */
    val digits: Int?,
    /** Raw period value from the source (may be 0 / negative / absent→null). */
    val period: Int?,
    val createdAt: String?,
    val legacySourceId: String? = null,
    /** Legacy account id (schema 3); null for schema 1/2. Used to keep a
     *  legacy Account's TOTP + recoveryCodes as children of ONE AuthAccount. */
    val legacyAccountId: String? = null,
    /** Populated by [LegacyTotpValidator]; null before validation. */
    val usability: String? = null,
    /** Human-readable reason when [usability] is not usable. */
    val invalidationReason: String? = null,
)

data class LegacyRecoveryCodeSet(
    val id: String,
    val title: String,
    val codes: List<String>,
    val createdAt: String?,
    val legacySourceId: String? = null,
    /** Legacy account id (schema 3); null for schema 1/2. */
    val legacyAccountId: String? = null,
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
