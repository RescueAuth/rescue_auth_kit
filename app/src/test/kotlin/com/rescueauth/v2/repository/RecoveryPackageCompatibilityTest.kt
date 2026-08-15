package com.rescueauth.v2.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rescueauth.v2.database.RescueAuthDatabase
import com.rescueauth.v2.export.MergePlanner
import com.rescueauth.v2.export.SnapshotScope
import com.rescueauth.v2.export.VaultPackagePayload
import com.rescueauth.v2.export.VaultRecoveryCode
import com.rescueauth.v2.export.VaultRecoveryCodeSet
import com.rescueauth.v2.export.VaultSnapshot
import com.rescueauth.v2.session.SecureSessionStateMachine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 4 P3 package compatibility tests.
 *
 * Verifies that Recovery Codes created/edited through the new daily-use path
 * round-trip through the existing Phase 3 pipeline untouched: local Vault →
 * Full Vault logical snapshot → package/import (existing merge path) → state
 * preserved; repeated import idempotent; used/unused divergence still blocks;
 * MergePlanner conflict semantics are unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecoveryPackageCompatibilityTest {

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

    private fun vault() = VaultRepository(db, session)
    private fun auth() = AuthenticatorRepository(vault(), db, session)
    private fun recovery() = RecoveryCodeRepository(vault(), db, session)

    private fun payload(snapshot: VaultSnapshot, packageId: String = "pkg-recovery") = VaultPackagePayload(
        logicalSchemaVersion = VaultPackagePayload.CURRENT_LOGICAL_SCHEMA_VERSION,
        packageId = packageId,
        createdAt = "2024-01-01T00:00:00Z",
        snapshot = snapshot,
    )

    /** Builds a local Vault with one account + one recovery set (partial used). */
    private suspend fun seedLocalVault(): Seed {
        val account = auth().findOrCreateAccount("GitHub", "alice@example.com")
        val set = recovery().createSet(account.id, "Backup codes", listOf("AAAA-1111", "BBBB-2222", "CCCC-3333"))
        recovery().markUsed(set.codes[0].id)
        val usedAt = recovery().observeSetsByAccount(account.id).first().single()
            .codes.first { it.stableId == set.codes[0].stableId }.usedAt
        return Seed(account.id, set.stableId, set.codes.map { it.stableId }, usedAt)
    }

    data class Seed(
        val accountId: String,
        val setStableId: String,
        val codeStableIds: List<String>,
        val usedAt: String?,
    )

    // ------------------------------------------------------------------
    // 19. Recovery Set appears in Full Vault snapshot
    // ------------------------------------------------------------------

    @Test
    fun `recovery set appears in full vault snapshot`() = runBlocking {
        seedLocalVault()
        val snapshot = vault().buildConsistentExportSnapshot()
        assertEquals(SnapshotScope.FULL_VAULT, snapshot.scope)
        assertEquals(1, snapshot.accounts.size)
        val account = snapshot.accounts.single()
        assertEquals(1, account.recoveryCodeSets.size)
        val set = account.recoveryCodeSets.single()
        assertEquals("Backup codes", set.title)
        assertEquals(3, set.codes.size)
        assertEquals(1, set.codes.count { it.status == "USED" })
    }

    // ------------------------------------------------------------------
    // 20-21. used/unused + usedAt survive package logical round-trip
    // ------------------------------------------------------------------

    @Test
    fun `used and unused states survive logical package round trip`() = runBlocking {
        seedLocalVault()
        val snapshot = vault().buildConsistentExportSnapshot()

        // Import into an EMPTY vault (existing transactional merge path).
        val importDb = Room.inMemoryDatabaseBuilder(context, RescueAuthDatabase::class.java)
            .allowMainThreadQueries().build()
        val importSession = SecureSessionStateMachine()
        importSession.beginAuthentication()
        importSession.onAuthenticationSuccess()
        val importVault = VaultRepository(importDb, importSession)
        val importRepo = RecoveryCodeRepository(importVault, importDb, importSession)
        val importAuth = AuthenticatorRepository(importVault, importDb, importSession)

        val outcome = importVault.applyMergePlan(payload(snapshot))
        assertTrue(outcome is ImportOutcome.Applied)

        // The imported vault holds the same set with used/unused + usedAt.
        val account = importAuth.findOrCreateAccount("GitHub", "alice@example.com")
        val imported = importRepo.observeSetsByAccount(account.id).first().single()
        assertEquals("Backup codes", imported.title)
        assertEquals(3, imported.totalCount)
        assertEquals(1, imported.usedCount)
        assertEquals(2, imported.remainingCount)
        val used = imported.codes.first { it.isUsed }
        assertNotNull(used.usedAt)
        importDb.close()
    }

    @Test
    fun `export import into empty vault preserves exact state`() = runBlocking {
        val seed = seedLocalVault()

        // Simulate a full round-trip: local vault → snapshot → empty DB → snapshot.
        val exported = vault().buildConsistentExportSnapshot()

        val importDb = Room.inMemoryDatabaseBuilder(context, RescueAuthDatabase::class.java)
            .allowMainThreadQueries().build()
        val importSession = SecureSessionStateMachine()
        importSession.beginAuthentication()
        importSession.onAuthenticationSuccess()
        val importVault = VaultRepository(importDb, importSession)
        val importRepo = RecoveryCodeRepository(importVault, importDb, importSession)
        val importAuth = AuthenticatorRepository(importVault, importDb, importSession)

        val outcome = importVault.applyMergePlan(payload(exported))
        assertTrue(outcome is ImportOutcome.Applied)

        // Re-export from the imported vault and compare exact logical state.
        val reExported = importVault.buildConsistentExportSnapshot()
        assertEquals(exported.accounts.size, reExported.accounts.size)
        val srcSet = exported.accounts.single().recoveryCodeSets.single()
        val dstSet = reExported.accounts.single().recoveryCodeSets.single()
        assertEquals(srcSet.stableId, dstSet.stableId)
        assertEquals(srcSet.title, dstSet.title)
        assertEquals(srcSet.codes.size, dstSet.codes.size)
        val srcByValue = srcSet.codes.associateBy { it.value }
        val dstByValue = dstSet.codes.associateBy { it.value }
        for ((value, srcCode) in srcByValue) {
            val dstCode = dstByValue[value]!!
            assertEquals(srcCode.stableId, dstCode.stableId)
            assertEquals(srcCode.status, dstCode.status)
            assertEquals(srcCode.usedAt, dstCode.usedAt)
        }
        // The specific USED code kept its stableId and usedAt.
        val usedCode = dstSet.codes.first { it.status == "USED" }
        assertEquals(seed.codeStableIds[0], usedCode.stableId)
        assertEquals(seed.usedAt, usedCode.usedAt)
        importDb.close()
    }

    // ------------------------------------------------------------------
    // 23. idempotent repeated import
    // ------------------------------------------------------------------

    @Test
    fun `same package imported twice is idempotent`() = runBlocking {
        seedLocalVault()
        val snapshot = vault().buildConsistentExportSnapshot()
        val outcome1 = vault().applyMergePlan(payload(snapshot, "pkg-1"))
        assertTrue(outcome1 is ImportOutcome.Applied)

        val outcome2 = vault().applyMergePlan(payload(snapshot, "pkg-1"))
        assertTrue(outcome2 is ImportOutcome.Applied)
        assertEquals(1, db.recoveryCodeSetDao().listByAccount(db.authAccountDao().listAllIds().first()).size)
        assertEquals(3, db.recoveryCodeDao().listBySet(
            db.recoveryCodeSetDao().listByAccount(db.authAccountDao().listAllIds().first()).single().id,
        ).size)
    }

    // ------------------------------------------------------------------
    // 24. conflicting used/unused continues to surface divergence
    // ------------------------------------------------------------------

    @Test
    fun `conflicting used unused continues to surface divergence`() = runBlocking {
        seedLocalVault()

        // Build a source package where the same set has opposite code states.
        val account = auth().findOrCreateAccount("GitHub", "alice@example.com")
        val set = recovery().observeSetsByAccount(account.id).first().single()
        val sourceCodes = set.codes.map { code ->
            VaultRecoveryCode(
                stableId = code.stableId,
                value = code.value,
                status = if (code.isUsed) "UNUSED" else "USED",
                usedAt = if (code.isUsed) null else "2024-06-01T00:00:00Z",
                sortOrder = code.sortOrder,
            )
        }
        val source = VaultSnapshot(
            accounts = listOf(
                MergeTestData.account(
                    id = "github-account",
                    serviceName = "GitHub",
                    accountName = "alice@example.com",
                    recoverySets = listOf(
                        VaultRecoveryCodeSet(
                            stableId = set.stableId,
                            title = set.title,
                            createdAt = set.createdAt,
                            codes = sourceCodes,
                        ),
                    ),
                ),
            ),
            scope = SnapshotScope.FULL_VAULT,
        )

        val outcome = vault().applyMergePlan(payload(source, "pkg-divergence"))
        assertTrue(outcome is ImportOutcome.Blocked)
        val blocked = outcome as ImportOutcome.Blocked
        assertEquals(3, blocked.result.stateDivergences)
        // Nothing was written (blocked before apply) — the USED code stays USED.
        assertEquals("USED", db.recoveryCodeDao().listBySet(set.id).single { it.value == "AAAA-1111" }.status)
    }

    // ------------------------------------------------------------------
    // 25. P3 does not change MergePlanner conflict semantics
    // ------------------------------------------------------------------

    @Test
    fun `merge planner semantics unchanged for recovery sets`() = runBlocking {
        // Same stableId + different code values → CONFLICT (unchanged contract).
        val dest = VaultSnapshot(
            accounts = listOf(
                MergeTestData.account(
                    id = "a",
                    serviceName = "GitHub",
                    accountName = "alice",
                    recoverySets = listOf(
                        MergeTestData.recoverySet("set-1", title = "Backup", codes = listOf(MergeTestData.recoveryCode("c1", "AAAA"))),
                    ),
                ),
            ),
            scope = SnapshotScope.FULL_VAULT,
        )
        val source = VaultSnapshot(
            accounts = listOf(
                MergeTestData.account(
                    id = "a",
                    serviceName = "GitHub",
                    accountName = "alice",
                    recoverySets = listOf(
                        MergeTestData.recoverySet("set-1", title = "Backup", codes = listOf(MergeTestData.recoveryCode("c1", "BBBB"))),
                    ),
                ),
            ),
            scope = SnapshotScope.FULL_VAULT,
        )
        val plan = MergePlanner.plan(dest, source)
        assertEquals(1, plan.summary.conflicts)
        assertEquals(0, plan.summary.stateDivergences)
        assertTrue(plan.accountPlans.single().recoverySetPlans.single().decision == com.rescueauth.v2.export.MergeDecision.CONFLICT)
    }

    /** P3 writes must round-trip through the FULL export snapshot unchanged. */
    @Test
    fun `recovery set created via P3 flows into export snapshot with all state`() = runBlocking {
        val seed = seedLocalVault()
        val snapshot = vault().buildConsistentExportSnapshot()
        val set = snapshot.accounts.single().recoveryCodeSets.single()
        assertEquals(seed.setStableId, set.stableId)
        assertNotNull(set.codes.first { it.status == "USED" }.usedAt)
        assertFalse(set.codes.any { it.status !in setOf("USED", "UNUSED") })
    }
}
