package com.rescueauth.v2.legacy

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV

/**
 * Decrypts a legacy `.rakvault` payload.
 *
 * Pipeline (exactly mirroring the legacy Flutter app):
 *   Argon2id(password, salt) → 32-byte key
 *   XChaCha20-Poly1305(key, nonce24) → plaintext (AEAD MAC verified first)
 *
 * XChaCha20-Poly1305 is implemented per the Dart `cryptography` package
 * construction (which follows the IETF XChaCha draft):
 *   subkey  = HChaCha20(key, nonce[0..15])
 *   subnonce = 0x00000000 || nonce[16..23]
 *   then standard ChaCha20-Poly1305 (RFC 8439) with the subkey/subnonce.
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
        val subkey = HChaCha20.deriveSubkey(key, nonce.copyOfRange(0, 16))
        val subnonce = ByteArray(12)
        // 4 zero bytes prefix (IETF XChaCha) then the last 8 nonce bytes.
        System.arraycopy(nonce, 16, subnonce, 4, 8)

        val aead = ChaCha20Poly1305()
        aead.init(
            false,
            ParametersWithIV(KeyParameter(subkey), subnonce)
        )
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
