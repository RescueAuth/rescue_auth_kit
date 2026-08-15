package com.rescueauth.v2.session

/**
 * Outcome of a Vault open attempt (first-run create or existing unlock).
 *
 * This is the startup error taxonomy required by the startup-auth contract:
 * every Keystore / crypto / DB failure is mapped to a distinct outcome so the
 * UI can react correctly instead of crashing on an unhandled
 * `UserNotAuthenticatedException` (Issue #50).
 */
sealed interface VaultUnlockOutcome {

    /**
     * The Vault was opened successfully and the session is UNLOCKED.
     *
     * [freshFirstRunKey] is non-null only for first-run creation: the caller
     * must zero it after the DB is opened. For existing-vault unlocks it is
     * null (the key is retained by [SessionManager]).
     */
    data class Success(val freshFirstRunKey: ByteArray?) : VaultUnlockOutcome

    /** A valid user-auth token is required to open the Vault. */
    object AuthRequired : VaultUnlockOutcome

    /** The user cancelled the system auth prompt — the Vault is NOT opened. */
    object AuthCancelled : VaultUnlockOutcome

    /** Authentication / crypto failed (transient) — the Vault is NOT opened. */
    object AuthFailed : VaultUnlockOutcome

    /** No usable secure device credential or strong biometric is available. */
    object NoSecureDevice : VaultUnlockOutcome

    /** The Keystore wrap key was permanently invalidated (enrollment changed). */
    object KeyInvalidated : VaultUnlockOutcome

    /** The device Keystore is unavailable (transient hardware/service error). */
    object KeystoreUnavailable : VaultUnlockOutcome

    /** The persisted Vault cannot be opened (corrupt blob / DB error). */
    object VaultCorrupt : VaultUnlockOutcome
}
