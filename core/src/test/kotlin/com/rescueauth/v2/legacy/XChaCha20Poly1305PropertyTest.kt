package com.rescueauth.v2.legacy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.SecureRandom

/**
 * Randomized property tests for BC 1.85 native `XChaCha20Poly1305` as used
 * by the legacy importer (phase 1 fix; see ADR-0002).
 *
 * Properties:
 *   - round trip: encrypt(key, nonce, pt) → decrypt → identical pt
 *   - ciphertext integrity: flipping ANY byte of ciphertext/tag fails AEAD
 *   - nonce integrity: a different nonce fails AEAD
 *   - key integrity: a different key fails AEAD
 *   - empty plaintext round-trips
 *   - long plaintext (up to 64 KiB) round-trips
 */
class XChaCha20Poly1305PropertyTest {

    private val rng = SecureRandom()

    private fun bcEncrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, pt: ByteArray): ByteArray {
        val aead = org.bouncycastle.crypto.modes.XChaCha20Poly1305()
        aead.init(true, org.bouncycastle.crypto.params.ParametersWithIV(
            org.bouncycastle.crypto.params.KeyParameter(key), nonce))
        if (aad.isNotEmpty()) aead.processAADBytes(aad, 0, aad.size)
        val out = ByteArray(aead.getOutputSize(pt.size))
        val len = aead.processBytes(pt, 0, pt.size, out, 0)
        val fin = aead.doFinal(out, len)
        return out.copyOf(len + fin)
    }

    private fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    @Test
    fun `round trip random sizes`() {
        repeat(50) {
            val key = randomBytes(32)
            val nonce = randomBytes(24)
            val pt = randomBytes(rng.nextInt(64 * 1024))
            val ct = bcEncrypt(key, nonce, ByteArray(0), pt)
            val back = LegacyVaultDecryptor.decrypt(ct, key, nonce)
            assertArrayEquals("size=${pt.size}", pt, back)
        }
    }

    @Test
    fun `empty plaintext round trips`() {
        val key = randomBytes(32)
        val nonce = randomBytes(24)
        val ct = bcEncrypt(key, nonce, ByteArray(0), ByteArray(0))
        val back = LegacyVaultDecryptor.decrypt(ct, key, nonce)
        assertArrayEquals(ByteArray(0), back)
    }

    @Test
    fun `any ciphertext byte flip fails`() {
        repeat(30) {
            val key = randomBytes(32)
            val nonce = randomBytes(24)
            val pt = randomBytes(1 + rng.nextInt(512))
            val ct = bcEncrypt(key, nonce, ByteArray(0), pt)
            val mutated = ct.copyOf()
            val idx = rng.nextInt(mutated.size)
            mutated[idx] = (mutated[idx].toInt() xor (1 shl rng.nextInt(8))).toByte()
            assertThrows("flip at $idx", LegacyVaultDecryptor.DecryptException::class.java) {
                LegacyVaultDecryptor.decrypt(mutated, key, nonce)
            }
        }
    }

    @Test
    fun `different nonce fails`() {
        val key = randomBytes(32)
        val nonce = randomBytes(24)
        val pt = randomBytes(128)
        val ct = bcEncrypt(key, nonce, ByteArray(0), pt)
        val otherNonce = nonce.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertThrows(LegacyVaultDecryptor.DecryptException::class.java) {
            LegacyVaultDecryptor.decrypt(ct, key, otherNonce)
        }
    }

    @Test
    fun `different key fails`() {
        val key = randomBytes(32)
        val nonce = randomBytes(24)
        val pt = randomBytes(128)
        val ct = bcEncrypt(key, nonce, ByteArray(0), pt)
        val otherKey = key.copyOf().also { it[31] = (it[31].toInt() xor 1).toByte() }
        assertThrows(LegacyVaultDecryptor.DecryptException::class.java) {
            LegacyVaultDecryptor.decrypt(ct, otherKey, nonce)
        }
    }
}
