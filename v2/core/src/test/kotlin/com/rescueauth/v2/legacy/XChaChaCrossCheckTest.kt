package com.rescueauth.v2.legacy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-check: decrypts a ciphertext produced by the Dart `cryptography`
 * package (the exact library used by the legacy app) with our Kotlin
 * XChaCha20-Poly1305. If this fails, decrypting legacy fixtures cannot work.
 */
class XChaChaCrossCheckTest {

    private val key = ByteArray(32) { it.toByte() }
    private val nonce = ByteArray(24) { (it + 1).toByte() }
    private val expectedPlaintext = "RescueAuth XChaCha20-Poly1305 cross-check"
    private val dartCiphertext = hexToBytes(
        "ee9e21f9de79f946416097f12f18a75065ac341e279297b5787d07ad80f6cbbe4663938a6f6e07e63d"
    )
    private val dartMac = hexToBytes("327978af00895d4479e7945b5f23eded")

    @Test
    fun `kotlin decrypts dart xchacha20-poly1305 ciphertext`() {
        val encrypted = dartCiphertext + dartMac // BC ChaCha20Poly1305 expects ct||tag
        val plain = LegacyVaultDecryptor.decrypt(encrypted, key, nonce)
        assertArrayEquals(expectedPlaintext.toByteArray(Charsets.UTF_8), plain)
    }

    @Test
    fun `dart xchacha cross vector decrypts with our hchacha`() {
        // Recompute subkey + subnonce exactly as the legacy decryptor does.
        val subkey = HChaCha20.deriveSubkey(key, nonce.copyOfRange(0, 16))
        val subnonce = ByteArray(12)
        System.arraycopy(nonce, 16, subnonce, 4, 8)
        val aead = org.bouncycastle.crypto.modes.ChaCha20Poly1305()
        aead.init(false, org.bouncycastle.crypto.params.ParametersWithIV(
            org.bouncycastle.crypto.params.KeyParameter(subkey), subnonce))
        val input = dartCiphertext + dartMac
        val out = ByteArray(aead.getOutputSize(input.size))
        val len = aead.processBytes(input, 0, input.size, out, 0)
        val finalLen = aead.doFinal(out, len)
        assertArrayEquals(expectedPlaintext.toByteArray(Charsets.UTF_8), out.copyOf(len + finalLen))
    }

    private fun hexToBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
