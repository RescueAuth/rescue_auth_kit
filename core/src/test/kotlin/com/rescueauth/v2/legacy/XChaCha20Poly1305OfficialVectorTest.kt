package com.rescueauth.v2.legacy

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * Official IETF test vector for XChaCha20-Poly1305.
 *
 * Source: draft-irtf-cfrg-xchacha-03 §2.2.1 (AEAD_XCHACHA20_POLY1305).
 * Same plaintext/nonce/AAD as RFC 8439 §2.8.2, but the XChaCha20 nonce is
 * 24 bytes (counter = 0, and the 192-bit nonce). The ciphertext+tag below is
 * the full 130-byte output.
 *
 * This test pins BC's native `XChaCha20Poly1305` (the implementation used by
 * the legacy importer) against the published standard vector.
 */
class XChaCha20Poly1305OfficialVectorTest {

    private val key = hex(
        "808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f"
    )
    private val nonce = hex("404142434445464748494a4b4c4d4e4f5051525354555657")
    private val aad = hex("50515253c0c1c2c3c4c5c6c7")
    private val plaintext =
        "Ladies and Gentlemen of the class of '99: If I could offer you only " +
            "one tip for the future, sunscreen would be it."
    private val expectedCiphertextAndTag = hex(
        "bd6d179d3e83d43b9576579493c0e939" +
            "572a1700252bfaccbed2902c21396cbb" +
            "731c7f1b0b4aa6440bf3a82f4eda7e39" +
            "ae64c6708c54c216cb96b72e1213b452" +
            "2f8c9ba40db5d945b11b69b982c1bb9e" +
            "3f3fac2bc369488f76b2383565d3fff9" +
            "21f9664c97637da9768812f615c68b13" +
            "b52ec0875924c1c7987947deafd8780acf49"
    )

    @Test
    fun `bc native xchacha20poly1305 matches official vector`() {
        val aead = org.bouncycastle.crypto.modes.XChaCha20Poly1305()
        aead.init(true, org.bouncycastle.crypto.params.ParametersWithIV(
            org.bouncycastle.crypto.params.KeyParameter(key), nonce))
        aead.processAADBytes(aad, 0, aad.size)
        val out = ByteArray(aead.getOutputSize(plaintext.toByteArray(Charsets.UTF_8).size))
        val len = aead.processBytes(
            plaintext.toByteArray(Charsets.UTF_8), 0, plaintext.toByteArray(Charsets.UTF_8).size, out, 0)
        val finalLen = aead.doFinal(out, len)
        assertArrayEquals(expectedCiphertextAndTag, out.copyOf(len + finalLen))
    }

    private fun hex(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
