package com.rescueauth.v2.security

import android.content.Context
import com.rescueauth.v2.vaultkey.BackupPinCodec
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.ceil

/**
 * App-private persistence and brute-force cooldown guard for the Backup PIN
 * `VaultKey` slot (`vault_key_pin_wrapped.bin`, ADR-0015).
 */
class BackupPinVaultSlot(
    private val directory: File,
    private val kdfParams: BackupPinCodec.KdfParams = BackupPinCodec.KdfParams(),
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) {
    constructor(
        context: Context,
        kdfParams: BackupPinCodec.KdfParams = BackupPinCodec.KdfParams(),
        clockMillis: () -> Long = { System.currentTimeMillis() },
    ) : this(context.filesDir, kdfParams, clockMillis)

    private val lock = Any()

    sealed interface PinAttemptOutcome {
        data class Unlocked(val vaultKey: ByteArray) : PinAttemptOutcome
        object Verified : PinAttemptOutcome
        data class WrongPin(val failedAttempts: Int, val cooldownSeconds: Int) : PinAttemptOutcome
        data class CooldownActive(val remainingSeconds: Int) : PinAttemptOutcome
        object MissingSlot : PinAttemptOutcome
        object CorruptSlot : PinAttemptOutcome
    }

    /** Presence is judged by file existence (never by decode success) to uphold the single-vault invariant. */
    fun hasPersisted(): Boolean = envelopeFile().exists()

    /**
     * Write-once persistence used during first-run Backup PIN vault creation.
     * Refuses to overwrite an existing envelope so a misjudged first-run state
     * can never destroy an existing key slot.
     */
    fun wrapAndPersistFirstRun(vaultKey: ByteArray, pin: String) = synchronized(lock) {
        val target = envelopeFile()
        if (target.exists()) {
            throw IllegalStateException("Backup PIN VaultKey slot already exists; refusing to overwrite")
        }
        val envelope = BackupPinCodec.wrap(vaultKey, pin, kdfParams)
        writeAtomically(target, envelope, replaceExisting = false)
        clearGuardLocked()
    }

    /**
     * Replaces or adds the Backup PIN slot for an already-unlocked vault
     * (`SessionManager.setOrReplaceBackupPin`).
     */
    fun wrapAndReplace(vaultKey: ByteArray, pin: String) = synchronized(lock) {
        val envelope = BackupPinCodec.wrap(vaultKey, pin, kdfParams)
        writeAtomically(envelopeFile(), envelope, replaceExisting = true)
        clearGuardLocked()
    }

    fun remainingCooldownSeconds(): Int = synchronized(lock) {
        remainingCooldownSecondsLocked(clockMillis())
    }

    fun unwrapWithRateLimit(pin: String): PinAttemptOutcome = synchronized(lock) {
        val target = envelopeFile()
        if (!target.exists()) return PinAttemptOutcome.MissingSlot

        val now = clockMillis()
        val activeCooldown = remainingCooldownSecondsLocked(now)
        if (activeCooldown > 0) {
            return PinAttemptOutcome.CooldownActive(activeCooldown)
        }

        val bytes = try {
            target.readBytes()
        } catch (_: Exception) {
            return PinAttemptOutcome.CorruptSlot
        }

        return try {
            val vaultKey = BackupPinCodec.unwrap(bytes, pin)
            clearGuardLocked()
            PinAttemptOutcome.Unlocked(vaultKey)
        } catch (_: BackupPinCodec.AuthenticationFailedException) {
            recordFailureLocked(now)
        } catch (_: BackupPinCodec.CorruptEnvelopeException) {
            PinAttemptOutcome.CorruptSlot
        } catch (_: Exception) {
            PinAttemptOutcome.CorruptSlot
        }
    }

    fun verifyWithRateLimit(pin: String): PinAttemptOutcome {
        return when (val outcome = unwrapWithRateLimit(pin)) {
            is PinAttemptOutcome.Unlocked -> {
                outcome.vaultKey.fill(0)
                PinAttemptOutcome.Verified
            }
            else -> outcome
        }
    }

    fun delete() = synchronized(lock) {
        envelopeFile().delete()
        File(envelopeFile().absolutePath + ".tmp").delete()
        clearGuardLocked()
    }

    private fun recordFailureLocked(now: Long): PinAttemptOutcome.WrongPin {
        val (prevAttempts, _) = readGuardLocked()
        val attempts = prevAttempts + 1
        val cooldownMillis = cooldownDurationMillisFor(attempts)
        val cooldownUntil = if (cooldownMillis > 0L) now + cooldownMillis else 0L
        writeGuardLocked(attempts, cooldownUntil)
        val cooldownSec = if (cooldownMillis > 0L) ceil(cooldownMillis / 1000.0).toInt() else 0
        return PinAttemptOutcome.WrongPin(failedAttempts = attempts, cooldownSeconds = cooldownSec)
    }

    private fun remainingCooldownSecondsLocked(now: Long): Int {
        val (attempts, rawUntil) = readGuardLocked()
        if (rawUntil <= now) return 0
        // Clamp against wall-clock jumps so a clock rollback cannot lock the user out indefinitely.
        val clampedRemaining = (rawUntil - now).coerceAtMost(MAX_COOLDOWN_MILLIS)
        if (rawUntil - now > MAX_COOLDOWN_MILLIS) {
            writeGuardLocked(attempts, now + clampedRemaining)
        }
        return ceil(clampedRemaining / 1000.0).toInt().coerceAtLeast(1)
    }

    private fun readGuardLocked(): Pair<Int, Long> {
        val file = guardFile()
        if (!file.exists()) return 0 to 0L
        return try {
            val lines = file.readLines()
            val attempts = lines.getOrNull(0)?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val until = lines.getOrNull(1)?.trim()?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            attempts to until
        } catch (_: Exception) {
            0 to 0L
        }
    }

    private fun writeGuardLocked(attempts: Int, cooldownUntil: Long) {
        val payload = "$attempts\n$cooldownUntil\n".encodeToByteArray()
        runCatching { writeAtomically(guardFile(), payload, replaceExisting = true) }
    }

    private fun clearGuardLocked() {
        guardFile().delete()
        File(guardFile().absolutePath + ".tmp").delete()
    }

    private fun writeAtomically(target: File, bytes: ByteArray, replaceExisting: Boolean) {
        directory.mkdirs()
        val temp = File(target.absolutePath + ".tmp")
        try {
            temp.writeBytes(bytes)
            if (replaceExisting) {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } else {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                )
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun envelopeFile(): File = File(directory, ENVELOPE_FILE)
    private fun guardFile(): File = File(directory, GUARD_FILE)

    companion object {
        const val ENVELOPE_FILE = "vault_key_pin_wrapped.bin"
        private const val GUARD_FILE = "backup_pin_guard.dat"
        private const val MAX_COOLDOWN_MILLIS = 300_000L

        internal fun cooldownDurationMillisFor(failedAttempts: Int): Long = when {
            failedAttempts >= 8 -> 300_000L // 5 minutes
            failedAttempts >= 6 -> 60_000L  // 1 minute
            failedAttempts >= 5 -> 30_000L  // 30 seconds
            else -> 0L
        }
    }
}
