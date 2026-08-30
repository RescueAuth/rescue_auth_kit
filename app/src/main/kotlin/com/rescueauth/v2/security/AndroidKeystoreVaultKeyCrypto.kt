package com.rescueauth.v2.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Production [VaultKeyCrypto] backed by AndroidKeyStore.
 *
 * The wrap key is a NON-EXPORTABLE AES-256-GCM key; `setUserAuthenticationRequired`
 * ties unwrap to a successful BiometricPrompt (or the configured validity
 * window). Biometric enrollment changes deliberately do NOT invalidate the
 * key ([ADR-0014]: with the platform default they would permanently destroy
 * the vault on a routine fingerprint change). Keystore invalidation still
 * surfaces as [VaultKeyManager.KeyInvalidatedException] (e.g. secure lock
 * screen removed) so the caller routes to the recovery flow instead of
 * deleting the database.
 */
class AndroidKeystoreVaultKeyCrypto(
    private val context: Context,
    private val userAuthenticationRequired: Boolean = true,
    private val authenticationValiditySeconds: Int = 30,
) : VaultKeyCrypto {

    override fun wrap(vaultKey: ByteArray, persist: (String) -> Unit) {
        val key = ensureKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        try {
            cipher.init(Cipher.ENCRYPT_MODE, key)
        } catch (e: android.security.keystore.UserNotAuthenticatedException) {
            // No valid auth token for the user-authentication-required key. This
            // is a startup-contract violation (auth was not performed first) or
            // a token-expiry race — never a crash / "Keystore unavailable".
            throw VaultKeyCrypto.AuthRequiredException("Keystore key requires user authentication", e)
        } catch (e: android.security.keystore.KeyPermanentlyInvalidatedException) {
            // Biometric enrollment changed / key permanently invalidated.
            throw VaultKeyManager.KeyInvalidatedException("Keystore wrap key invalidated", e)
        }
        val iv = cipher.iv
        val wrapped = cipher.doFinal(vaultKey)
        persist(Blob.encode(iv, wrapped))
    }

    override fun persistBlob(blob: String) {
        blobFile().writeText(blob)
    }

    override fun readWrapped(): String? =
        blobFile().takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }

    override fun unwrap(blob: String): ByteArray {
        val (iv, ciphertext) = Blob.decode(blob)
        val key = loadKey()
            ?: throw VaultKeyManager.KeyInvalidatedException("Keystore wrap key missing")
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ciphertext)
        } catch (e: android.security.keystore.UserNotAuthenticatedException) {
            // Auth token expired between the BiometricPrompt success and the
            // unwrap — defensive mapping to AUTH_REQUIRED, never a crash.
            throw VaultKeyCrypto.AuthRequiredException("Keystore key requires user authentication", e)
        } catch (e: java.security.InvalidKeyException) {
            throw VaultKeyManager.KeyInvalidatedException("Keystore key invalidated: ${e.message}", e)
        } catch (e: javax.crypto.AEADBadTagException) {
            throw VaultKeyManager.KeyInvalidatedException("VaultKey unwrap MAC failed", e)
        } catch (e: Exception) {
            throw VaultKeyManager.KeyInvalidatedException("VaultKey unwrap failed: ${e.message}", e)
        }
    }

    override fun reset() {
        try {
            val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS)
        } catch (_: Exception) {
            // ignore — best effort
        }
        deletePersisted()
    }

    override fun deletePersisted() {
        blobFile().delete()
    }

    private fun blobFile(): File = File(context.filesDir, BLOB_FILE)

    private fun ensureKey(): SecretKey {
        val existing = loadKey()
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER,
        )
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
        if (userAuthenticationRequired) {
            builder.setUserAuthenticationRequired(true)
            // Opt OUT of biometric-enrollment invalidation (approved review
            // 2026-08-30, see docs/ADRS/ADR-0014). With the platform default
            // (true), merely adding or removing a fingerprint permanently
            // invalidates this wrap key — and since the VaultKey is wrapped by
            // it and the SQLCipher database is keyed by the VaultKey, a routine
            // biometric change would destroy the entire vault with no recovery
            // path. Authentication is still required to unwrap (the 30s
            // validity window below); only enrollment changes no longer brick
            // the key. Removing the secure lock screen entirely still
            // invalidates auth-bound keys (platform hard constraint) — the
            // recovery flow remains the backstop for that case.
            builder.setInvalidatedByBiometricEnrollment(false)
            if (authenticationValiditySeconds > 0) {
                builder.setUserAuthenticationValidityDurationSeconds(authenticationValiditySeconds)
            }
        }
        generator.init(builder.build())
        return generator.generateKey()
    }

    private fun loadKey(): SecretKey? {
        return try {
            val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            if (ks.containsAlias(KEY_ALIAS)) ks.getKey(KEY_ALIAS, null) as? SecretKey else null
        } catch (_: Exception) {
            null
        }
    }

    private object Blob {
        fun encode(iv: ByteArray, ciphertext: ByteArray): String {
            val raw = ByteArray(1 + iv.size + ciphertext.size)
            raw[0] = iv.size.toByte()
            System.arraycopy(iv, 0, raw, 1, iv.size)
            System.arraycopy(ciphertext, 0, raw, 1 + iv.size, ciphertext.size)
            return Base64.encodeToString(raw, Base64.NO_WRAP)
        }

        fun decode(s: String): Pair<ByteArray, ByteArray> {
            val raw = Base64.decode(s, Base64.NO_WRAP)
            require(raw.size > 1) { "corrupt vault key blob" }
            val ivLen = raw[0].toInt() and 0xff
            require(ivLen in 12..16) { "corrupt IV length: $ivLen" }
            require(raw.size > 1 + ivLen) { "corrupt vault key blob size" }
            val iv = raw.copyOfRange(1, 1 + ivLen)
            val ct = raw.copyOfRange(1 + ivLen, raw.size)
            return iv to ct
        }
    }

    companion object {
        const val KEY_ALIAS = "vault_key_wrap"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val BLOB_FILE = "vault_key_wrapped.b64"
        private const val GCM_TAG_BITS = 128
    }
}
