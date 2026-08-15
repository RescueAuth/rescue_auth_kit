package com.rescueauth.v2.security

import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Test double for [VaultKeyCrypto] backed by the JVM's AES-GCM (no Android
 * Keystore needed). Mirrors the production blob format and invalidation
 * semantics so [VaultKeyManager] logic is exercised end-to-end.
 */
class FakeVaultKeyCrypto : VaultKeyCrypto {

    private val wrapKey: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private var blob: String? = null
    var invalidateOnUnwrap: Boolean = false

    /** When true, [wrap] throws [VaultKeyCrypto.AuthRequiredException] (no auth token). */
    var requireAuthOnWrap: Boolean = false

    /** When true, [unwrap] throws [VaultKeyCrypto.AuthRequiredException] (no auth token). */
    var requireAuthOnUnwrap: Boolean = false

    override fun wrap(vaultKey: ByteArray, persist: (String) -> Unit) {
        if (requireAuthOnWrap) {
            throw VaultKeyCrypto.AuthRequiredException("simulated no-auth wrap")
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrapKey)
        val iv = cipher.iv
        val wrapped = cipher.doFinal(vaultKey)
        val raw = ByteArray(1 + iv.size + wrapped.size)
        raw[0] = iv.size.toByte()
        System.arraycopy(iv, 0, raw, 1, iv.size)
        System.arraycopy(wrapped, 0, raw, 1 + iv.size, wrapped.size)
        persist(java.util.Base64.getEncoder().encodeToString(raw))
    }

    override fun persistBlob(blob: String) {
        this.blob = blob
    }

    override fun readWrapped(): String? = blob

    override fun unwrap(blob: String): ByteArray {
        if (requireAuthOnUnwrap) {
            throw VaultKeyCrypto.AuthRequiredException("simulated no-auth unwrap")
        }
        if (invalidateOnUnwrap) {
            throw VaultKeyCrypto.KeyInvalidatedException("simulated keystore invalidation")
        }
        val raw = java.util.Base64.getDecoder().decode(blob)
        val ivLen = raw[0].toInt() and 0xff
        val iv = raw.copyOfRange(1, 1 + ivLen)
        val ct = raw.copyOfRange(1 + ivLen, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrapKey, GCMParameterSpec(128, iv))
        return cipher.doFinal(ct)
    }

    override fun reset() {
        blob = null
        invalidateOnUnwrap = false
        requireAuthOnWrap = false
        requireAuthOnUnwrap = false
    }

    override fun deletePersisted() {
        blob = null
    }
}
