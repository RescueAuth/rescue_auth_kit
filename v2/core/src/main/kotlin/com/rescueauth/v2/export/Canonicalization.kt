package com.rescueauth.v2.export

import java.security.MessageDigest

/**
 * Canonicalization and semantic fingerprinting (Phase 3A §Identity).
 *
 * ## Canonicalization
 *
 * Normalises business fields so two logically identical records from
 * different sources compare equal:
 *
 * - `serviceName` / `accountName` / `title`: trimmed, runs of whitespace
 *   collapsed to a single space, case-folded. Comparison is case-insensitive.
 * - TOTP `algorithm`: trimmed + uppercased (`sha1` → `SHA1`).
 * - `secretBase32`: stripped of all whitespace / `-`, uppercased (RFC 4648
 *   base32 is case-insensitive). Invalid base32 is never normalised — it is
 *   rejected by validation instead.
 * - Empty strings are never collapsed to null. `null` and empty remain
 *   distinct inputs (a missing label is NOT equal to an empty label).
 *
 * ## Semantic fingerprint
 *
 * The fingerprint identifies "logically the same credential" for
 * deduplication/conflict detection:
 *
 * - **TOTP credential fingerprint** = canonical(secret, algorithm, digits,
 *   periodSeconds). This is the true identity of a TOTP credential.
 *   `serviceName`/`accountName` are intentionally NOT included: two devices
 *   that scan the same QR code (same secret+params) are the same credential
 *   even if one device later renamed the account.
 * - **Recovery-code-set fingerprint** = canonical title + canonical code
 *   values (status/usedAt excluded — using a code on one device must not make
 *   a later import "conflict").
 * - **Account fingerprint** = canonical(serviceName, accountName). Used only
 *   for merge grouping; account membership is decided by the child
 *   fingerprints, never by the account label alone.
 *
 * ## Security
 *
 * The TOTP fingerprint is derived from the secret and MUST be treated as
 * secret-derived material:
 * - it is computed on demand during planning/import only;
 * - it is never persisted to the database;
 * - it must never be placed in the plaintext package header (Phase 3B
 *   contract) — only inside the encrypted payload.
 * The digest used (SHA-256) is not a password KDF; its purpose here is
 * equality/identity, not key derivation.
 */
object Canonicalization {

    // ------------------------------------------------------------------
    // Field normalisation
    // ------------------------------------------------------------------

    private val WHITESPACE = Regex("\\s+")

    /** Collapse whitespace runs to one space, trim, uppercase (labels). */
    fun canonicalLabel(value: String): String =
        WHITESPACE.replace(value.trim(), " ").uppercase()

    /** Base32 secrets: strip separators/whitespace, uppercase (RFC 4648). */
    fun canonicalSecret(value: String): String =
        value.filterNot { it == ' ' || it == '-' || it == '\n' || it == '\r' || it == '\t' }
            .uppercase()

    /** TOTP algorithm token. */
    fun canonicalAlgorithm(value: String): String = value.trim().uppercase()

    /** TOTP digits (allowed 6..8 per legacy validator; kept verbatim here). */
    fun canonicalDigits(value: Int): Int = value

    fun canonicalPeriod(value: Int): Int = value

    // ------------------------------------------------------------------
    // Fingerprints
    // ------------------------------------------------------------------

    /**
     * Semantic fingerprint of a TOTP credential (secret + parameters).
     * Two credentials with the same fingerprint MUST generate the same TOTP
     * codes; two credentials with different fingerprints MUST NOT be merged.
     */
    fun totpFingerprint(secretBase32: String, algorithm: String, digits: Int, periodSeconds: Int): String =
        sha256Hex(
            "totp\u0000" +
                canonicalSecret(secretBase32) + "\u0000" +
                canonicalAlgorithm(algorithm) + "\u0000" +
                digits + "\u0000" +
                periodSeconds,
        )

    /** Convenience overload. */
    fun totpFingerprint(c: VaultTotpCredential): String =
        totpFingerprint(c.secretBase32, c.algorithm, c.digits, c.periodSeconds)

    /** Semantic fingerprint of a recovery-code set (title + code values). */
    fun recoverySetFingerprint(title: String, codes: List<VaultRecoveryCode>): String {
        val body = buildString {
            append("recset\u0000")
            append(canonicalLabel(title))
            append('\u0000')
            // Codes ordered by sortOrder, then value — deterministic.
            codes.sortedWith(compareBy({ it.sortOrder }, { it.value })).forEach { c ->
                append(canonicalLabel(c.value))
                append('\u0000')
            }
        }
        return sha256Hex(body)
    }

    /**
     * Account-level fingerprint (serviceName + accountName). Used only to
     * group/attach child credentials during merge; never used to prove that
     * two accounts are "the same" — that decision belongs to the child
     * fingerprints (a label change is metadata, not a new credential).
     */
    fun accountFingerprint(serviceName: String, accountName: String): String =
        sha256Hex(
            "account\u0000" +
                canonicalLabel(serviceName) + "\u0000" +
                canonicalLabel(accountName),
        )

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
