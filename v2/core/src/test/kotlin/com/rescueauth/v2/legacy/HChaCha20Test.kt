package com.rescueauth.v2.legacy

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Cross-validates HChaCha20 against the Dart `cryptography` package output
 * (same library the legacy Flutter app used to create fixtures).
 *
 * Vector (Dart Hchacha20.deriveKey):
 *   key   = 00 01 02 ... 1f
 *   nonce = 00000009 0000004a 00000000 31415927
 *   out   = 82413b4227b27bfed30e42508a877d73a0f9e4d58a74a853c12ec41326d3ecdc
 */
class HChaCha20Test {

    @Test
    fun `matches dart cryptography package hchacha20 vector`() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = byteArrayOf(
            0x00, 0x00, 0x00, 0x09,
            0x00, 0x00, 0x00, 0x4a,
            0x00, 0x00, 0x00, 0x00,
            0x31, 0x41, 0x59, 0x27,
        )
        val subkey = HChaCha20.deriveSubkey(key, nonce)
        val hex = subkey.joinToString("") { "%02x".format(it) }
        assertEquals(
            "82413b4227b27bfed30e42508a877d73a0f9e4d58a74a853c12ec41326d3ecdc",
            hex,
        )
    }
}
