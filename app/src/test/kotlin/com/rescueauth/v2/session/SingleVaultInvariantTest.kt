package com.rescueauth.v2.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rescueauth.v2.database.RescueAuthDatabase
import com.rescueauth.v2.security.FakeVaultKeyCrypto
import com.rescueauth.v2.security.VaultKeyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A machine owns exactly ONE vault.
 *
 * Reported defect: unlocking occasionally produced an EMPTY vault. The
 * dangerous variant of that symptom is a "second vault": any condition that
 * made the wrapped VaultKey look absent (blob unreadable, partial write,
 * teardown race) flipped the app into first-run, where
 * [VaultKeyManager.generateAndWrap] mints a NEW key and OVERWRITES the
 * wrapped blob. The existing SQLCipher database is then keyed with a vault
 * key nobody holds — permanently unrecoverable.
 *
 * These tests pin the invariant that closes that path: a vault counts as
 * existing when EITHER artifact survives, and creating over it is refused.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SingleVaultInvariantTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun manager(crypto: FakeVaultKeyCrypto): SessionManager = SessionManager(
        context = context,
        stateMachine = SecureSessionStateMachine(),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        databaseFactory = { ctx, _ ->
            Room.inMemoryDatabaseBuilder(ctx, RescueAuthDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        },
        vaultKeyManagerFactory = { VaultKeyManager(crypto) },
    )

    private fun dbFile(): File = context.getDatabasePath(RescueAuthDatabase.DB_NAME)

    /** Removes every persisted vault artifact so each test starts clean. */
    private fun deleteVaultArtifacts(crypto: FakeVaultKeyCrypto) {
        crypto.deletePersisted()
        dbFile().parentFile?.mkdirs()
        dbFile().delete()
        File(dbFile().path + "-wal").delete()
        File(dbFile().path + "-shm").delete()
    }

    @Test
    fun `fresh install has no vault and may create one`() = runTest {
        val crypto = FakeVaultKeyCrypto()
        deleteVaultArtifacts(crypto)
        val mgr = manager(crypto)

        assertTrue("a clean install must be first-run", mgr.needsFirstRunSetup())
    }

    @Test
    fun `existing wrapped key alone means the vault exists`() = runTest {
        val crypto = FakeVaultKeyCrypto()
        deleteVaultArtifacts(crypto)
        VaultKeyManager(crypto).generateAndWrap().fill(0)
        val mgr = manager(crypto)

        assertFalse("a wrapped key must suppress first-run", mgr.needsFirstRunSetup())
    }

    @Test
    fun `existing database alone means the vault exists`() = runTest {
        val crypto = FakeVaultKeyCrypto()
        deleteVaultArtifacts(crypto)
        // A database survives while its wrapped key blob does not (corrupt
        // blob, crash mid-write, or an OS-level file loss). The vault exists.
        dbFile().parentFile?.mkdirs()
        dbFile().writeBytes(ByteArray(64))
        val mgr = manager(crypto)

        assertFalse("an existing database must suppress first-run", mgr.needsFirstRunSetup())
    }

    @Test
    fun `create over a surviving database must refuse instead of minting a key`() = runTest {
        val crypto = FakeVaultKeyCrypto()
        deleteVaultArtifacts(crypto)
        VaultKeyManager(crypto).generateAndWrap().fill(0)
        val originalBlob = crypto.readWrapped()
        // The wrapped blob is lost while the database survives.
        crypto.deletePersisted()
        dbFile().parentFile?.mkdirs()
        dbFile().writeBytes(ByteArray(64))

        val mgr = manager(crypto)
        mgr.createVaultAndOpen()

        assertFalse("vault must not open as a brand-new one", mgr.sessionState.isUnlocked())
        assertNull("no database handle may be published", mgr.databaseOrNull())
        assertTrue("a second vault key must not be minted", crypto.readWrapped().isNullOrBlank())
        assertFalse("original blob documented by the fixture", originalBlob.isNullOrBlank())
    }

    @Test
    fun `unlock of an existing vault keeps the original key`() = runTest {
        val crypto = FakeVaultKeyCrypto()
        deleteVaultArtifacts(crypto)
        val original = VaultKeyManager(crypto).generateAndWrap()
        val originalCopy = original.copyOf()
        original.fill(0)

        val mgr = manager(crypto)
        val outcome = mgr.unlock()

        assertTrue("existing vault must unlock", mgr.sessionState.isUnlocked())
        assertTrue("unlock must report success", outcome is VaultUnlockOutcome.Success)
        // Re-unwrapping from the persisted blob proves it was not replaced.
        val reread = VaultKeyManager(crypto).unwrap()
        assertArrayEquals("persisted vault key must be unchanged", originalCopy, reread)
        reread.fill(0)
        originalCopy.fill(0)
    }
}
