package com.rescueauth.v2.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rescueauth.v2.database.RescueAuthDatabase
import com.rescueauth.v2.security.BackupPinVaultSlot
import com.rescueauth.v2.security.FakeVaultKeyCrypto
import com.rescueauth.v2.security.VaultKeyManager
import com.rescueauth.v2.vaultkey.BackupPinCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DualSlotFirstRunTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fastKdf = BackupPinCodec.KdfParams(
        memoryKiB = BackupPinCodec.MIN_MEMORY_KIB,
        iterations = 1,
        parallelism = 1,
    )

    private fun buildManager(
        crypto: FakeVaultKeyCrypto = FakeVaultKeyCrypto(),
        pinSlotWriteFails: Boolean = false,
        failDatabaseOpen: () -> Unit = {},
        openedKeys: MutableList<ByteArray> = mutableListOf(),
    ): Pair<SessionManager, File> {
        val slotDir = File(
            context.filesDir,
            if (pinSlotWriteFails) "dual-slot-pin-fail-test" else "dual-slot-first-run-test",
        )
        if (slotDir.exists()) slotDir.deleteRecursively()
        slotDir.mkdirs()
        // A directory at the atomic-write temp path makes writeAtomically fail
        // only when wrapAndPersistFirstRun actually writes the envelope.
        if (pinSlotWriteFails) {
            File(slotDir, BackupPinVaultSlot.ENVELOPE_FILE + ".tmp").mkdirs()
        }
        val manager = SessionManager(
            context = context,
            stateMachine = SecureSessionStateMachine(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            databaseFactory = { ctx, key ->
                failDatabaseOpen()
                openedKeys += key.copyOf()
                Room.inMemoryDatabaseBuilder(ctx, RescueAuthDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
            },
            vaultKeyManagerFactory = { VaultKeyManager(crypto) },
            backupPinSlotFactory = { BackupPinVaultSlot(slotDir, fastKdf) },
        )
        return manager to slotDir
    }

    private fun assertNoHalfInitializedState(
        manager: SessionManager,
        slotDir: File,
        crypto: FakeVaultKeyCrypto,
    ) {
        assertFalse("system slot must be removed on failure", crypto.hasPersisted())
        assertFalse("database must not be open on failure", manager.databaseOrNull() != null)
        assertFalse("state machine must not remain unlocked", manager.sessionState.isUnlocked())
        assertEquals(SecureSessionStateMachine.State.LOCKED, manager.sessionState.state.value)
        val status = manager.refreshSecurityStatus()
        assertFalse("securityStatus must not claim system slot on failure", status.hasSystemKeySlot)
        assertFalse("securityStatus must not claim PIN slot on failure", status.hasBackupPin)
        assertFalse(manager.hasSystemKeySlot)
        assertFalse(manager.hasBackupPin)
        assertTrue(manager.needsFirstRunSetup())
        assertEquals(emptyList<String>(), slotDir.list()?.toList())
    }

    @Test
    fun dual_slot_first_run_succeeds_and_both_slots_unlock_same_key() {
        val crypto = FakeVaultKeyCrypto()
        val openedKeys = mutableListOf<ByteArray>()
        val (manager, slotDir) = buildManager(crypto = crypto, openedKeys = openedKeys)

        assertTrue(manager.needsFirstRunSetup())
        val outcome = manager.createVaultWithSystemAndBackupPinAndOpen("246810")
        val freshKey = (outcome as? VaultUnlockOutcome.Success)?.freshFirstRunKey
        assertTrue("dual-slot first run must succeed: " + outcome, freshKey != null)
        freshKey!!.fill(0)

        val status = manager.refreshSecurityStatus()
        assertTrue(status.hasSystemKeySlot)
        assertTrue(status.hasBackupPin)
        assertTrue(manager.sessionState.isUnlocked())
        assertEquals(1, openedKeys.size)
        assertEquals(32, openedKeys[0].size)

        manager.lock()
        assertTrue(manager.unlock() is VaultUnlockOutcome.Success)
        assertArrayEquals("system slot must wrap original VaultKey", openedKeys[0], openedKeys[1])

        manager.lock()
        assertEquals(BackupPinUnlockResult.Success, manager.unlockWithBackupPin("246810"))
        assertArrayEquals("PIN slot must wrap original VaultKey", openedKeys[0], openedKeys[2])
        assertTrue(
            "successful dual-slot creation must persist the PIN envelope",
            File(slotDir, BackupPinVaultSlot.ENVELOPE_FILE).exists(),
        )
    }

    @Test
    fun system_slot_write_failure_rolls_back_and_leaves_clean_state() {
        val crypto = FakeVaultKeyCrypto().apply { requireAuthOnWrap = true }
        val (manager, slotDir) = buildManager(crypto = crypto)

        val outcome = manager.createVaultWithSystemAndBackupPinAndOpen("135790")

        assertFalse("must not fall back to PIN-only success", outcome is VaultUnlockOutcome.Success)
        assertNoHalfInitializedState(manager, slotDir, crypto)
    }

    @Test
    fun pin_slot_write_failure_rolls_back_and_leaves_clean_state() {
        val crypto = FakeVaultKeyCrypto()
        val (manager, slotDir) = buildManager(
            crypto = crypto,
            pinSlotWriteFails = true,
        )

        val outcome = manager.createVaultWithSystemAndBackupPinAndOpen("135790")

        assertFalse("must not fall back to system-only success", outcome is VaultUnlockOutcome.Success)
        assertNoHalfInitializedState(manager, slotDir, crypto)
    }

    @Test
    fun database_open_failure_rolls_back_both_slots_and_leaves_clean_state() {
        val crypto = FakeVaultKeyCrypto()
        val (manager, slotDir) = buildManager(
            crypto = crypto,
            failDatabaseOpen = { throw IllegalStateException("simulated database open failure") },
        )

        val outcome = manager.createVaultWithSystemAndBackupPinAndOpen("135790")

        assertFalse("database failure must fail the whole creation", outcome is VaultUnlockOutcome.Success)
        assertEquals(VaultUnlockOutcome.VaultCorrupt, outcome)
        assertNoHalfInitializedState(manager, slotDir, crypto)
    }

}
