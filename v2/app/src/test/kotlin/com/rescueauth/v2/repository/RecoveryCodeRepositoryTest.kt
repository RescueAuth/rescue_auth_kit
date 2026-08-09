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
import org.junit.Assert.assertFalse
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
 * Phase 4 P3 Recovery Codes repository tests — real Room persistence,
 * transactional create/edit/delete, minimal identity-preserving diff,
 * USED/UNUSED state and close/reopen persistence.
 *
 * Uses an in-memory (unencrypted) Room DB; the SQLCipher path is covered by
 * the instrumented suite. All mutations go through [VaultRepository.mutate]
 * (shared mutex + transaction).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecoveryCodeRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: RescueAuthDatabase
    private lateinit var session: SecureSessionStateMachine
    private lateinit var vault: VaultRepository
    private lateinit var repo: RecoveryCodeRepository
    private lateinit var auth: AuthenticatorRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, RescueAuthDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        session = SecureSessionStateMachine()
        session.beginAuthentication()
        session.onAuthenticationSuccess()
        vault = VaultRepository(db, session)
        repo = RecoveryCodeRepository(vault, db, session)
        auth = AuthenticatorRepository(vault, db, session)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun accountId(): String =
        auth.findOrCreateAccount("GitHub", "alice@example.com").id

    private suspend fun createSet(
        title: String = "Backup codes",
        values: List<String> = listOf("AAAA-1111", "BBBB-2222", "CCCC-3333"),
    ): com.rescueauth.v2.domain.RecoveryCodeSet =
        repo.createSet(accountId(), title, values)

    // ------------------------------------------------------------------
    // 1. create / batch create / account relation
    // ------------------------------------------------------------------

    @Test
    fun `create recovery set persists and belongs to correct account`() = runBlocking {
        val account = auth.findOrCreateAccount("GitHub", "alice@example.com")
        val set = repo.createSet(account.id, "Backup codes", listOf("AAAA", "BBBB", "CCCC"))

        assertNotNull(set.id)
        assertNotNull(set.stableId)
        assertEquals("Backup codes", set.title)
        assertEquals(3, set.codes.size)
        assertTrue(set.codes.all { !it.isUsed })
        assertEquals(0, set.usedCount)
        assertEquals(3, set.totalCount)
        assertEquals(3, set.remainingCount)

        val observed = repo.observeSetsByAccount(account.id).first()
        assertEquals(1, observed.size)
        assertEquals(account.id, observed[0].accountId)
        assertEquals(3, observed[0].codes.size)
    }

    @Test
    fun `batch create codes from multiline paste trims whitespace and skips blanks`() = runBlocking {
        val account = auth.findOrCreateAccount("GitHub", "alice@example.com")
        // The repository receives pre-parsed lines; the whitespace/blank
        // handling is asserted through the ViewModel parse contract. Here we
        // verify trimmed values persist verbatim (opaque secrets).
        val set = repo.createSet(
            account.id,
            "Backup",
            listOf("  AAAA-1111  ", "BBBB-2222", "  ", "CCCC-3333"),
        )
        assertEquals(3, set.codes.size)
        assertEquals(listOf("AAAA-1111", "BBBB-2222", "CCCC-3333"), set.codes.map { it.value })
    }

    @Test
    fun `sets created under different accounts stay separate`() = runBlocking {
        val accA = auth.findOrCreateAccount("GitHub", "alice@example.com")
        val accB = auth.findOrCreateAccount("GitLab", "bob@example.com")
        repo.createSet(accA.id, "Set A", listOf("AAAA"))
        repo.createSet(accB.id, "Set B", listOf("BBBB"))

        assertEquals(1, repo.observeSetsByAccount(accA.id).first().size)
        assertEquals(1, repo.observeSetsByAccount(accB.id).first().size)
        assertEquals("Set A", repo.observeSetsByAccount(accA.id).first()[0].title)
        assertEquals("Set B", repo.observeSetsByAccount(accB.id).first()[0].title)
    }

    @Test
    fun `same code in two different sets is allowed`() = runBlocking {
        val account = auth.findOrCreateAccount("GitHub", "alice@example.com")
        repo.createSet(account.id, "Set 1", listOf("SHARED-CODE"))
        repo.createSet(account.id, "Set 2", listOf("SHARED-CODE"))

        val sets = repo.observeSetsByAccount(account.id).first()
        assertEquals(2, sets.size)
        assertEquals(2, sets.sumOf { it.codes.size })
    }

    // ------------------------------------------------------------------
    // 3. duplicate input validation (exact whitespace-normalised only)
    // ------------------------------------------------------------------

    @Test
    fun `exact duplicate lines are rejected`() = runBlocking<Unit> {
        val account = auth.findOrCreateAccount("GitHub", "alice@example.com")
        assertThrows(RecoveryCodeRepository.ValidationException::class.java) {
            runBlocking {
                repo.createSet(account.id, "Backup", listOf("AAAA", "AAAA"))
            }
        }
    }

    @Test
    fun `ABC-123 and ABC123 are distinct opaque secrets`() = runBlocking {
        val account = auth.findOrCreateAccount("GitHub", "alice@example.com")
        val set = repo.createSet(account.id, "Backup", listOf("ABC-123", "ABC123"))
        assertEquals(2, set.codes.size)
    }

    @Test
    fun `duplicate check trims whitespace but keeps case distinct`() = runBlocking {
        val account = auth.findOrCreateAccount("GitHub", "alice@example.com")
        // " abc " trims to "abc" → exact duplicate of "abc" is rejected.
        assertThrows(RecoveryCodeRepository.ValidationException::class.java) {
            runBlocking { repo.createSet(account.id, "Backup", listOf(" abc ", "abc")) }
        }
        // "ABC" (different case) is a distinct opaque secret.
        val set = repo.createSet(account.id, "Backup", listOf("abc", "ABC"))
        assertEquals(2, set.codes.size)
    }

    // ------------------------------------------------------------------
    // 5-9. mark used / unused, usedAt, remaining count
    // ------------------------------------------------------------------

    @Test
    fun `mark used persists status and usedAt`() = runBlocking {
        val set = createSet()
        val first = set.codes[0]
        repo.markUsed(first.id)

        val reloaded = repo.observeSetsByAccount(accountId()).first().single()
        val code = reloaded.codes.first { it.id == first.id }
        assertTrue(code.isUsed)
        assertNotNull(code.usedAt)
        assertEquals(1, reloaded.usedCount)
        assertEquals(2, reloaded.remainingCount)
    }

    @Test
    fun `mark used then unused clears usedAt`() = runBlocking {
        val set = createSet()
        val first = set.codes[0]
        repo.markUsed(first.id)
        repo.markUnused(first.id)

        val reloaded = repo.observeSetsByAccount(accountId()).first().single()
        val code = reloaded.codes.first { it.id == first.id }
        assertFalse(code.isUsed)
        assertNull(code.usedAt)
        assertEquals(0, reloaded.usedCount)
        assertEquals(3, reloaded.remainingCount)
    }

    // ------------------------------------------------------------------
    // 10-15. edit semantics
    // ------------------------------------------------------------------

    @Test
    fun `edit title updates set metadata`() = runBlocking {
        val set = createSet()
        val edited = repo.editSet(set.id, "Renamed", set.codes.map { it.value })
        assertEquals("Renamed", edited.title)
        assertEquals(3, edited.codes.size)
    }

    @Test
    fun `edit list preserves unchanged code stableId and USED state`() = runBlocking {
        val set = createSet()
        val first = set.codes[0]
        val second = set.codes[1]
        repo.markUsed(second.id)

        val stableIdFirst = first.stableId
        val stableIdSecond = second.stableId

        // Edit: keep A + B, remove C, add D.
        val edited = repo.editSet(set.id, "Backup", listOf("AAAA-1111", "BBBB-2222", "DDDD-4444"))

        val byValue = edited.codes.associateBy { it.value }
        assertEquals(stableIdFirst, byValue["AAAA-1111"]!!.stableId)
        assertEquals(stableIdSecond, byValue["BBBB-2222"]!!.stableId)
        assertTrue(byValue["BBBB-2222"]!!.isUsed)
        assertNotNull(byValue["BBBB-2222"]!!.usedAt)
        // Removed code deleted, new code fresh.
        assertFalse(edited.codes.any { it.value == "CCCC-3333" })
        val d = byValue["DDDD-4444"]!!
        assertFalse(d.isUsed)
        assertTrue(d.stableId.isNotBlank())
    }

    @Test
    fun `edit that changes a code value creates a new stableId`() = runBlocking {
        val set = createSet()
        val originalStableId = set.codes[0].stableId
        // Change value A → A-modified (treated as delete + create).
        val edited = repo.editSet(set.id, "Backup", listOf("AAAA-1111-X", "BBBB-2222", "CCCC-3333"))
        val a = edited.codes.first { it.value == "AAAA-1111-X" }
        assertTrue(a.stableId != originalStableId)
        assertFalse(a.isUsed)
    }

    @Test
    fun `edit with duplicate new values is rejected and nothing changes`() = runBlocking<Unit> {
        val set = createSet()
        assertThrows(RecoveryCodeRepository.ValidationException::class.java) {
            runBlocking { repo.editSet(set.id, "Backup", listOf("AAAA-1111", "AAAA-1111", "BBBB-2222")) }
        }
        // Original unchanged (transactional rollback).
        val reloaded = repo.observeSetsByAccount(accountId()).first().single()
        assertEquals(3, reloaded.codes.size)
        assertEquals("Backup codes", reloaded.title)
    }

    // ------------------------------------------------------------------
    // 16-17. delete + Undo
    // ------------------------------------------------------------------

    @Test
    fun `delete set removes it and undo restores exact stableIds and states`() = runBlocking {
        val account = auth.findOrCreateAccount("GitHub", "alice@example.com")
        val set = createSet()
        val first = set.codes[0]
        repo.markUsed(first.id)

        val setStableId = set.stableId
        val codeStableIds = set.codes.map { it.stableId }

        val deleted = repo.deleteSet(set.id)
        assertNotNull(deleted)
        assertEquals(0, repo.observeSetsByAccount(account.id).first().size)

        repo.restoreSet(deleted!!)
        val restored = repo.observeSetsByAccount(account.id).first().single()
        assertEquals(setStableId, restored.stableId)
        assertEquals(3, restored.codes.size)
        assertEquals(codeStableIds.sorted(), restored.codes.map { it.stableId }.sorted())
        val restoredFirst = restored.codes.first { it.stableId == first.stableId }
        assertTrue(restoredFirst.isUsed)
        assertNotNull(restoredFirst.usedAt)
        // Values and used/unused preserved for all.
        assertEquals(set.codes.map { it.value }.sorted(), restored.codes.map { it.value }.sorted())
    }

    @Test
    fun `delete of missing set returns null`() = runBlocking {
        assertNull(repo.deleteSet("does-not-exist"))
    }

    // ------------------------------------------------------------------
    // 18. close / reopen persistence
    // ------------------------------------------------------------------

    @Test
    fun `close and reopen database preserves sets codes and states`() = runBlocking<Unit> {
        val fileDb = Room.databaseBuilder(
            context,
            RescueAuthDatabase::class.java,
            "recovery-persistence-test.db",
        ).allowMainThreadQueries().build()
        val fileSession = SecureSessionStateMachine()
        fileSession.beginAuthentication()
        fileSession.onAuthenticationSuccess()
        val fileVault = VaultRepository(fileDb, fileSession)
        val fileRepo = RecoveryCodeRepository(fileVault, fileDb, fileSession)
        val fileAuth = AuthenticatorRepository(fileVault, fileDb, fileSession)

        val account = fileAuth.findOrCreateAccount("GitHub", "alice@example.com")
        val set = fileRepo.createSet(account.id, "Backup", listOf("AAAA", "BBBB", "CCCC"))
        val setStableId = set.stableId
        val codeStableIds = set.codes.map { it.stableId }
        fileRepo.markUsed(set.codes[1].id)
        val usedAt = fileRepo.observeSetsByAccount(account.id).first().single().codes[1].usedAt
        fileDb.close()

        val db2 = Room.databaseBuilder(
            context,
            RescueAuthDatabase::class.java,
            "recovery-persistence-test.db",
        ).allowMainThreadQueries().build()
        val session2 = SecureSessionStateMachine()
        session2.beginAuthentication()
        session2.onAuthenticationSuccess()
        val vault2 = VaultRepository(db2, session2)
        val repo2 = RecoveryCodeRepository(vault2, db2, session2)
        val auth2 = AuthenticatorRepository(vault2, db2, session2)

        val account2 = auth2.findOrCreateAccount("GitHub", "alice@example.com")
        val reloaded = repo2.observeSetsByAccount(account2.id).first().single()
        assertEquals(setStableId, reloaded.stableId)
        assertEquals(codeStableIds.sorted(), reloaded.codes.map { it.stableId }.sorted())
        val used = reloaded.codes.first { it.stableId == codeStableIds[1] }
        assertTrue(used.isUsed)
        assertEquals(usedAt, used.usedAt)
        assertEquals(1, reloaded.usedCount)
        db2.close()
        context.deleteDatabase("recovery-persistence-test.db")
    }
}
