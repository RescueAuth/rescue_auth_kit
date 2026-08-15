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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Repository batch TOTP import tests (Phase 4 P2 migration path).
 *
 * Real in-memory Room DB: batch import of 3 credentials, parent Provider /
 * Account reuse, existing-TOTP duplicate skipping, mixed duplicate + new,
 * unsupported entries never written, and close/reopen persistence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TotpBatchImportRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: RescueAuthDatabase
    private lateinit var session: SecureSessionStateMachine
    private lateinit var repo: AuthenticatorRepository

    private fun item(
        issuer: String,
        account: String,
        secret: String = "JBSWY3DPEHPK3PXP",
        algorithm: String = "SHA1",
        digits: Int = 6,
        period: Int = 30,
    ) = TotpImportItem(issuer, account, secret, algorithm, digits, period)

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
    fun `23 migration 3 credentials results in 3 in real db`() = runBlocking {
        val result = repo.importTotpBatch(
            listOf(
                item("GitHub", "a@x.com", secret = "AAAAAAAA"),
                item("GitLab", "b@x.com", secret = "BBBBBBBB"),
                item("Acme", "c@x.com", secret = "CCCCCCCC"),
            )
        )
        assertEquals(3, result.importedCount)
        assertEquals(3, repo.observeTotpCredentials().first().size)
    }

    @Test
    fun `24 parent provider grouping reuse`() = runBlocking {
        repo.importTotpBatch(
            listOf(
                item("GitHub", "a@x.com", secret = "AAAAAAAA"),
                item("GitHub", "b@x.com", secret = "BBBBBBBB"),
            )
        )
        val accounts = repo.observeAccounts().first()
        // Same issuer (provider) but different accounts → 2 accounts sharing
        // the same serviceName; no duplicate / per-credential accounts.
        assertEquals(2, accounts.size)
        assertTrue(accounts.all { it.serviceName == "GitHub" })
    }

    @Test
    fun `25 parent account reuse for same issuer and account`() = runBlocking {
        repo.importTotpBatch(
            listOf(
                item("GitHub", "a@x.com", secret = "AAAAAAAA"),
                item("GitHub", "a@x.com", secret = "BBBBBBBB"),
            )
        )
        val accounts = repo.observeAccounts().first()
        assertEquals(1, accounts.size)
        val creds = repo.observeTotpCredentials().first()
        assertEquals(2, creds.size)
        assertTrue(creds.all { it.accountId == accounts[0].id })
    }

    @Test
    fun `26 existing totp duplicate skipped`() = runBlocking {
        // pre-existing credential via normal add path
        val account = repo.findOrCreateAccount("GitHub", "a@x.com")
        repo.addTotpCredential(account.id, "JBSWY3DPEHPK3PXP", "SHA1", 6, 30)

        val result = repo.importTotpBatch(listOf(item("GitHub", "a@x.com")))
        assertEquals(0, result.importedCount)
        assertEquals(1, result.duplicateCount)
        assertEquals(1, repo.observeTotpCredentials().first().size)
    }

    @Test
    fun `27 mixed duplicate and new entries`() = runBlocking {
        repo.importTotpBatch(listOf(item("GitHub", "a@x.com", secret = "AAAAAAAA")))
        val result = repo.importTotpBatch(
            listOf(
                item("GitHub", "a@x.com", secret = "AAAAAAAA"), // duplicate
                item("GitHub", "b@x.com", secret = "BBBBBBBB"), // new
            )
        )
        assertEquals(1, result.importedCount)
        assertEquals(1, result.duplicateCount)
        assertEquals(2, repo.observeTotpCredentials().first().size)
    }

    @Test
    fun `28 unsupported entry never written`() = runBlocking {
        val result = repo.importTotpBatch(
            listOf(
                item("GitHub", "a@x.com", algorithm = "MD5"),
                item("GitHub", "b@x.com", digits = 5),
                item("GitHub", "c@x.com", secret = "NOT!!!BASE32"),
                item("GitHub", "d@x.com", secret = "AAAAAAAA"),
            )
        )
        assertEquals(1, result.importedCount)
        assertEquals(2, result.unsupportedCount)
        assertEquals(1, result.invalidCount)
        assertEquals(1, repo.observeTotpCredentials().first().size)
    }

    @Test
    fun `29 restart reopen imported credentials remain`() = runBlocking {
        val dbName = "batch-reopen-${System.nanoTime()}.db"
        val fileDb = Room.databaseBuilder(context, RescueAuthDatabase::class.java, dbName)
            .allowMainThreadQueries()
            .build()
        try {
            session.beginAuthentication()
            session.onAuthenticationSuccess()
            val repoA = AuthenticatorRepository(VaultRepository(fileDb, session), fileDb, session)
            repoA.importTotpBatch(
                listOf(
                    item("GitHub", "a@x.com", secret = "AAAAAAAA"),
                    item("GitLab", "b@x.com", secret = "BBBBBBBB"),
                )
            )
            assertEquals(2, repoA.observeTotpCredentials().first().size)
            val stableIds = repoA.observeTotpCredentials().first().map { it.stableId }.toSet()
            assertEquals(2, stableIds.size)

            // Simulate restart: close and reopen the same file-backed DB.
            fileDb.close()
            val reopened = Room.databaseBuilder(context, RescueAuthDatabase::class.java, dbName)
                .allowMainThreadQueries()
                .build()
            try {
                val repoB = AuthenticatorRepository(VaultRepository(reopened, session), reopened, session)
                val creds = repoB.observeTotpCredentials().first()
                assertEquals(2, creds.size)
                assertEquals(stableIds, creds.map { it.stableId }.toSet())
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `stableId is generated for each imported credential`() = runBlocking {
        val result = repo.importTotpBatch(
            listOf(
                item("GitHub", "a@x.com", secret = "AAAAAAAA"),
                item("GitHub", "b@x.com", secret = "BBBBBBBB"),
            )
        )
        val ids = result.imported.mapNotNull { it.importedCredentialId }
        assertEquals(2, ids.size)
        assertTrue(ids.toSet().size == 2)
    }
}
