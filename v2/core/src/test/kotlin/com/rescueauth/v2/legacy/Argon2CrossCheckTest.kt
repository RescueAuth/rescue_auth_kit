package com.rescueauth.v2.legacy

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * Cross-check: derives an Argon2id key with the SAME parameters as the Dart
 * `cryptography` package (used by the legacy app) and compares to the Dart
 * output. If Argon2id differs, legacy fixtures cannot be decrypted.
 */
class Argon2CrossCheckTest {

    private val salt = ByteArray(16) { it.toByte() }

    // Dart cryptography 2.9.0: Argon2id(memory: 1024, iterations: 2,
    // parallelism: 1, hashLength: 32), password "test-password-1".
    private val expectedKeyHex =
        "222f21f7ca93d4c65b988598fd96bbd9f480bb412539a0cb70ac0f734df2e472"

    @Test
    fun `kotlin argon2id matches dart cryptography output`() {
        val params = LegacyKdfParams(
            name = "argon2id",
            memoryKiB = 1024,
            iterations = 2,
            parallelism = 1,
            hashLengthBytes = 32,
            saltB64 = "",
        )
        val key = LegacyVaultDecryptor.deriveArgon2idKey("test-password-1", params, salt)
        val hex = key.joinToString("") { "%02x".format(it) }
        assertArrayEquals(expectedKeyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), key)
    }
}
