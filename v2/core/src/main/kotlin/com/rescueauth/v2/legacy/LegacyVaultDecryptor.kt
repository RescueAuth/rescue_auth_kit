package com.rescueauth.v2.legacy

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.modes.XChaCha20Poly1305
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV

/**
 * Decrypts a legacy `.rakvault` payload.
 *
 * Pipeline (exactly mirroring the legacy Flutter app):
 *   Argon2id(password, salt) → 32-byte key
 *   XChaCha20-Poly1305(key, nonce24) → plaintext (AEAD MAC verified first)
 *
 * ## Crypto dependency (phase 1 fix, see ADR-0002)
 *
 * Since Bouncy Castle 1.85 the provider ships a **native `XChaCha20Poly1305`**
 * AEAD implementation. Empirical verification:
 *
 *   - decrypts all legacy fixtures (schema 1/2/3) byte-for-byte;
 *   - matches the official IETF draft-irtf-cfrg-xchacha-03 §2.2.1 test vector;
 *   - fails (AEAD MAC) on wrong password / wrong MAC fixtures.
 *
 * The previous self-implemented `HChaCha20` (used to construct XChaCha20 from
 * BC's plain `ChaCha20Poly1305`) is therefore **removed**. No hand-rolled
 * crypto remains; the only bespoke code is the read-only legacy envelope glue
 * in this module, which never writes legacy files and is not used by the new
 * backup format.
 *
 * The importer MUST call [LegacyKdfValidator.validate] before invoking
 * [decrypt] — never trust header KDF parameters blindly.
 */
object LegacyVaultDecryptor {

    class DecryptException(message: String, cause: Throwable? = null) : Exception(message, cause)

    fun deriveArgon2idKey(
        password: String,
        params: LegacyKdfParams,
        salt: ByteArray,
    ): ByteArray {
        val builder = org.bouncycastle.crypto.params.Argon2Parameters.Builder(
            org.bouncycastle.crypto.params.Argon2Parameters.ARGON2_id
        )
            .withVersion(org.bouncycastle.crypto.params.Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(params.iterations)
            .withMemoryAsKB(params.memoryKiB)
            .withParallelism(params.parallelism)
            .withSalt(salt)
        val generator = org.bouncycastle.crypto.generators.Argon2BytesGenerator()
        generator.init(builder.build())
        val out = ByteArray(params.hashLengthBytes)
        generator.generateBytes(password.toByteArray(Charsets.UTF_8), out)
        return out
    }

    fun decrypt(encrypted: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray {
        // Legacy format stores ciphertext and MAC as separate fields
        // (`ciphertextB64` + `macB64`), but BC's AEAD expects ct||tag.
        // The caller is responsible for concatenating them (see
        // LegacyRakVaultImporter).
        val aead = XChaCha20Poly1305()
        aead.init(false, ParametersWithIV(KeyParameter(key), nonce))
        try {
            val out = ByteArray(aead.getOutputSize(encrypted.size))
            val len = aead.processBytes(encrypted, 0, encrypted.size, out, 0)
            val finalLen = aead.doFinal(out, len)
            return out.copyOf(len + finalLen)
        } catch (e: InvalidCipherTextException) {
            throw DecryptException("AEAD authentication failed (wrong password or corrupted file)", e)
        }
    }
}
