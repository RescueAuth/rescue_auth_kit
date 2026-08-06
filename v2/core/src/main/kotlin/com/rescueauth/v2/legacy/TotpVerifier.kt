package com.rescueauth.v2.legacy

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 6238 TOTP / RFC 4226 HOTP verification used by the legacy import
 * compatibility suite. Pure, stateless; never logs secrets.
 */
object TotpVerifier {

    const val ALGORITHM_SHA1 = "SHA1"
    const val ALGORITHM_SHA256 = "SHA256"
    const val ALGORITHM_SHA512 = "SHA512"

    private val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    class TotpException(message: String) : Exception(message)

    /** Decode RFC 4648 base32 (case-insensitive, no padding required). */
    fun decodeBase32(input: String): ByteArray {
        val cleaned = input.filterNot { it == ' ' || it == '-' || it == '\n' || it == '\r' || it == '\t' }
        var buffer = 0
        var bits = 0
        val out = java.io.ByteArrayOutputStream()
        for (c in cleaned.uppercase()) {
            val v = BASE32_ALPHABET.indexOf(c)
            if (v < 0) throw TotpException("Invalid base32 character: $c")
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.write((buffer ushr bits) and 0xff)
            }
        }
        return out.toByteArray()
    }

    /**
     * Compute the TOTP value at the given Unix time (seconds) per RFC 6238.
     * Uses a fixed test time so results are deterministic and cross-checkable
     * against the RFC 4226 vectors.
     */
    fun totpAt(
        secretBase32: String,
        algorithm: String,
        digits: Int,
        periodSeconds: Int,
        unixTimeSeconds: Long,
    ): String {
        if (digits !in 6..10) throw TotpException("digits out of range: $digits")
        if (periodSeconds <= 0) throw TotpException("period must be positive")
        val hmac = when (algorithm.uppercase()) {
            ALGORITHM_SHA1 -> "HmacSHA1"
            ALGORITHM_SHA256 -> "HmacSHA256"
            ALGORITHM_SHA512 -> "HmacSHA512"
            else -> throw TotpException("Unsupported TOTP algorithm: $algorithm")
        }
        val counter = unixTimeSeconds / periodSeconds
        val counterBytes = java.nio.ByteBuffer.allocate(8).putLong(counter).array()

        val mac = Mac.getInstance(hmac)
        mac.init(SecretKeySpec(decodeBase32(secretBase32), hmac))
        val hash = mac.doFinal(counterBytes)

        val offset = hash[hash.size - 1].toInt() and 0x0f
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        val otp = binary % 1_000_000_000L // mod 10^digits handled below
        val modulo = when (digits) {
            6 -> 1_000_000L
            7 -> 10_000_000L
            8 -> 100_000_000L
            9 -> 1_000_000_000L
            else -> 10_000_000_000L
        }
        val value = otp % modulo
        return value.toString().padStart(digits, '0')
    }
}
