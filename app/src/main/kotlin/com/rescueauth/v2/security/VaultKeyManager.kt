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

    /**
     * @return true when a wrapped VaultKey exists on this install.
     *
     * Presence is decided by the persisted artifact, not by a successful
     * decode: a blank/corrupt blob still means the vault exists (see
     * [VaultKeyCrypto.hasPersisted]).
     */
    val hasVaultKey: Boolean
        get() = crypto.hasPersisted()

    /**
     * Generates a fresh 256-bit VaultKey, wraps it, and persists the blob.
     * @return the unwrapped VaultKey (caller must zero after use).
     */
    fun generateAndWrap(): ByteArray {
        val vaultKey = generateRandomKey()
        try {
            wrapExisting(vaultKey)
            return vaultKey
        } catch (t: Throwable) {
            vaultKey.fill(0)
            throw t
        }
    }

    /** Generates a fresh 256-bit CSPRNG VaultKey without touching AndroidKeyStore. */
    fun generateRandomKey(): ByteArray =
        ByteArray(32).also { SecureRandomHolder.nextBytes(it) }

    /** Reads the raw persisted wrapped-key blob (null if absent), for atomic rollback on rebind. */
    fun readPersistedBlob(): String? = crypto.readWrapped()

    /** Restores a previously backed-up blob when a rebind attempt fails before persisting. */
    fun restorePersistedBlob(blob: String) {
        if (!crypto.hasPersisted()) {
            crypto.persistBlob(blob)
        }
    }

    /**
     * Wraps an existing 32-byte [vaultKey] with the device Keystore key and
     * persists the blob (write-once unless [deleteWrapped] was explicitly called).
     */
    fun wrapExisting(vaultKey: ByteArray) {
        require(vaultKey.size == 32) { "VaultKey must be 32 bytes" }
        try {
            crypto.wrap(vaultKey) { blob -> crypto.persistBlob(blob) }
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
