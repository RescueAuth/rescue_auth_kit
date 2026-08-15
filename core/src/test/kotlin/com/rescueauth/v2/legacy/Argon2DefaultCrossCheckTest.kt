package com.rescueauth.v2.legacy

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * Cross-check with the LEGACY DEFAULT Argon2id parameters (19 MiB / 2 iter /
 * p1 / 32B) which the real .rakvault files use. Salt = 00..0f.
 */
class Argon2DefaultCrossCheckTest {

    @Test
    fun `kotlin argon2id 19MiB matches dart`() {
        val salt = ByteArray(16) { it.toByte() }
        val params = LegacyKdfParams("argon2id", 19456, 2, 1, 32, "")
        val key = LegacyVaultDecryptor.deriveArgon2idKey("test-password-1", params, salt)
        val expected = "9b801c05548beccf5b8d2b31949fdae5edaf026ce3f77235f2ca5a9c91d15c15"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertArrayEquals(expected, key)
    }
}
