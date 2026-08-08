package com.rescueauth.v2.totp

import com.rescueauth.v2.export.TotpParameters
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Production TOTP core (Phase 4 P1).
 *
 * Pure Kotlin / pure JVM: **no Android, no Room, no legacy-import model**
 * dependencies. It is the shared production engine used by the Authenticator
 * vertical slice (generate / countdown / validation) and is independent of the
 * `legacy` compatibility suite.
 *
 * Reuse policy (do not reimplement crypto):
 * - Base32 decoding + algorithm/digits whitelist are reused from the shared
 *   logical layer [TotpParameters] (not the legacy package).
 * - The HOTP/TOTP primitive is RFC 4226 / RFC 6238 `HMAC + dynamic
 *   truncation`, implemented with the standard `javax.crypto.Mac` — the same
 *   verified primitive the legacy cross-check suite uses, but exposed here as
 *   the production entry point.
 *
 * Secrets must never be logged or leaked through errors.
 */
object TotpCore {

    const val DEFAULT_ALGORITHM = TotpParameters.ALGORITHM_SHA1
    const val DEFAULT_DIGITS = 6
    const val DEFAULT_PERIOD_SECONDS = 30

    val SUPPORTED_ALGORITHMS: Set<String> = TotpParameters.SUPPORTED_ALGORITHMS
    val SUPPORTED_DIGITS: Set<Int> = TotpParameters.SUPPORTED_DIGITS

    /** Error raised for any invalid parameter / secret. */
    class TotpException(message: String) : Exception(message)

    /**
     * Generates the TOTP value at [unixTimeSeconds] (RFC 6238).
     *
     * @param secretBase32 RFC 4648 Base32 secret (case-insensitive; whitespace
     *   and `-` separators are ignored).
     * @param algorithm one of [SUPPORTED_ALGORITHMS].
     * @param digits one of [SUPPORTED_DIGITS] (6..10).
     * @param periodSeconds time step in seconds (1..120).
     */
    fun generate(
        secretBase32: String,
        algorithm: String,
        digits: Int,
        periodSeconds: Int,
        unixTimeSeconds: Long,
    ): String {
        validate(algorithm, digits, periodSeconds)
        val secretBytes = try {
            TotpParameters.decodeBase32(normalizeSecret(secretBase32))
        } catch (e: IllegalArgumentException) {
            throw TotpException("invalid base32 secret")
        }
        if (secretBytes.isEmpty()) throw TotpException("secret must not be empty")

        val hmac = when (algorithm.trim().uppercase()) {
            TotpParameters.ALGORITHM_SHA1 -> "HmacSHA1"
            TotpParameters.ALGORITHM_SHA256 -> "HmacSHA256"
            TotpParameters.ALGORITHM_SHA512 -> "HmacSHA512"
            else -> throw TotpException("unsupported TOTP algorithm: $algorithm")
        }

        val counter = unixTimeSeconds / periodSeconds
        val counterBytes = java.nio.ByteBuffer.allocate(8).putLong(counter).array()

        val mac = Mac.getInstance(hmac)
        mac.init(SecretKeySpec(secretBytes, hmac))
        val hash = mac.doFinal(counterBytes)

        val offset = hash[hash.size - 1].toInt() and 0x0f
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)

        val modulo = when (digits) {
            6 -> 1_000_000L
            7 -> 10_000_000L
            8 -> 100_000_000L
            9 -> 1_000_000_000L
            10 -> 10_000_000_000L
            else -> throw TotpException("invalid digits: $digits")
        }
        return (binary % modulo).toString().padStart(digits, '0')
    }

    /**
     * Seconds remaining until the current code rotates. Returns a value in
     * `1..periodSeconds` (a freshly rotated code reports the full period).
     */
    fun remainingSeconds(unixTimeSeconds: Long, periodSeconds: Int): Int {
        if (periodSeconds !in TotpParameters.MIN_PERIOD_SECONDS..TotpParameters.MAX_PERIOD_SECONDS) {
            throw TotpException("invalid period: $periodSeconds")
        }
        val within = (unixTimeSeconds % periodSeconds).toInt() // 0..period-1
        return periodSeconds - within
    }

    /** Fraction of the period still remaining, in `(0, 1]`. */
    fun progressFraction(unixTimeSeconds: Long, periodSeconds: Int): Float {
        val remaining = remainingSeconds(unixTimeSeconds, periodSeconds)
        return remaining.toFloat() / periodSeconds
    }

    /**
     * Validates algorithm / digits / period; throws [TotpException] on error.
     * Digits must be in 6..10 and period in 1..120 (frozen v1 contract).
     */
    fun validate(algorithm: String, digits: Int, periodSeconds: Int) {
        val algo = algorithm.trim().uppercase()
        if (algo !in SUPPORTED_ALGORITHMS) throw TotpException("unsupported algorithm: $algorithm")
        if (digits !in SUPPORTED_DIGITS) throw TotpException("invalid digits: $digits")
        if (periodSeconds !in TotpParameters.MIN_PERIOD_SECONDS..TotpParameters.MAX_PERIOD_SECONDS) {
            throw TotpException("invalid period: $periodSeconds")
        }
    }

    /** True when [secretBase32] decodes to a non-empty valid Base32 payload. */
    fun isValidBase32(secretBase32: String): Boolean {
        if (secretBase32.isBlank()) return false
        return TotpParameters.isValidBase32(normalizeSecret(secretBase32))
    }

    /**
     * Normalises a Base32 secret for storage: strips whitespace / `-` / `=`
     * padding and uppercases (RFC 4648, padding optional).
     */
    fun normalizeSecret(secret: String): String =
        secret.filterNot { it == ' ' || it == '-' || it == '=' || it == '\n' || it == '\r' || it == '\t' }
            .uppercase()
}
