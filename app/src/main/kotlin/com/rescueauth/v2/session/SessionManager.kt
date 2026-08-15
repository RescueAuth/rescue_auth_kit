package com.rescueauth.v2.session

import android.content.Context
import com.rescueauth.v2.database.RescueAuthDatabase
import com.rescueauth.v2.security.VaultKeyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Coordinates unlock/lock, auto-lock timeout, background masking and Keystore
 * invalidation handling (ADR-0003 §2, §4).
 *
 * Lifecycle contract:
 * - [unlock] unwraps the VaultKey (caller must have completed BiometricPrompt)
 *   and opens the encrypted DB. On Keystore invalidation the state machine
 *   moves to KEY_INVALIDATED and the database is NOT deleted.
 * - [lock] closes the DB and zeroes the in-memory key.
 * - [onAppBackgrounded] masks the UI immediately; the actual DB lock happens
 *   after the configured timeout ([lockAfterMillis]).
 * - [onAppForegrounded] cancels the pending lock timer; the caller decides
 *   whether re-authentication is required.
 *
 * The database factory is injectable ([databaseFactory]) so tests can supply
 * an in-memory Room DB instead of the SQLCipher build (Robolectric cannot load
 * SQLCipher's JNI .so).
 */
class SessionManager(
    private val context: Context,
    private val stateMachine: SecureSessionStateMachine,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val databaseFactory: (Context, ByteArray) -> RescueAuthDatabase =
        { ctx, key -> RescueAuthDatabase.build(ctx, key) },
    // Injectable so platform tests can supply a fake VaultKeyManager instead
    // of the real AndroidKeyStore-backed one (Robolectric has no Keystore).
    private val vaultKeyManagerFactory: (Context) -> VaultKeyManager =
        { VaultKeyManager.production(it) },
) {
    private val vaultKeyManager: VaultKeyManager by lazy { vaultKeyManagerFactory(context) }
    private var database: RescueAuthDatabase? = null
    private var vaultKey: ByteArray? = null
    private var lockTimerJob: Job? = null
    private var inBackground = false
    private var backgroundedAtElapsed = 0L

    /** @return the open database (null if not unlocked). */
    fun databaseOrNull(): RescueAuthDatabase? = database

    /** Read-only access to the session state machine (used by repository wiring). */
    val sessionState: SecureSessionStateMachine get() = stateMachine

    /** Observable session state flow (used by UI collection). */
    val sessionStateFlow: kotlinx.coroutines.flow.StateFlow<SecureSessionStateMachine.State> get() = stateMachine.state

    /** @return true if a wrapped VaultKey exists (first-run check). */
    fun needsFirstRunSetup(): Boolean = !vaultKeyManager.hasVaultKey

    /**
     * First-run setup: generates + wraps the VaultKey and opens the DB.
     *
     * The caller must have completed a successful system authentication
     * prompt **before** calling this — the Keystore operation is never
     * executed first (Issue #50). On success returns the fresh key (which the
     * caller must zero) inside [VaultUnlockOutcome.Success]; on any Keystore /
     * crypto failure returns a typed [VaultUnlockOutcome] instead of crashing.
     */
    fun createVaultAndOpen(): VaultUnlockOutcome {
        if (!stateMachine.beginAuthentication()) {
            return if (stateMachine.isUnlocked()) VaultUnlockOutcome.Success(null) else VaultUnlockOutcome.AuthFailed
        }
        return try {
            val key = vaultKeyManager.generateAndWrap()
            val db = databaseFactory(context, key)
            vaultKey?.fill(0)
            vaultKey = key.copyOf()
            database = db
            stateMachine.onAuthenticationSuccess()
            VaultUnlockOutcome.Success(key)
        } catch (e: VaultKeyManager.AuthRequiredException) {
            stateMachine.onAuthenticationFailure()
            VaultUnlockOutcome.AuthRequired
        } catch (e: VaultKeyManager.KeyInvalidatedException) {
            stateMachine.onKeystoreInvalidated()
            VaultUnlockOutcome.KeyInvalidated
        } catch (e: VaultKeyManager.KeystoreUnavailableException) {
            stateMachine.onAuthenticationFailure()
            VaultUnlockOutcome.KeystoreUnavailable
        } catch (e: Exception) {
            stateMachine.onAuthenticationFailure()
            VaultUnlockOutcome.VaultCorrupt
        }
    }

    /**
     * Unlocks the session. Caller must have completed a successful system
     * authentication prompt before calling this (Issue #50 — auth-first).
     *
     * @return a typed [VaultUnlockOutcome]; on success the session is UNLOCKED.
     */
    fun unlock(): VaultUnlockOutcome {
        if (!stateMachine.beginAuthentication()) {
            return if (stateMachine.isUnlocked()) VaultUnlockOutcome.Success(null) else VaultUnlockOutcome.AuthFailed
        }
        return try {
            val key = vaultKeyManager.unwrap()
            val db = databaseFactory(context, key)
            vaultKey?.fill(0)
            vaultKey = key
            database = db
            stateMachine.onAuthenticationSuccess()
            VaultUnlockOutcome.Success(null)
        } catch (e: VaultKeyManager.AuthRequiredException) {
            stateMachine.onAuthenticationFailure()
            VaultUnlockOutcome.AuthRequired
        } catch (e: VaultKeyManager.KeyInvalidatedException) {
            stateMachine.onKeystoreInvalidated()
            VaultUnlockOutcome.KeyInvalidated
        } catch (e: VaultKeyManager.KeystoreUnavailableException) {
            stateMachine.onAuthenticationFailure()
            VaultUnlockOutcome.KeystoreUnavailable
        } catch (e: Exception) {
            stateMachine.onAuthenticationFailure()
            VaultUnlockOutcome.VaultCorrupt
        }
    }

    /**
     * Unlocks directly with an already-derived key (used by first-run setup
     * and tests that already hold the key). Caller must zero [key] after.
     */
    fun unlockWithFreshKey(key: ByteArray): Boolean {
        if (!stateMachine.beginAuthentication()) return false
        return try {
            val db = databaseFactory(context, key)
            vaultKey?.fill(0)
            vaultKey = key.copyOf()
            database = db
            stateMachine.onAuthenticationSuccess()
            true
        } catch (e: Exception) {
            stateMachine.onAuthenticationFailure()
            false
        }
    }

    /** Locks immediately: closes DB, zeroes the key. */
    fun lock() {
        lockTimerJob?.cancel()
        lockTimerJob = null
        database?.let { RescueAuthDatabase.closeDatabase(it) }
        database = null
        vaultKey?.fill(0)
        vaultKey = null
        stateMachine.lock()
    }

    /** Marks the session as locked WITHOUT re-authenticating (used by tests). */
    fun forceLockState() {
        lock()
    }

    /**
     * Called when the app goes to background. Masks immediately; schedules
     * [lockAfterMillis] (0 = lock immediately, -1 = never auto-lock).
     */
    fun onAppBackgrounded(lockAfterMillis: Long = 30_000L) {
        inBackground = true
        backgroundedAtElapsed = android.os.SystemClock.elapsedRealtime()
        lockTimerJob?.cancel()
        if (lockAfterMillis < 0) return
        if (lockAfterMillis == 0L) {
            lock()
            return
        }
        lockTimerJob = scope.launch {
            delay(lockAfterMillis)
            // Only lock if still backgrounded.
            if (inBackground) {
                lock()
            }
        }
    }

    /** Called when the app returns to foreground. */
    fun onAppForegrounded() {
        inBackground = false
        lockTimerJob?.cancel()
        lockTimerJob = null
    }

    /** @return millis spent in background (used to decide re-auth). */
    fun backgroundDurationMillis(): Long =
        if (!inBackground) 0L
        else android.os.SystemClock.elapsedRealtime() - backgroundedAtElapsed
}
