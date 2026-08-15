package com.rescueauth.v2.update

/**
 * SHA-256 hex validation for the manifest `apkSha256` field.
 *
 * The contract requires exactly 64 canonical lowercase hex characters.
 * Uppercase hex is rejected so a single canonical representation is enforced
 * (avoiding any ambiguity in release verification).
 */
object Sha256 {

    private val HEX = "0123456789abcdef".toCharArray()

    fun isCanonicalHex(value: String): Boolean {
        if (value.length != 64) return false
        for (c in value) {
            if (c !in HEX) return false
        }
        return true
    }
}
