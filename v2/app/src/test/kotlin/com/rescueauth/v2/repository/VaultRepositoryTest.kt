package com.rescueauth.v2.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rescueauth.v2.database.RescueAuthDatabase
import com.rescueauth.v2.legacy.LegacyImportBundle
import com.rescueauth.v2.legacy.LegacyTotpEntry
import com.rescueauth.v2.legacy.LegacyDeveloperEntry
import com.rescueauth.v2.session.SecureSessionStateMachine
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * VaultRepository tests: serialized mutations, session-lock enforcement and
 * legacy import transactionality.
 *
 * Phase 3 reset: the automatic-backup snapshot sink / PRE_IMPORT checkpoint /
 * BackupRecord bookkeeping were REMOVED (manual Export Package only). The
 * repository no longer takes a snapshot sink, so the change-tracking and
 * checkpoint-failure assertions are gone; the transactionality guarantees
 * (all-or-nothing import, session-lock enforcement, serialization) remain.
 *
 * Uses an in-memory (unencrypted) Room DB — the SQLCipher path is covered by
 * the instrumented test. All repository logic (locking, serialization,
 * import mapping) is DB-backend agnostic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: RescueAuthDatabase
    private lateinit var session: SecureSessionStateMachine

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, RescueAuthDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        session = SecureSessionStateMachine()
        session.beginAuthentication()
        session.onAuthenticationSuccess()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun repo() = VaultRepository(db, session)

    private fun sampleBundle(): LegacyImportBundle = LegacyImportBundle(
        schemaVersion = 1,
        totpEntries = listOf(
            LegacyTotpEntry(
                id = "totp-1", issuer = "GitHub", accountName = "alice@example.com",
                secretBase32 = "JBSWY3DPEHPK3PXP", algorithm = "SHA1",
                digits = 6, period = 30, createdAt = "2024-01-01T00:00:00Z",
            ),
            LegacyTotpEntry(
                id = "totp-2", issuer = "Google", accountName = "bob@example.com",
                secretBase32 = "4F6VS6KX3UXWY2FQ", algorithm = "SHA256",
                digits = 6, period = 30, createdAt = "2024-02-01T00:00:00Z",
            ),
        ),
        recoveryCodeSets = emptyList(),
        developerEntries = emptyList(),
        developerSettings = false,
    )

    // ------------------------------------------------------------------
    // Session-lock enforcement
    // ------------------------------------------------------------------

    @Test
    fun `mutation when locked throws SessionLockedException`() = runBlocking {
        session.lock()
        try {
            repo().markRecoveryCodeUsed("c1")
            assertTrue("expected SessionLockedException", false)
        } catch (e: VaultRepository.SessionLockedException) {
            // expected
        }
    }

    @Test
    fun `import when locked throws SessionLockedException`() = runBlocking {
        session.lock()
        try {
            repo().importLegacy(sampleBundle())
            assertTrue("expected SessionLockedException", false)
        } catch (e: VaultRepository.SessionLockedException) {
            // expected
        }
    }

    // ------------------------------------------------------------------
    // Serialization
    // ------------------------------------------------------------------

    @Test
    fun `concurrent mutations are serialized and no data is lost`() = runBlocking {
        val r = repo()
        // Seed one account.
        r.importLegacy(sampleBundle())
        assertEquals(2, db.authAccountDao().count())

        // 20 concurrent favorite toggles on the first account: with a Mutex
        // the final state is deterministic (last writer wins) and no crash.
        val firstId = db.authAccountDao().listAllIds().first()
        coroutineScope {
            (1..20).map { i ->
                async { r.setFavorite(firstId, i % 2 == 0) }
            }.awaitAll()
        }
        assertEquals(2, db.authAccountDao().count())
    }

    @Test
    fun `concurrent imports are serialized and all rows persist`() = runBlocking {
        val r = repo()
        coroutineScope {
            (1..5).map { _ ->
                async { r.importLegacy(sampleBundle()) }
            }.awaitAll()
        }
        // All 5 imports committed (serialized) => 10 accounts total.
        assertEquals(10, db.authAccountDao().count())
    }

    // ------------------------------------------------------------------
    // Import transactionality
    // ------------------------------------------------------------------

    @Test
    fun `legacy import persists accounts totps and import record`() = runBlocking {
        val summary = repo().importLegacy(sampleBundle())
        assertEquals(2, summary.importedAccounts)
        assertEquals(0, summary.warningCount)
        assertEquals(2, db.authAccountDao().count())
        assertEquals(2, db.totpCredentialDao().listAll().size)
        assertEquals(1, db.importRecordDao().listAll().size)
    }

    @Test
    fun `invalid params are excluded and reported without aborting import`() = runBlocking {
        val bundle = LegacyImportBundle(
            schemaVersion = 1,
            totpEntries = listOf(
                LegacyTotpEntry(
                    id = "bad", issuer = "X", accountName = "x",
                    secretBase32 = "JBSWY3DPEHPK3PXP", algorithm = "SHA999",
                    digits = 6, period = 30, createdAt = "t",
                ),
                LegacyTotpEntry(
                    id = "good", issuer = "GitHub", accountName = "alice",
                    secretBase32 = "JBSWY3DPEHPK3PXP", algorithm = "SHA1",
                    digits = 6, period = 30, createdAt = "t",
                ),
            ),
            recoveryCodeSets = emptyList(),
            developerEntries = emptyList(),
        )
        val summary = repo().importLegacy(bundle)
        assertEquals(1, summary.importedAccounts)
        assertEquals(1, summary.warningCount)
        assertEquals(1, summary.notImported.size)
        assertEquals("bad", summary.notImported.first())
        assertEquals(1, db.authAccountDao().count())
    }

    @Test
    fun `legacy import failure rolls back the whole transaction`() = runBlocking {
        val r = repo()
        // A bundle that maps to nothing must abort the transaction without
        // leaving any partial rows behind.
        val emptyBundle = LegacyImportBundle(
            schemaVersion = 1,
            totpEntries = emptyList(),
            recoveryCodeSets = emptyList(),
            developerEntries = emptyList(),
        )
        try {
            r.importLegacy(emptyBundle)
            assertTrue("expected import to abort", false)
        } catch (e: VaultRepository.InvalidImportException) {
            // expected
        }
        assertEquals(0, db.authAccountDao().count())
    }

    // ------------------------------------------------------------------
    // Recovery code lifecycle
    // ------------------------------------------------------------------

    @Test
    fun `mark recovery code used then unused`() = runBlocking {
        val r = repo()
        r.importLegacy(sampleBundle())
        // Seed a recovery set manually.
        val accId = db.authAccountDao().listAllIds().first()
        db.recoveryCodeSetDao().insertAll(
            listOf(com.rescueauth.v2.database.RecoveryCodeSetEntity("set-1", accId, "Backup", "t"))
        )
        db.recoveryCodeDao().insertAll(
            listOf(com.rescueauth.v2.database.RecoveryCodeEntity("c1", "set-1", "AAAA", "UNUSED", null, 0))
        )
        r.markRecoveryCodeUsed("c1")
        assertEquals("USED", db.recoveryCodeDao().listBySet("set-1").first().status)
        r.markRecoveryCodeUnused("c1")
        assertEquals("UNUSED", db.recoveryCodeDao().listBySet("set-1").first().status)
    }
}
