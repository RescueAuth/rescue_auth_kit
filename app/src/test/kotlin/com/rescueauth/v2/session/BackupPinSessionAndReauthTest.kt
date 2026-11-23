package com.rescueauth.v2.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rescueauth.v2.database.RescueAuthDatabase
import com.rescueauth.v2.security.BackupPinFallbackPrompt
import com.rescueauth.v2.security.BackupPinVaultSlot
import com.rescueauth.v2.security.FakeSensitiveActionPrompt
import com.rescueauth.v2.security.FakeVaultKeyCrypto
import com.rescueauth.v2.security.SensitiveAction
import com.rescueauth.v2.security.SensitiveActionGate
import com.rescueauth.v2.security.SensitiveActionRequest
import com.rescueauth.v2.security.SensitiveActionResult
import com.rescueauth.v2.security.SensitiveActionTarget
import com.rescueauth.v2.security.VaultKeyManager
import com.rescueauth.v2.vaultkey.BackupPinCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupPinSessionAndReauthTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fastKdf = BackupPinCodec.KdfParams(
        memoryKiB = BackupPinCodec.MIN_MEMORY_KIB,
        iterations = 1,
        parallelism = 1,
    )

    private fun buildManager(
        crypto: FakeVaultKeyCrypto = FakeVaultKeyCrypto(),
        clock: () -> Long = { System.currentTimeMillis() },
        openedKeys: MutableList<ByteArray> = mutableListOf(),
    ): SessionManager {
        return SessionManager(
            context = context,
            stateMachine = SecureSessionStateMachine(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            databaseFactory = { ctx, key ->
                openedKeys += key.copyOf()
                Room.inMemoryDatabaseBuilder(ctx, RescueAuthDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
            },
            vaultKeyManagerFactory = { VaultKeyManager(crypto) },
            backupPinSlotFactory = { BackupPinVaultSlot(it.filesDir, fastKdf, clock) },
        )
    }

    @Test
    fun `lockless device creates vault with Backup PIN, upholds single-vault invariant, and unlocks with PIN`() = runTest {
        val crypto = FakeVaultKeyCrypto()
        val openedKeys = mutableListOf<ByteArray>()
        val mgr = buildManager(crypto = crypto, openedKeys = openedKeys)

        assertTrue(mgr.needsFirstRunSetup())
        val createOutcome = mgr.createVaultWithBackupPinAndOpen("246810")
        assertTrue(createOutcome is VaultUnlockOutcome.Success)
        (createOutcome as VaultUnlockOutcome.Success).freshFirstRunKey?.fill(0)

        assertTrue(mgr.sessionState.isUnlocked())
        assertTrue(mgr.hasBackupPin)
        assertFalse(mgr.hasSystemKeySlot)
        assertFalse("Backup PIN slot alone must suppress first-run", mgr.needsFirstRunSetup())

        // Cannot remove Backup PIN when no system Keystore slot exists.
        assertFalse("must refuse removing the only unlock slot", mgr.removeBackupPin())

        mgr.lock()
        assertFalse(mgr.sessionState.isUnlocked())

        // Attempting a second first-run create must be refused.
        assertEquals(VaultUnlockOutcome.AuthFailed, mgr.createVaultWithBackupPinAndOpen("999999"))
        assertFalse(mgr.sessionState.isUnlocked())

        // Wrong PIN fails without unlocking; correct PIN reopens with the exact same VaultKey.
        assertTrue(mgr.unlockWithBackupPin("111111") is BackupPinUnlockResult.WrongPin)
        assertFalse(mgr.sessionState.isUnlocked())

        assertEquals(BackupPinUnlockResult.Success, mgr.unlockWithBackupPin("246810"))
        assertTrue(mgr.sessionState.isUnlocked())
        assertEquals(2, openedKeys.size)
        assertArrayEquals("PIN unlock must unwrap the exact original VaultKey", openedKeys[0], openedKeys[1])
    }

    @Test
    fun `existing Keystore vault adds Backup PIN, survives KeyInvalidated, and self-heals Keystore slot`() = runTest {
        val crypto = FakeVaultKeyCrypto()
        val openedKeys = mutableListOf<ByteArray>()
        val mgr = buildManager(crypto = crypto, openedKeys = openedKeys)

        val created = mgr.createVaultAndOpen()
        assertTrue(created is VaultUnlockOutcome.Success)
        (created as VaultUnlockOutcome.Success).freshFirstRunKey?.fill(0)
        assertTrue(mgr.hasSystemKeySlot)
        assertFalse(mgr.hasBackupPin)

        // Add Backup PIN while unlocked.
        assertTrue(mgr.setOrReplaceBackupPin("135790"))
        assertTrue(mgr.hasBackupPin)

        mgr.lock()

        // Simulate user removing screen lock -> Android Keystore invalidates Slot A.
        crypto.invalidateOnUnwrap = true
        val brokenOutcome = mgr.unlock()
        assertEquals(VaultUnlockOutcome.KeyInvalidated, brokenOutcome)
        assertTrue(mgr.isSystemKeyInvalidated)
        assertFalse(mgr.sessionState.isUnlocked())

        // Recover seamlessly via Backup PIN (Slot B) even from KEY_INVALIDATED state!
        assertEquals(BackupPinUnlockResult.Success, mgr.unlockWithBackupPin("135790"))
        assertTrue(mgr.sessionState.isUnlocked())
        assertArrayEquals("Slot B must unwrap the identical VaultKey as Slot A", openedKeys[0], openedKeys[1])

        // Cannot remove Backup PIN while Slot A is still invalidated.
        assertFalse(mgr.removeBackupPin())

        // User re-enables system screen lock -> rebindSystemKeySlot heals Slot A!
        crypto.invalidateOnUnwrap = false
        val rebind = mgr.rebindSystemKeySlot()
        assertTrue(rebind is VaultUnlockOutcome.Success)
        assertTrue(mgr.hasSystemKeySlot)
        assertFalse(mgr.isSystemKeyInvalidated)

        // Verify healed Slot A unlocks the exact same VaultKey again.
        mgr.lock()
        assertTrue(mgr.unlock() is VaultUnlockOutcome.Success)
        assertArrayEquals(openedKeys[0], openedKeys[2])

        // With healthy Slot A, user may remove Backup PIN if desired.
        assertTrue(mgr.removeBackupPin())
        assertFalse(mgr.hasBackupPin)
    }

    @Test
    fun `brute-force guard enforces progressive cooldown and clamps clock skew`() = runTest {
        var nowMs = 1_000_000L
        val mgr = buildManager(clock = { nowMs })
        mgr.createVaultWithBackupPinAndOpen("888888").let {
            (it as? VaultUnlockOutcome.Success)?.freshFirstRunKey?.fill(0)
        }
        mgr.lock()

        // Attempts 1..4: immediate retry (0s cooldown).
        for (i in 1..4) {
            val res = mgr.unlockWithBackupPin("000000") as BackupPinUnlockResult.WrongPin
            assertEquals(i, res.failedAttempts)
            assertEquals(0, res.cooldownSeconds)
        }
        // Attempt 5: 30s cooldown.
        val fifth = mgr.unlockWithBackupPin("000000") as BackupPinUnlockResult.WrongPin
        assertEquals(5, fifth.failedAttempts)
        assertEquals(30, fifth.cooldownSeconds)

        // Immediate attempt during cooldown is rejected without running KDF, even with right PIN.
        val blocked = mgr.unlockWithBackupPin("888888")
        assertTrue(blocked is BackupPinUnlockResult.Cooldown)
        assertEquals(30, (blocked as BackupPinUnlockResult.Cooldown).remainingSeconds)

        // Simulate extreme backward clock skew (nowMs jumped backward by 10 days) — must clamp <= 300s!
        nowMs = 100_000L
        assertTrue(mgr.remainingBackupPinCooldownSeconds() in 1..300)

        // Advance past clamped window -> valid PIN unlocks and resets failure counter.
        nowMs += 301_000L
        assertEquals(BackupPinUnlockResult.Success, mgr.unlockWithBackupPin("888888"))
        assertEquals(0, mgr.remainingBackupPinCooldownSeconds())
    }

    @Test
    fun `BackupPinFallbackPrompt preserves one-shot target-bound SensitiveActionGate semantics`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val mgr = buildManager()
        mgr.createVaultWithBackupPinAndOpen("654321").let {
            (it as? VaultUnlockOutcome.Success)?.freshFirstRunKey?.fill(0)
        }

        val fakeSystemPrompt = FakeSensitiveActionPrompt()
        var systemUsable = false
        val fallbackPrompt = BackupPinFallbackPrompt(
            systemPrompt = fakeSystemPrompt,
            canUseSystemPrompt = { systemUsable },
            hasBackupPin = { mgr.hasBackupPin },
            initialCooldownSeconds = { mgr.remainingBackupPinCooldownSeconds() },
            verifyPin = { pin -> mgr.verifyBackupPinForReauth(pin) },
            scope = CoroutineScope(SupervisorJob() + testDispatcher),
            verifyDispatcher = testDispatcher,
        )
        val gate = SensitiveActionGate(
            prompt = fallbackPrompt,
            session = mgr.sessionState,
            titleProvider = { "Verify" },
            subtitleProvider = { "Enter Backup PIN" },
        )

        val reqA = SensitiveActionRequest(
            action = SensitiveAction.REVEAL_SSH_PRIVATE_KEY,
            target = SensitiveActionTarget.DeveloperField("entry-1", "privateKey"),
        )
        val reqB = SensitiveActionRequest(
            action = SensitiveAction.COPY_SSH_PRIVATE_KEY,
            target = SensitiveActionTarget.DeveloperField("entry-1", "privateKey"),
        )

        var delivered: SensitiveActionResult? = null
        assertTrue(gate.authorize(reqA) { delivered = it })
        assertNotNull("Backup PIN dialog must open on lockless device", fallbackPrompt.dialogState.value)

        // Wrong PIN keeps dialog open with wrongPin = true and does NOT authorize.
        fallbackPrompt.submitPin("111111")
        assertNull(delivered)
        assertEquals(true, fallbackPrompt.dialogState.value?.wrongPin)

        // Correct PIN closes dialog and delivers one-shot authorization for reqA ONLY.
        fallbackPrompt.submitPin("654321")
        assertNull(fallbackPrompt.dialogState.value)
        assertEquals(SensitiveActionResult.Success(reqA), delivered)

        // Mismatched target/action (reqB) is rejected; reqA executes once then is consumed.
        var executed = 0
        assertFalse(gate.executePending(reqB) { executed++ })
        assertTrue(gate.executePending(reqA) { executed++ })
        assertFalse("one-shot authorization must be consumed", gate.executePending(reqA) { executed++ })
        assertEquals(1, executed)
    }

    @Test
    fun `resetLockedOutVault wipes slots and database so first-run setup is re-enabled`() = runTest {
        val crypto = FakeVaultKeyCrypto()
        val mgr = buildManager(crypto = crypto)
        mgr.createVaultAndOpen().let { (it as? VaultUnlockOutcome.Success)?.freshFirstRunKey?.fill(0) }
        mgr.setOrReplaceBackupPin("123456")
        val dbFile = context.getDatabasePath(RescueAuthDatabase.DB_NAME)
        dbFile.parentFile?.mkdirs()
        dbFile.writeBytes(ByteArray(32))
        mgr.lock()

        assertFalse(mgr.needsFirstRunSetup())
        mgr.resetLockedOutVault()

        assertTrue(mgr.needsFirstRunSetup())
        assertFalse(mgr.hasSystemKeySlot)
        assertFalse(mgr.hasBackupPin)
        assertFalse(dbFile.exists())
    }
}
