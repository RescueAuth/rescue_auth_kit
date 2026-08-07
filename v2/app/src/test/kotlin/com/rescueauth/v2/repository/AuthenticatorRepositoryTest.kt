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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Production Authenticator repository tests: real TOTP CRUD on an in-memory
 * Room DB (SQLCipher path is covered by the instrumented suite), delete +
 * Undo restore with stableId preservation, and close/reopen persistence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthenticatorRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: RescueAuthDatabase
    private lateinit var session: SecureSessionStateMachine
    private lateinit var repo: AuthenticatorRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, RescueAuthDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        session = SecureSessionStateMachine()
        session.beginAuthentication()
        session.onAuthenticationSuccess()
        repo = AuthenticatorRepository(VaultRepository(db, session), db, session)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `findOrCreateAccount creates and reuses account`() = runBlocking {
        val created = repo.findOrCreateAccount("GitHub", "alice@example.com")
        assertNotNull(created.id)
        assertNotNull(created.stableId)
        assertEquals("GitHub", created.serviceName)
        assertEquals("alice@example.com", created.accountName)

        val again = repo.findOrCreateAccount("GitHub", "alice@example.com")
        assertEquals(created.id, again.id)
        assertEquals(created.stableId, again.stableId)
    }

    @Test
    fun `addTotpCredential persists and is observable`() = runBlocking {
        val account = repo.findOrCreateAccount("GitHub", "alice@example.com")
        val cred = repo.addTotpCredential(
            accountId = account.id,
            secretBase32 = "JBSWY3DPEHPK3PXP",
            algorithm = "SHA1",
            digits = 6,
            periodSeconds = 30,
        )
        assertNotNull(cred.id)
        assertEquals(cred.stableId, cred.id) // first production insert: stableId = id
        assertEquals("JBSWY3DPEHPK3PXP", cred.secretBase32)

        val cards = repo.observeTotpCredentials().first()
        assertEquals(1, cards.size)
        assertEquals(cred.id, cards[0].id)
    }

    @Test
    fun `addTotpCredential validates input`() = runBlocking<Unit> {
        val account = repo.findOrCreateAccount("GitHub", "alice@example.com")
        assertThrows(AuthenticatorRepository.ValidationException::class.java) {
            runBlocking { repo.addTotpCredential(account.id, "", "SHA1", 6, 30) }
        }
        assertThrows(AuthenticatorRepository.ValidationException::class.java) {
            runBlocking { repo.addTotpCredential(account.id, "JBSWY3DPEHPK3PXP", "", 6, 30) }
        }
        assertThrows(AuthenticatorRepository.ValidationException::class.java) {
            runBlocking { repo.addTotpCredential(account.id, "JBSWY3DPEHPK3PXP", "SHA1", 5, 30) }
        }
        assertThrows(AuthenticatorRepository.ValidationException::class.java) {
            runBlocking { repo.addTotpCredential(account.id, "JBSWY3DPEHPK3PXP", "SHA1", 6, 0) }
        }
    }

    @Test
    fun `delete removes row and restore keeps stableId`() = runBlocking {
        val account = repo.findOrCreateAccount("GitHub", "alice@example.com")
        val cred = repo.addTotpCredential(
            accountId = account.id,
            secretBase32 = "JBSWY3DPEHPK3PXP",
            algorithm = "SHA256",
            digits = 8,
            periodSeconds = 60,
        )
        val stableIdBefore = cred.stableId

        val deleted = repo.deleteTotpCredential(cred.id)
        assertNotNull(deleted)
        assertEquals(0, repo.observeTotpCredentials().first().size)

        // Undo restore.
        repo.restoreTotpCredential(deleted!!)
        val restored = repo.observeTotpCredentials().first()
        assertEquals(1, restored.size)
        assertEquals(cred.id, restored[0].id)
        assertEquals(stableIdBefore, restored[0].stableId)
        assertEquals("JBSWY3DPEHPK3PXP", restored[0].secretBase32)
        assertEquals("SHA256", restored[0].algorithm)
        assertEquals(8, restored[0].digits)
        assertEquals(60, restored[0].periodSeconds)
    }

    @Test
    fun `delete of missing id returns null`() = runBlocking {
        val deleted = repo.deleteTotpCredential("does-not-exist")
        assertNull(deleted)
    }

    @Test
    fun `delete account cascades credentials`() = runBlocking {
        val account = repo.findOrCreateAccount("GitHub", "alice@example.com")
        repo.addTotpCredential(account.id, "JBSWY3DPEHPK3PXP", "SHA1", 6, 30)
        repo.deleteAccount(account.id)
        assertEquals(0, repo.observeTotpCredentials().first().size)
    }

    @Test
    fun `mutations when locked throw SessionLockedException`() = runBlocking<Unit> {
        session.lock()
        assertThrows(VaultRepository.SessionLockedException::class.java) {
            runBlocking { repo.findOrCreateAccount("GitHub", "a@b.com") }
        }
    }

    @Test
    fun `close and reopen database preserves data`() = runBlocking<Unit> {
        val fileDb = Room.databaseBuilder(
            context,
            RescueAuthDatabase::class.java,
            "persistence-test.db",
        ).allowMainThreadQueries().build()
        val fileSession = SecureSessionStateMachine()
        fileSession.beginAuthentication()
        fileSession.onAuthenticationSuccess()
        val fileRepo = AuthenticatorRepository(VaultRepository(fileDb, fileSession), fileDb, fileSession)

        val account = fileRepo.findOrCreateAccount("GitHub", "alice@example.com")
        val cred = fileRepo.addTotpCredential(account.id, "JBSWY3DPEHPK3PXP", "SHA1", 6, 30)
        val stableId = cred.stableId
        fileDb.close()

        // Reopen the same file-backed DB (fresh Session + repository).
        val db2 = Room.databaseBuilder(
            context,
            RescueAuthDatabase::class.java,
            "persistence-test.db",
        ).allowMainThreadQueries().build()
        val session2 = SecureSessionStateMachine()
        session2.beginAuthentication()
        session2.onAuthenticationSuccess()
        val repo2 = AuthenticatorRepository(VaultRepository(db2, session2), db2, session2)

        val cards = repo2.observeTotpCredentials().first()
        assertEquals(1, cards.size)
        assertEquals(stableId, cards[0].stableId)
        assertEquals(cred.id, cards[0].id)
        assertEquals("JBSWY3DPEHPK3PXP", cards[0].secretBase32)
        db2.close()
        context.deleteDatabase("persistence-test.db")
    }
}
