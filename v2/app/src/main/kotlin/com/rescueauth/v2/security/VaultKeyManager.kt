package com.rescueauth.v2.security

import android.content.Context

/**
 * High-level VaultKey operations (ADR-0003 §2).
 *
 * The device-specific wrap/unwrap is delegated to a [VaultKeyCrypto]; the
 * default production implementation is [AndroidKeystoreVaultKeyCrypto].
 * Tests inject a fake crypto.
 */
class VaultKeyManager(private val crypto: VaultKeyCrypto) {

    class NoVaultKeyException(message: String) : Exception(message)
    class KeyInvalidatedException(message: String, cause: Throwable? = null) : Exception(message, cause)
    class KeystoreUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * Raised when the Keystore operation needs a user-authentication token
     * that is absent (Android `UserNotAuthenticatedException`). Maps to
     * `AUTH_REQUIRED` in the startup taxonomy — distinct from Keystore
     * unavailability and from key invalidation.
     */
    class AuthRequiredException(message: String, cause: Throwable? = null) : Exception(message, cause)

    val hasVaultKey: Boolean
        get() = !crypto.readWrapped().isNullOrBlank()

    /**
     * Generates a fresh 256-bit VaultKey, wraps it, and persists the blob.
     * @return the unwrapped VaultKey (caller must zero after use).
     */
    fun generateAndWrap(): ByteArray {
        val vaultKey = ByteArray(32).also { SecureRandomHolder.nextBytes(it) }
        return try {
            crypto.wrap(vaultKey) { blob -> crypto.persistBlob(blob) }
            vaultKey
        } catch (e: VaultKeyCrypto.AuthRequiredException) {
            // Never hide a missing user-auth token as "Keystore unavailable"
            // (the startup crash root cause) — map it to AUTH_REQUIRED.
            throw AuthRequiredException(e.message ?: "Keystore requires user authentication", e)
        } catch (e: VaultKeyCrypto.KeyInvalidatedException) {
            throw KeyInvalidatedException(e.message ?: "Keystore key invalidated", e)
        } catch (e: KeyInvalidatedException) {
            throw e
        } catch (e: Exception) {
            throw KeystoreUnavailableException("VaultKey wrap failed: ${e.message}", e)
        }
    }

    /**
     * Unwraps the persisted VaultKey. Caller must have completed
     * BiometricPrompt (or be within the validity window).
     */
    fun unwrap(): ByteArray {
        val blob = crypto.readWrapped()
            ?: throw NoVaultKeyException("No wrapped VaultKey on this install")
        return try {
            crypto.unwrap(blob)
        } catch (e: VaultKeyCrypto.AuthRequiredException) {
            throw AuthRequiredException(e.message ?: "Keystore requires user authentication", e)
        } catch (e: VaultKeyCrypto.KeyInvalidatedException) {
            throw KeyInvalidatedException(e.message ?: "Keystore key invalidated", e)
        } catch (e: Exception) {
            throw KeyInvalidatedException("VaultKey unwrap failed: ${e.message}", e)
        }
    }

    fun deleteWrapped() {
        crypto.reset()
    }

    private object SecureRandomHolder {
        private val rng = java.security.SecureRandom()
        fun nextBytes(b: ByteArray) = rng.nextBytes(b)
    }

    companion object {
        fun production(context: Context, userAuthenticationRequired: Boolean = true, validitySeconds: Int = 30): VaultKeyManager =
            VaultKeyManager(AndroidKeystoreVaultKeyCrypto(context, userAuthenticationRequired, validitySeconds))
    }
}
