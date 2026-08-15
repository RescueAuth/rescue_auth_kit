package com.rescueauth.v2.legacy

import com.rescueauth.v2.legacy.LegacyImportBundle.Companion.TOTP_INVALID
import com.rescueauth.v2.legacy.LegacyImportBundle.Companion.TOTP_USABLE

/**
 * Validates legacy TOTP parameters WITHOUT rewriting them.
 *
 * ## Policy (phase 1 fix)
 *
 * Unknown or illegal `algorithm` / `digits` / `period` must never be silently
 * replaced by SHA1 / 6 / 30 and imported as a normal credential — doing so
 * would generate TOTP codes that differ from the old app (wrong algorithm,
 * wrong width, wrong time step) while the user believes they are correct.
 *
 * Instead:
 *   - the original raw fields are preserved verbatim on [LegacyTotpEntry];
 *   - [LegacyTotpEntry.usability] is set to INVALID with a concrete reason;
 *   - the entry is excluded from the final DB import and reported in the
 *     "not imported" report (see [LegacyImportReport]).
 *
 * An invalid base32 secret is also rejected — a malformed secret cannot
 * produce a correct TOTP, so importing it silently is forbidden too.
 *
 * These rules are frozen by docs/LEGACY_IMPORT.md §5.2 and covered by
 * fixture `schema1_invalid_totp_params`.
 */
object LegacyTotpValidator {

    private val SUPPORTED_ALGORITHMS = setOf(
        TotpVerifier.ALGORITHM_SHA1,
        TotpVerifier.ALGORITHM_SHA256,
        TotpVerifier.ALGORITHM_SHA512,
    )

    private val SUPPORTED_DIGITS = setOf(6, 7, 8)

    /**
     * Returns a copy of [entry] with `usability`/`invalidationReason` filled.
     * Raw algorithm/digits/period are left untouched.
     */
    fun validate(entry: LegacyTotpEntry): LegacyTotpEntry {
        val rawAlgo = entry.algorithm.trim().uppercase()
        val reason = when {
            rawAlgo.isEmpty() -> "missing algorithm"
            rawAlgo !in SUPPORTED_ALGORITHMS -> "unknown algorithm '$rawAlgo'"
            entry.digits == null -> "missing digits"
            entry.digits !in SUPPORTED_DIGITS -> "invalid digits '${entry.digits}'"
            entry.period == null -> "missing period"
            entry.period <= 0 -> "invalid period '${entry.period}'"
            else -> null
        }
        if (reason != null) {
            return entry.copy(usability = TOTP_INVALID, invalidationReason = reason)
        }
        // Secret is validated last: a malformed base32 cannot generate a
        // correct TOTP, so it must not be imported silently either.
        return try {
            TotpVerifier.decodeBase32(entry.secretBase32)
            entry.copy(usability = TOTP_USABLE)
        } catch (e: TotpVerifier.TotpException) {
            entry.copy(
                usability = TOTP_INVALID,
                invalidationReason = "invalid base32 secret: ${e.message}",
            )
        }
    }
}
