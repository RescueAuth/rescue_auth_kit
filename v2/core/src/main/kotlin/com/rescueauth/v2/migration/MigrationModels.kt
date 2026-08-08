package com.rescueauth.v2.migration

/**
 * Result of parsing an `otpauth-migration://` URI (Phase 4 P2).
 *
 * Pure Kotlin value object — no Android / Room / Compose / legacy-model
 * dependency. Carries the per-entry classification (so valid entries are
 * never silently dropped because of an unrelated bad entry) plus the raw
 * batch metadata so the scanner batch session can route multi-QR exports.
 */
data class MigrationParseResult(
    val entries: List<MigrationTotpCandidate>,
    val batchSize: Int,
    val batchIndex: Int,
    val batchId: Int,
) {
    val importableCount: Int get() = entries.count { it.status == MigrationEntryStatus.IMPORTABLE }
    val unsupportedCount: Int get() = entries.count { it.status == MigrationEntryStatus.UNSUPPORTED }
    val invalidCount: Int get() = entries.count { it.status == MigrationEntryStatus.INVALID }
}

/**
 * One classified entry from a migration payload.
 *
 * [secretBase32] is only populated for importable entries; unsupported /
 * invalid entries carry only non-secret classification metadata. The secret
 * is intentionally kept out of any string-printing path.
 */
data class MigrationTotpCandidate(
    val status: MigrationEntryStatus,
    val reason: String? = null,
    val secretBase32: String? = null,
    val name: String? = null,
    val issuer: String? = null,
    val algorithm: String? = null,
    val digits: Int? = null,
    val periodSeconds: Int? = null,
) {
    /**
     * Secret-safe string form: the Base32 secret is **never** included, so
     * accidental logging of a candidate cannot leak credential material.
     */
    override fun toString(): String =
        "MigrationTotpCandidate(status=$status, reason=$reason, name=$name, " +
            "issuer=$issuer, algorithm=$algorithm, digits=$digits, period=$periodSeconds)"

    companion object {
        fun importable(
            secretBase32: String,
            name: String?,
            issuer: String?,
            algorithm: String,
            digits: Int,
            periodSeconds: Int,
        ): MigrationTotpCandidate = MigrationTotpCandidate(
            status = MigrationEntryStatus.IMPORTABLE,
            secretBase32 = secretBase32,
            name = name,
            issuer = issuer,
            algorithm = algorithm,
            digits = digits,
            periodSeconds = periodSeconds,
        )

        fun unsupported(
            reason: String,
            wireAlgorithm: Int,
            wireDigits: Int,
            wireType: Int,
        ): MigrationTotpCandidate = MigrationTotpCandidate(
            status = MigrationEntryStatus.UNSUPPORTED,
            reason = reason,
            algorithm = algorithmToken(wireAlgorithm),
            digits = if (wireDigits == 0) null else wireDigits,
        )

        fun invalid(reason: String): MigrationTotpCandidate =
            MigrationTotpCandidate(status = MigrationEntryStatus.INVALID, reason = reason)

        private fun algorithmToken(wireAlgorithm: Int): String? = when (wireAlgorithm) {
            0 -> "MD5"
            1 -> "SHA1"
            2 -> "SHA256"
            3 -> "SHA512"
            4 -> "SHA224"
            else -> null
        }
    }
}

enum class MigrationEntryStatus {
    /** Can be imported into native v2 and used normally. */
    IMPORTABLE,

    /** Known format but outside native v2 semantics (HOTP / algorithm / digits). */
    UNSUPPORTED,

    /** Structurally malformed (missing/empty/undecodable secret). */
    INVALID,
}
