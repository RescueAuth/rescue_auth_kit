package com.rescueauth.v2.session

import android.content.Context
import com.rescueauth.v2.database.RescueAuthDatabase
import com.rescueauth.v2.security.BackupPinVaultSlot
import com.rescueauth.v2.security.VaultKeyManager
import com.rescueauth.v2.vaultkey.BackupPinCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Snapshot of the vault's configured unlock slots (ADR-0015).
 */
data class VaultSecurityStatus(
    val hasSystemKeySlot: Boolean = false,
    val isSystemKeyInvalidated: Boolean = false,
    val hasBackupPin: Boolean = false,
)

/**
 * Typed result of an unlock attempt via the Backup PIN slot (ADR-0015).
 */
sealed interface BackupPinUnlockResult {
    object Success : BackupPinUnlockResult
    data class WrongPin(val failedAttempts: Int, val cooldownSeconds: Int) : BackupPinUnlockResult
    data class Cooldown(val remainingSeconds: Int) : BackupPinUnlockResult
    object InvalidPinFormat : BackupPinUnlockResult
    object CorruptOrMissing : BackupPinUnlockResult
}

/**
 * Coordinates unlock/lock, dual-slot VaultKey wrapping (Android Keystore +
 * Backup PIN), auto-lock timeout, background masking, and Keystore invalidation
 * recovery (ADR-0003 §2, §4; ADR-0015).
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
    private val backupPinSlotFactory: (Context) -> BackupPinVaultSlot =
        { BackupPinVaultSlot(it) },
) {
    private val vaultKeyManager: VaultKeyManager by lazy { vaultKeyManagerFactory(context) }
    private val backupPinSlot: BackupPinVaultSlot by lazy { backupPinSlotFactory(context) }

    /** Serializes open/close/slot-mutation transitions when startup opening runs off-main. */
    private val lifecycleLock = Any()

    @Volatile
    private var database: RescueAuthDatabase? = null

    @Volatile
    private var vaultKey: ByteArray? = null

    @Volatile
    private var systemSlotInvalidated = false

    private val _securityStatus = MutableStateFlow(VaultSecurityStatus())
    val securityStatusFlow: StateFlow<VaultSecurityStatus> = _securityStatus.asStateFlow()

    private var lockTimerJob: Job? = null
    private var inBackground = false
    private var backgroundedAtElapsed = 0L

    /** @return the open database (null if not unlocked). */
    fun databaseOrNull(): RescueAuthDatabase? = database

    /** Read-only access to the session state machine (used by repository wiring). */
    val sessionState: SecureSessionStateMachine get() = stateMachine

    /** Observable session state flow (used by UI collection). */
    val sessionStateFlow: StateFlow<SecureSessionStateMachine.State> get() = stateMachine.state

    /** True when the Android Keystore slot exists and has not failed with invalidation. */
    val hasSystemKeySlot: Boolean
        get() = vaultKeyManager.hasVaultKey && !systemSlotInvalidated

    /** True when any Keystore wrapped-key artifact exists on disk (even if invalidated). */
    val hasAnySystemArtifact: Boolean
        get() = vaultKeyManager.hasVaultKey

    /** True when the Keystore slot exists on disk but failed unwrap with permanent invalidation. */
    val isSystemKeyInvalidated: Boolean
        get() = vaultKeyManager.hasVaultKey && systemSlotInvalidated

    /** True when a Backup PIN slot envelope exists on disk. */
    val hasBackupPin: Boolean
        get() = backupPinSlot.hasPersisted()

    /** Recomputes and publishes the current [VaultSecurityStatus]. */
    fun refreshSecurityStatus(): VaultSecurityStatus {
        val status = VaultSecurityStatus(
            hasSystemKeySlot = hasSystemKeySlot,
            isSystemKeyInvalidated = isSystemKeyInvalidated,
            hasBackupPin = hasBackupPin,
        )
        _securityStatus.value = status
        return status
    }

    /**
     * @return true only when this install has NO vault at all — no Keystore
     * wrapped VaultKey, NO Backup PIN wrapped VaultKey, AND no database file.
     *
     * A machine owns exactly ONE vault (Single-Vault Invariant, ADR-0015 §1).
     */
    fun needsFirstRunSetup(): Boolean =
        !vaultKeyManager.hasVaultKey && !backupPinSlot.hasPersisted() && !databaseFileExists()

    /**
     * First-run setup via system authentication (Slot A): generates + wraps the
     * VaultKey in Android Keystore and opens the DB.
     */
    fun createVaultAndOpen(): VaultUnlockOutcome = synchronized(lifecycleLock) {
        if (!needsFirstRunSetup()) return unlock()
        if (!stateMachine.beginAuthentication()) {
            return if (stateMachine.isUnlocked()) VaultUnlockOutcome.Success(null) else VaultUnlockOutcome.AuthFailed
        }
        return try {
            val key = vaultKeyManager.generateAndWrap()
            val db = try {
                openDatabase(key)
            } catch (e: Exception) {
                key.fill(0)
                vaultKeyManager.deleteWrapped()
                throw e
            }
            vaultKey?.fill(0)
            vaultKey = key.copyOf()
            database = db
            systemSlotInvalidated = false
            stateMachine.onAuthenticationSuccess()
            refreshSecurityStatus()
            VaultUnlockOutcome.Success(key)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: VaultKeyManager.AuthRequiredException) {
            stateMachine.onAuthenticationFailure()
            VaultUnlockOutcome.AuthRequired
        } catch (e: VaultKeyManager.KeyInvalidatedException) {
            systemSlotInvalidated = true
            stateMachine.onKeystoreInvalidated()
            refreshSecurityStatus()
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
     * First-run setup via Backup PIN (Slot B — used on lockless devices, ADR-0015 §3).
     * Never overwrites an existing vault if any artifact already exists on disk.
     */
    fun createVaultWithBackupPinAndOpen(pin: String): VaultUnlockOutcome = synchronized(lifecycleLock) {
        if (!needsFirstRunSetup()) {
            return VaultUnlockOutcome.AuthFailed
        }
        if (BackupPinCodec.validateNewPin(pin) != null) {
            return VaultUnlockOutcome.AuthFailed
        }
        if (!stateMachine.beginAuthentication()) {
            return if (stateMachine.isUnlocked()) VaultUnlockOutcome.Success(null) else VaultUnlockOutcome.AuthFailed
        }
        val key = vaultKeyManager.generateRandomKey()
        return try {
            backupPinSlot.wrapAndPersistFirstRun(key, pin)
            val db = try {
                openDatabase(key)
            } catch (e: Exception) {
                backupPinSlot.delete()
                throw e
            }
            vaultKey?.fill(0)
            vaultKey = key.copyOf()
            database = db
            stateMachine.onAuthenticationSuccess()
            refreshSecurityStatus()
            VaultUnlockOutcome.Success(key)
        } catch (e: kotlinx.coroutines.CancellationException) {
            key.fill(0)
            throw e
        } catch (e: Exception) {
            key.fill(0)
            stateMachine.onAuthenticationFailure()
            VaultUnlockOutcome.VaultCorrupt
        }
    }

    /**
     * First-run setup with BOTH unlock slots (ADR-0015 §3.2): generates one
     * random 256-bit VaultKey, wraps it in Android Keystore (Slot A) and under
     * the Backup PIN (Slot B), then opens the SQLCipher database.
     *
     * The operation is all-or-nothing. If either slot write or the database
     * open fails, every slot written so far, the temporary key material and
     * the session state are rolled back. There is no single-slot fallback.
     */
    fun createVaultWithSystemAndBackupPinAndOpen(pin: String): VaultUnlockOutcome = synchronized(lifecycleLock) {
        if (!needsFirstRunSetup()) return VaultUnlockOutcome.AuthFailed
        if (BackupPinCodec.validateNewPin(pin) != null) return VaultUnlockOutcome.AuthFailed
        if (!stateMachine.beginAuthentication()) {
            return if (stateMachine.isUnlocked()) VaultUnlockOutcome.Success(null) else VaultUnlockOutcome.AuthFailed
        }

        val key = vaultKeyManager.generateRandomKey()
        var systemSlotWritten = false
        var backupPinSlotWritten = false
        var openedDb: RescueAuthDatabase? = null
        try {
            vaultKeyManager.wrapExisting(key)
            systemSlotWritten = true
            backupPinSlot.wrapAndPersistFirstRun(key, pin)
            backupPinSlotWritten = true
            openedDb = openDatabase(key)

            vaultKey?.fill(0)
            vaultKey = key.copyOf()
            database = openedDb
            systemSlotInvalidated = false
            stateMachine.onAuthenticationSuccess()
            refreshSecurityStatus()
            return VaultUnlockOutcome.Success(key)
        } catch (e: kotlinx.coroutines.CancellationException) {
            rollbackDualSlotCreation(systemSlotWritten, backupPinSlotWritten, openedDb, key)
            stateMachine.onAuthenticationFailure()
            throw e
        } catch (e: VaultKeyManager.AuthRequiredException) {
            rollbackDualSlotCreation(systemSlotWritten, backupPinSlotWritten, openedDb, key)
            stateMachine.onAuthenticationFailure()
            return VaultUnlockOutcome.AuthRequired
        } catch (e: VaultKeyManager.KeyInvalidatedException) {
            rollbackDualSlotCreation(systemSlotWritten, backupPinSlotWritten, openedDb, key)
            stateMachine.onAuthenticationFailure()
            refreshSecurityStatus()
            return VaultUnlockOutcome.KeyInvalidated
        } catch (e: VaultKeyManager.KeystoreUnavailableException) {
            rollbackDualSlotCreation(systemSlotWritten, backupPinSlotWritten, openedDb, key)
            stateMachine.onAuthenticationFailure()
            return VaultUnlockOutcome.KeystoreUnavailable
        } catch (e: Exception) {
            rollbackDualSlotCreation(systemSlotWritten, backupPinSlotWritten, openedDb, key)
            stateMachine.onAuthenticationFailure()
            return VaultUnlockOutcome.VaultCorrupt
        } finally {
            key.fill(0)
        }
    }

    /**
     * Removes every artifact produced by an unsuccessful dual-slot create.
     * The caller checked needsFirstRunSetup under lifecycleLock before writing.
     */
    private fun rollbackDualSlotCreation(
        systemSlotWritten: Boolean,
        backupPinSlotWritten: Boolean,
        openedDb: RescueAuthDatabase?,
        @Suppress("UNUSED_PARAMETER") key: ByteArray,
    ) {
        openedDb?.let { RescueAuthDatabase.closeDatabase(it) }
        database = null
        vaultKey?.fill(0)
        vaultKey = null
        if (systemSlotWritten) vaultKeyManager.deleteWrapped()
        if (backupPinSlotWritten) backupPinSlot.delete()
        systemSlotInvalidated = false
        key.fill(0)
        refreshSecurityStatus()
    }

    /**
     * Unlocks the session via the Android Keystore slot (Slot A). Caller must
     * have completed a successful system authentication prompt before calling
     * this (Issue #50 — auth-first).
     */
    fun unlock(): VaultUnlockOutcome = synchronized(lifecycleLock) {
        if (!stateMachine.beginAuthentication()) {
            return if (stateMachine.isUnlocked()) VaultUnlockOutcome.Success(null) else VaultUnlockOutcome.AuthFailed
        }
        return try {
            val key = vaultKeyManager.unwrap()
            val db = openDatabase(key)
            vaultKey?.fill(0)
            vaultKey = key
            database = db
            systemSlotInvalidated = false
            stateMachine.onAuthenticationSuccess()
            refreshSecurityStatus()
            VaultUnlockOutcome.Success(null)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: VaultKeyManager.AuthRequiredException) {
            stateMachine.onAuthenticationFailure()
            VaultUnlockOutcome.AuthRequired
        } catch (e: VaultKeyManager.KeyInvalidatedException) {
            systemSlotInvalidated = true
            stateMachine.onKeystoreInvalidated()
            refreshSecurityStatus()
            VaultUnlockOutcome.KeyInvalidated
        } catch (e: VaultKeyManager.NoVaultKeyException) {
            stateMachine.onAuthenticationFailure()
            refreshSecurityStatus()
            VaultUnlockOutcome.VaultCorrupt
        } catch (e: VaultKeyManager.KeystoreUnavailableException) {
            stateMachine.onAuthenticationFailure()
            VaultUnlockOutcome.KeystoreUnavailable
        } catch (e: Exception) {
            stateMachine.onAuthenticationFailure()
            VaultUnlockOutcome.VaultCorrupt
        }
    }

    /**
     * Unlocks the session via the Backup PIN slot (Slot B), enforcing persistent
     * brute-force cooldown and recovering cleanly even if the session previously
     * entered `KEY_INVALIDATED` due to a broken Keystore slot (ADR-0015 §3).
     */
    fun unlockWithBackupPin(pin: String): BackupPinUnlockResult = synchronized(lifecycleLock) {
        if (stateMachine.isUnlocked()) {
            refreshSecurityStatus()
            return BackupPinUnlockResult.Success
        }
        if (pin.isEmpty() || pin.any { it < '0' || it > '9' }) {
            return BackupPinUnlockResult.InvalidPinFormat
        }
        return when (val outcome = backupPinSlot.unwrapWithRateLimit(pin)) {
            is BackupPinVaultSlot.PinAttemptOutcome.Unlocked -> {
                val key = outcome.vaultKey
                if (stateMachine.state.value == SecureSessionStateMachine.State.KEY_INVALIDATED) {
                    stateMachine.resetToLocked()
                }
                if (!stateMachine.beginAuthentication()) {
                    key.fill(0)
                    return if (stateMachine.isUnlocked()) {
                        BackupPinUnlockResult.Success
                    } else {
                        BackupPinUnlockResult.CorruptOrMissing
                    }
                }
                try {
                    val db = openDatabase(key)
                    vaultKey?.fill(0)
                    vaultKey = key
                    database = db
                    stateMachine.onAuthenticationSuccess()
                    refreshSecurityStatus()
                    BackupPinUnlockResult.Success
                } catch (e: kotlinx.coroutines.CancellationException) {
                    key.fill(0)
                    throw e
                } catch (_: Exception) {
                    key.fill(0)
                    stateMachine.onAuthenticationFailure()
                    BackupPinUnlockResult.CorruptOrMissing
                }
            }
            is BackupPinVaultSlot.PinAttemptOutcome.WrongPin ->
                BackupPinUnlockResult.WrongPin(outcome.failedAttempts, outcome.cooldownSeconds)
            is BackupPinVaultSlot.PinAttemptOutcome.CooldownActive ->
                BackupPinUnlockResult.Cooldown(outcome.remainingSeconds)
            BackupPinVaultSlot.PinAttemptOutcome.MissingSlot,
            BackupPinVaultSlot.PinAttemptOutcome.CorruptSlot,
            BackupPinVaultSlot.PinAttemptOutcome.Verified,
            -> BackupPinUnlockResult.CorruptOrMissing
        }
    }

    /**
     * Verifies [pin] against the persisted Backup PIN slot for sensitive-action
     * re-authentication without exposing or retaining plaintext PIN / KEK.
     */
    fun verifyBackupPinForReauth(pin: String): BackupPinVaultSlot.PinAttemptOutcome =
        backupPinSlot.verifyWithRateLimit(pin)

    /** Current remaining Backup PIN brute-force cooldown seconds (0 if none). */
    fun remainingBackupPinCooldownSeconds(): Int =
        backupPinSlot.remainingCooldownSeconds()

    /**
     * Adds or rotates the Backup PIN slot while the vault is currently UNLOCKED.
     * Does not require the previous Backup PIN (when the user unlocked via
     * system biometrics and passed fresh re-auth, they can reset a forgotten PIN).
     */
    fun setOrReplaceBackupPin(newPin: String): Boolean = synchronized(lifecycleLock) {
        if (!stateMachine.isUnlocked()) return false
        val currentKey = vaultKey ?: return false
        if (BackupPinCodec.validateNewPin(newPin) != null) return false
        return try {
            backupPinSlot.wrapAndReplace(currentKey, newPin)
            refreshSecurityStatus()
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Removes the Backup PIN slot while UNLOCKED, ONLY when a healthy Android
     * Keystore slot is active (refuses to orphan the vault with zero locks).
     */
    fun removeBackupPin(): Boolean = synchronized(lifecycleLock) {
        if (!stateMachine.isUnlocked()) return false
        if (!hasSystemKeySlot) return false
        backupPinSlot.delete()
        refreshSecurityStatus()
        return true
    }

    /**
     * Self-heals or enables the Android Keystore slot (Slot A) for a vault that
     * is currently UNLOCKED via Backup PIN. Caller must have just completed a
     * successful system authentication prompt (`BiometricPrompt`).
     */
    fun rebindSystemKeySlot(): VaultUnlockOutcome = synchronized(lifecycleLock) {
        if (!stateMachine.isUnlocked()) return VaultUnlockOutcome.AuthFailed
        val currentKey = vaultKey?.copyOf() ?: return VaultUnlockOutcome.AuthFailed
        val backupBlob = vaultKeyManager.readPersistedBlob()
        return try {
            vaultKeyManager.deleteWrapped()
            vaultKeyManager.wrapExisting(currentKey)
            systemSlotInvalidated = false
            refreshSecurityStatus()
            VaultUnlockOutcome.Success(null)
        } catch (e: VaultKeyManager.AuthRequiredException) {
            if (backupBlob != null) runCatching { vaultKeyManager.restorePersistedBlob(backupBlob) }
            VaultUnlockOutcome.AuthRequired
        } catch (e: VaultKeyManager.KeyInvalidatedException) {
            if (backupBlob != null) runCatching { vaultKeyManager.restorePersistedBlob(backupBlob) }
            VaultUnlockOutcome.KeyInvalidated
        } catch (e: Exception) {
            if (backupBlob != null) runCatching { vaultKeyManager.restorePersistedBlob(backupBlob) }
            VaultUnlockOutcome.KeystoreUnavailable
        } finally {
            currentKey.fill(0)
        }
    }

    /**
     * Deliberate destructive reset invoked ONLY after explicit user confirmation
     * on the locked-out recovery screen (when all configured key slots are lost).
     * Wipes both key slots and the local SQLCipher database files so the install
     * returns cleanly to first-run setup.
     */
    fun resetLockedOutVault() = synchronized(lifecycleLock) {
        lockTimerJob?.cancel()
        lockTimerJob = null
        database?.let { RescueAuthDatabase.closeDatabase(it) }
        database = null
        vaultKey?.fill(0)
        vaultKey = null
        systemSlotInvalidated = false
        vaultKeyManager.deleteWrapped()
        backupPinSlot.delete()
        val db = context.getDatabasePath(RescueAuthDatabase.DB_NAME)
        db.delete()
        File(db.path + "-wal").delete()
        File(db.path + "-shm").delete()
        stateMachine.resetToLocked()
        refreshSecurityStatus()
    }

    /**
     * Unlocks directly with an already-derived key (used by first-run setup
     * and tests that already hold the key). Caller must zero [key] after.
     */
    fun unlockWithFreshKey(key: ByteArray): Boolean = synchronized(lifecycleLock) {
        if (!stateMachine.beginAuthentication()) return false
        return try {
            val db = openDatabase(key)
            vaultKey?.fill(0)
            vaultKey = key.copyOf()
            database = db
            stateMachine.onAuthenticationSuccess()
            refreshSecurityStatus()
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            stateMachine.onAuthenticationFailure()
            false
        }
    }

    /** True when the encrypted database file exists (any size). */
    private fun databaseFileExists(): Boolean =
        context.getDatabasePath(RescueAuthDatabase.DB_NAME).exists()

    /** Room is lazy, so validate the connection before publishing UNLOCKED. */
    private fun openDatabase(key: ByteArray): RescueAuthDatabase {
        val db = databaseFactory(context, key)
        return try {
            db.openHelper.writableDatabase
            db
        } catch (error: Exception) {
            db.close()
            throw error
        }
    }

    /** Locks immediately: closes DB, zeroes the key. */
    fun lock() = synchronized(lifecycleLock) {
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
