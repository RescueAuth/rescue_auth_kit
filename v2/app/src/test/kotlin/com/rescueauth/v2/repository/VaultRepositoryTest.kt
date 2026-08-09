package com.rescueauth.v2.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rescueauth.v2.database.RescueAuthDatabase
import com.rescueauth.v2.session.SecureSessionStateMachine
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
 * VaultRepository tests: serialized mutations, session-lock enforcement and
 * transactional snapshot apply (the shared import boundary).
 *
 * Phase 5B: the Phase-1 `importLegacy(LegacyImportBundle)` spike entry point
 * was removed from VaultRepository — the shared apply boundary is
 * [applySnapshot], which every import path (Native package and Legacy
 * `.rakvault`) funnels through (ROADMAP §9). The repository no longer imports
 * any legacy-specific model.
 *
 * Uses an in-memory (unencrypted) Room DB — the SQLCipher path is covered by
 * the instrumented test. All repository logic (locking, serialization,
 * transactional apply) is DB-backend agnostic.
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

    private fun sampleSnapshot() = MergeTestData.fullSnapshot(
        MergeTestData.account(
            "a1", "GitHub", "alice@example.com",
            totps = listOf(MergeTestData.totp("t1", secret = "JBSWY3DPEHPK3PXP")),
        ),
        MergeTestData.account(
            "a2", "Google", "bob@example.com",
            totps = listOf(MergeTestData.totp("t2", secret = "4F6VS6KX3UXWY2FQ", algorithm = "SHA256")),
        ),
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
    fun `snapshot apply when locked throws SessionLockedException`() = runBlocking {
        session.lock()
        try {
            repo().applySnapshot(sampleSnapshot(), "pkg-1")
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
        r.applySnapshot(sampleSnapshot(), "seed")
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
            (1..5).map { i ->
                async { r.applySnapshot(sampleSnapshot(), "seed-$i") }
            }.awaitAll()
        }
        // All 5 imports committed (serialized). Because the stableIds are
        // identical, imports 2..5 are all duplicates — exactly 2 accounts total.
        assertEquals(2, db.authAccountDao().count())
    }

    // ------------------------------------------------------------------
    // Transactional apply
    // ------------------------------------------------------------------

    @Test
    fun `snapshot apply persists accounts totps and import record`() = runBlocking {
        val outcome = repo().applySnapshot(sampleSnapshot(), "seed")
        assertTrue(outcome is ImportOutcome.Applied)
        val result = (outcome as ImportOutcome.Applied).result
        assertEquals(2, result.insertedAccounts)
        assertEquals(2, db.authAccountDao().count())
        assertEquals(2, db.totpCredentialDao().listAll().size)
        assertEquals(1, db.importRecordDao().listAll().size)
    }

    @Test
    fun `snapshot apply failure rolls back the whole transaction`() = runBlocking {
        val r = repo()
        // A failing WriteSeam makes the apply throw mid-transaction; nothing
        // (including the ImportRecord) may remain.
        val failing = object : WriteSeam {
            override fun beforeDeveloperInsert() {
                throw MergeApplyException("injected failure")
            }
        }
        try {
            r.applySnapshot(
                MergeTestData.fullSnapshot(
                    MergeTestData.account(
                        "a1", "GitHub", "alice",
                        totps = listOf(MergeTestData.totp("t1")),
                    ),
                ),
                "seed-fail",
                seam = failing,
            )
            assertTrue("expected apply failure", false)
        } catch (e: MergeApplyException) {
            // expected
        }
        assertEquals(0, db.authAccountDao().count())
        assertEquals(0, db.totpCredentialDao().listAll().size)
        assertEquals(0, db.importRecordDao().listAll().size)
    }

    // ------------------------------------------------------------------
    // Recovery code lifecycle
    // ------------------------------------------------------------------

    @Test
    fun `mark recovery code used then unused`() = runBlocking {
        val r = repo()
        r.applySnapshot(
            MergeTestData.fullSnapshot(
                MergeTestData.account(
                    "a1", "GitHub", "alice",
                    recoverySets = listOf(
                        MergeTestData.recoverySet(
                            "set-1",
                            codes = listOf(MergeTestData.recoveryCode("c1", "AAAA")),
                        ),
                    ),
                ),
            ),
            "seed",
        )
        val accId = db.authAccountDao().listAllIds().first()
        r.markRecoveryCodeUsed("c1")
        assertEquals("USED", db.recoveryCodeDao().listBySet("set-1").first().status)
        r.markRecoveryCodeUnused("c1")
        assertEquals("UNUSED", db.recoveryCodeDao().listBySet("set-1").first().status)
    }
}
