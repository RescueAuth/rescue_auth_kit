package com.rescueauth.v2.export

/**
 * Platform-neutral TOTP parameter validation (Phase 3A / shared logical layer).
 *
 * This lives in the shared logical package (not under `legacy`) so that the
 * portable snapshot validation never depends on legacy-import types — keeping
 * the ROADMAP §9 isolation boundary:
 *
 * ```
 * legacy-specific parser/crypto/models
 *         ↓
 * shared logical snapshot / merge   (this package)
 *         ↑
 * native package codec
 * ```
 *
 * The set of supported algorithms/digits mirrors the v1 legacy validator
 * (`legacy/LegacyTotpValidator`) — same rules, no dependency.
 */
object TotpParameters {

    const val ALGORITHM_SHA1 = "SHA1"
    const val ALGORITHM_SHA256 = "SHA256"
    const val ALGORITHM_SHA512 = "SHA512"

    val SUPPORTED_ALGORITHMS = setOf(
        ALGORITHM_SHA1,
        ALGORITHM_SHA256,
        ALGORITHM_SHA512,
    )

    val SUPPORTED_DIGITS = setOf(6, 7, 8)

    private val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /**
     * Decode RFC 4648 base32 (case-insensitive, separators allowed, no padding
     * required). Returns the decoded bytes. Throws [IllegalArgumentException]
     * on any invalid character.
     */
    fun decodeBase32(input: String): ByteArray {
        val cleaned = input.filterNot { it == ' ' || it == '-' || it == '\n' || it == '\r' || it == '\t' }
        var buffer = 0
        var bits = 0
        val out = java.io.ByteArrayOutputStream()
        for (c in cleaned.uppercase()) {
            val v = BASE32_ALPHABET.indexOf(c)
            if (v < 0) throw IllegalArgumentException("Invalid base32 character: $c")
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.write((buffer ushr bits) and 0xff)
            }
        }
        return out.toByteArray()
    }

    /** True when [secretBase32] is a decodable RFC 4648 base32 string. */
    fun isValidBase32(secretBase32: String): Boolean =
        try {
            decodeBase32(secretBase32)
            true
        } catch (e: IllegalArgumentException) {
            false
        }
}
