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
 *   values. `status`/`usedAt` are handled separately by the merge planner
 *   (a used/unused divergence is a user-state difference and must never be
 *   silently dropped as pure metadata — see [MergePlanner]).
 * - **Account fingerprint** = canonical(serviceName, accountName). Used only
 *   for merge grouping; account membership is decided by the child
 *   fingerprints, never by the account label alone.
 * - **Developer Entry fingerprint** = canonical **sensitive payload** of the
 *   entry (keystore bytes / apiKey+apiSecret / private key / env values /
 *   generic field values). Labels (title / projectName / serviceName /
 *   keyName / notes) are deliberately EXCLUDED so that two entries sharing
 *   only a title are never auto-deduplicated (ROADMAP §8.3).
 *
 *   IMPORTANT: the developer fingerprint is only used for **same-stableId**
 *   comparisons (DUPLICATE vs CONFLICT of one lineage). It is NEVER used to
 *   dedupe entries with **different stableIds**: identical sensitive payloads
 *   do not prove the same logical asset (the same API key / SSH key /
 *   keystore bytes / env values can legitimately serve different
 *   service/account/project/keyName/label semantics), so different stableIds
 *   are always INSERT / keep both. Per-type full canonical logical
 *   equivalence is deferred to a later enhancement.
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

    // ------------------------------------------------------------------
    // Developer Entry fingerprints (conservative, sensitive-payload based)
    // ------------------------------------------------------------------

    /**
     * Canonical sensitive payload of a Developer Entry.
     *
     * Conservative by design (ROADMAP §8.3): only the security-sensitive
     * content participates, never the display labels. This means:
     *
     * - two SSH keys that merely share a `keyName`/`title` are NOT deduped;
     * - two entries with the same stableId but different sensitive payload
     *   (e.g. the private key was rotated) yield different fingerprints and
     *   are reported as CONFLICT.
     *
     * This fingerprint is used ONLY for same-stableId comparisons. It is
     * never used to dedupe across different stableIds (see [MergePlanner]):
     * identical sensitive payloads do not prove the same logical asset, so
     * different stableIds are always INSERT / keep both.
     */
    fun developerFingerprint(entry: VaultDeveloperEntry): String = when (entry) {
        is VaultAndroidSigningKey -> sha256Hex(
            "signing\u0000" + canonicalSecret(entry.keystoreBase64) + "\u0000" +
                entry.storePassword + "\u0000" + entry.keyAlias + "\u0000" + entry.keyPassword,
        )
        is VaultApiCredential -> sha256Hex(
            "api\u0000" + canonicalSecret(entry.apiKey) + "\u0000" + entry.apiSecret,
        )
        is VaultSshKey -> sha256Hex(
            "ssh\u0000" + entry.privateKey + "\u0000" + entry.passphrase,
        )
        is VaultEnvironmentVariableSet -> sha256Hex(
            "env\u0000" + canonicalKeyValues(entry.variables),
        )
        is VaultGenericSecret -> sha256Hex(
            "generic\u0000" + canonicalKeyValues(entry.fields),
        )
    }

    /** Deterministic canonical form of key/value pairs (sorted by key). */
    fun canonicalKeyValues(pairs: List<VaultKeyValue>): String {
        val body = buildString {
            pairs.sortedBy { it.key }.forEach { kv ->
                append(canonicalSecret(kv.key))
                append('\u0000')
                append(canonicalSecret(kv.value))
                append('\u0000')
            }
        }
        return body
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
