package com.rescueauth.v2.security

/**
 * Abstraction over the device key store so the VaultKey wrap/unwrap logic can
 * be unit-tested without a hardware-backed AndroidKeyStore (which Robolectric
 * does not provide).
 *
 * Production: [AndroidKeystoreVaultKeyCrypto] wraps/unwraps the VaultKey with
 * a non-exportable AES-GCM key in AndroidKeyStore (ADR-0003 §2).
 *
 * The persisted blob format is the same regardless of backend:
 *   base64( ivLen(1) || iv || AES-GCM( vaultKey ) )
 */
interface VaultKeyCrypto {

    /** Raised by [unwrap] when the wrap key is permanently invalid. */
    class KeyInvalidatedException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * Wraps [vaultKey] and hands the blob to [persist] for storage.
     */
    fun wrap(vaultKey: ByteArray, persist: (String) -> Unit)

    /** Returns the wrapped blob string (null if none). */
    fun readWrapped(): String?

    /** Persists a wrapped blob (production writes to app-private dir). */
    fun persistBlob(blob: String)

    /**
     * Unwraps [blob] back to the VaultKey.
     *
     * @throws KeyInvalidatedException when the wrap key is permanently invalid.
     */
    fun unwrap(blob: String): ByteArray

    /** Deletes the wrap key and blob. */
    fun reset()

    fun deletePersisted()
}
