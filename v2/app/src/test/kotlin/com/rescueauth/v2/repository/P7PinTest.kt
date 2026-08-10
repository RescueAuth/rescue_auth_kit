package com.rescueauth.v2.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rescueauth.v2.database.RescueAuthDatabase
import com.rescueauth.v2.session.SecureSessionStateMachine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P7 Account Pin/Unpin tests (35-47).
 *
 * Verifies that Pin persists through the serialized repository boundary,
 * reuses the `favorite` storage column as a compatibility field, preserves the
 * account's stableId / provider / TOTP / recovery state, and fails safely when
 * the session is locked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class P7PinTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: RescueAuthDatabase
    private lateinit var session: SecureSessionStateMachine
    private lateinit var vault: VaultRepository
    private lateinit var repo: AuthenticatorRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, RescueAuthDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        session = SecureSessionStateMachine()
        session.beginAuthentication()
        session.onAuthenticationSuccess()
        vault = VaultRepository(db, session)
        repo = AuthenticatorRepository(vault, db, session)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `pin account persists`() = runBlocking {
        val account = repo.findOrCreateAccount("GitHub", "alice")
        repo.setPinned(account.id, true)
        val accounts = repo.observeAccounts().first()
        assertTrue(accounts.first { it.id == account.id }.favorite)
        assertEquals(account.id, accounts.first { it.id == account.id }.id)
        assertEquals(account.stableId, accounts.first { it.id == account.id }.stableId)
    }

    @Test
    fun `unpin persists`() = runBlocking {
        val account = repo.findOrCreateAccount("GitHub", "alice")
        repo.setPinned(account.id, true)
        repo.setPinned(account.id, false)
        val accounts = repo.observeAccounts().first()
        assertEquals(false, accounts.first { it.id == account.id }.favorite)
    }

    @Test
    fun `close reopen DB preserves pin state`() = runBlocking<Unit> {
        val fileDb = Room.databaseBuilder(
            context,
            RescueAuthDatabase::class.java,
            "pin-persistence-test.db",
        ).allowMainThreadQueries().build()
        val fileSession = SecureSessionStateMachine()
        fileSession.beginAuthentication()
        fileSession.onAuthenticationSuccess()
        val fileRepo = AuthenticatorRepository(VaultRepository(fileDb, fileSession), fileDb, fileSession)

        val account = fileRepo.findOrCreateAccount("GitHub", "alice")
        fileRepo.setPinned(account.id, true)
        fileDb.close()

        // Reopen the same file-backed DB (fresh Session + repository).
        val db2 = Room.databaseBuilder(
            context,
            RescueAuthDatabase::class.java,
            "pin-persistence-test.db",
        ).allowMainThreadQueries().build()
        val session2 = SecureSessionStateMachine()
        session2.beginAuthentication()
        session2.onAuthenticationSuccess()
        val repo2 = AuthenticatorRepository(VaultRepository(db2, session2), db2, session2)
        try {
            val accounts = repo2.observeAccounts().first()
            assertEquals(1, accounts.size)
            assertEquals(true, accounts.first { it.id == account.id }.favorite)
            assertEquals(account.stableId, accounts.first { it.id == account.id }.stableId)
        } finally {
            db2.close()
            context.deleteDatabase("pin-persistence-test.db")
        }
    }

    @Test
    fun `pin preserves account stableId provider totp and recovery`() = runBlocking {
        val account = repo.findOrCreateAccount("GitHub", "alice")
        val stableIdBefore = account.stableId
        repo.addTotpCredential(account.id, "JBSWY3DPEHPK3PXP", "SHA1", 6, 30)

        val recovery = com.rescueauth.v2.repository.RecoveryCodeRepository(vault, db, session)
        recovery.createSet(account.id, "backup", listOf("AAA-111", "BBB-222"))

        repo.setPinned(account.id, true)

        val accounts = repo.observeAccounts().first()
        val pinned = accounts.first { it.id == account.id }
        assertEquals(stableIdBefore, pinned.stableId)
        assertEquals("GitHub", pinned.serviceName)

        val totps = repo.observeTotpCredentials().first()
        assertEquals(1, totps.size)
        assertEquals(account.id, totps[0].accountId)

        val sets = recovery.observeSetsByAccount(account.id).first()
        assertEquals(1, sets.size)
        assertEquals("backup", sets[0].title)
    }

    @Test
    fun `rapid repeated toggle produces deterministic final state`() = runBlocking {
        val account = repo.findOrCreateAccount("GitHub", "alice")
        // Simulate rapid toggles via the serialized boundary.
        repeat(10) { repo.setPinned(account.id, it % 2 == 0) }
        val final = repo.observeAccounts().first().first { it.id == account.id }
        assertEquals(false, final.favorite) // last call (it=9) set false
    }

    @Test
    fun `session locked mutation fails safely`() = runBlocking<Unit> {
        val account = repo.findOrCreateAccount("GitHub", "alice")
        session.lock()
        assertThrows(VaultRepository.SessionLockedException::class.java) {
            runBlocking { repo.setPinned(account.id, true) }
        }
    }

    @Test
    fun `updatedAt changes on pin`() = runBlocking {
        val account = repo.findOrCreateAccount("GitHub", "alice")
        val before = account.updatedAt
        repo.setPinned(account.id, true)
        val after = repo.observeAccounts().first().first { it.id == account.id }.updatedAt
        assertTrue(after != before)
    }
}
