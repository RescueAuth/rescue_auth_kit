package com.rescueauth.v2.totp

/**
 * Parsed result of a TOTP otpauth:// URI (Phase 4 P1).
 *
 * This is a pure Kotlin value object used by the production Authenticator
 * add flow. It deliberately has **no** Room / Android / legacy-model
 * dependency and never touches the database directly.
 */
data class ParsedTotp(
    /** Raw issuer from `issuer` query, or from the `issuer:account` label prefix. */
    val issuer: String?,
    /** Account label (`issuer:account` → the part after `:`, percent-decoded). */
    val accountName: String?,
    /** RFC 4648 Base32 secret (uppercased, separators stripped). */
    val secretBase32: String,
    /** SHA1 | SHA256 | SHA512 (defaults to SHA1). */
    val algorithm: String,
    /** 6/7/8 (defaults to 6). */
    val digits: Int,
    /** positive seconds (defaults to 30). */
    val periodSeconds: Int,
)
