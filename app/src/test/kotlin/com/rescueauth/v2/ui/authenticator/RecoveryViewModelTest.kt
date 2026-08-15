package com.rescueauth.v2.ui.authenticator

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rescueauth.v2.database.RescueAuthDatabase
import com.rescueauth.v2.repository.AuthenticatorRepository
import com.rescueauth.v2.repository.RecoveryCodeRepository
import com.rescueauth.v2.repository.VaultRepository
import com.rescueauth.v2.session.SecureSessionStateMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
 * Phase 4 P3 Recovery ViewModel tests — real repository path (in-memory
 * Room), create flow (multiline parse), validation, mark used/unused +
 * remaining count, reveal/hide, single copy, Copy All, edit flow, delete →
 * Undo and session-lock reveal cleanup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecoveryViewModelTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: RescueAuthDatabase
    private lateinit var session: SecureSessionStateMachine
    private lateinit var auth: AuthenticatorRepository
    private lateinit var recovery: RecoveryCodeRepository
    private lateinit var accountId: String

    private var activeScope: CoroutineScope? = null

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, RescueAuthDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        session = SecureSessionStateMachine()
        session.beginAuthentication()
        session.onAuthenticationSuccess()
        val vault = VaultRepository(db, session)
        auth = AuthenticatorRepository(vault, db, session)
        recovery = RecoveryCodeRepository(vault, db, session)
        accountId = runBlocking { auth.findOrCreateAccount("GitHub", "alice@example.com").id }
    }

    @After
    fun tearDown() {
        // Fully unwind background collection coroutines before closing the DB.
        // Otherwise a Room Flow still unwinding on Dispatchers.Default can query
        // the already-closed connection pool and throw an uncaught background
        // thread exception that surfaces in a later test (via runTest) as
        // UncaughtExceptionsBeforeTest (repro: DeveloperScreenTest flake).
        runBlocking { activeScope?.coroutineContext?.get(Job)?.cancelAndJoin() }
        activeScope = null
        db.close()
    }

    private fun newScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun viewModel(scope: CoroutineScope = newScope()): RecoveryViewModel {
        activeScope = scope
        return RecoveryViewModel(
            authRepositoryProvider = { auth },
            recoveryRepositoryProvider = { recovery },
            sessionState = session.state,
            accountId = accountId,
            scope = scope,
        )
    }

    private suspend fun awaitSets(
        vm: RecoveryViewModel,
        predicate: (RecoveryUiState) -> Boolean,
    ) {
        withTimeout(5_000L) {
            while (!predicate(vm.uiState.value)) {
                delay(10)
            }
        }
    }

    @Test
    fun `empty recovery state`() = runBlocking {
        val vm = viewModel()
        awaitSets(vm) { !it.loading }
        assertTrue(vm.uiState.value.isEmpty)
        assertEquals(0, vm.uiState.value.remainingCount)
    }

    @Test
    fun `create flow persists a set`() = runBlocking {
        val vm = viewModel()
        awaitSets(vm) { !it.loading }

        vm.beginCreate()
        vm.onTitleChange("Backup codes")
        vm.onValuesChange("AAAA-1111\nBBBB-2222\nCCCC-3333")
        assertTrue(vm.submitForm())

        awaitSets(vm) { it.sets.size == 1 }
        assertEquals("Backup codes", vm.uiState.value.sets[0].title)
        assertEquals(3, vm.uiState.value.sets[0].totalCount)
        assertEquals(3, vm.uiState.value.sets[0].remainingCount)
    }

    @Test
    fun `multiline parse trims whitespace and skips blank lines`() = runBlocking {
        val vm = viewModel()
        awaitSets(vm) { !it.loading }
        vm.beginCreate()
        vm.onTitleChange("Backup")
        vm.onValuesChange("  AAAA  \n\nBBBB\n  \nCCCC\n")
        val parsed = vm.formState.value.parsedValues()
        assertEquals(listOf("AAAA", "BBBB", "CCCC"), parsed)
    }

    @Test
    fun `validation error on duplicate codes`() = runBlocking {
        val vm = viewModel()
        awaitSets(vm) { !it.loading }
        vm.beginCreate()
        vm.onTitleChange("Backup")
        vm.onValuesChange("AAAA\nAAAA")
        assertFalse(vm.submitForm())
        assertNotNull(vm.formState.value.error)
        assertTrue(vm.formState.value.error!!.startsWith("duplicate:"))
        awaitSets(vm) { it.sets.isEmpty() }
    }

    @Test
    fun `validation error on empty values`() = runBlocking {
        val vm = viewModel()
        awaitSets(vm) { !it.loading }
        vm.beginCreate()
        vm.onTitleChange("Backup")
        vm.onValuesChange("   \n\n")
        assertFalse(vm.submitForm())
        assertEquals("empty_values", vm.formState.value.error)
    }

    @Test
    fun `mark used updates remaining count and mark unused restores it`() = runBlocking {
        val vm = viewModel()
        awaitSets(vm) { !it.loading }
        vm.beginCreate()
        vm.onTitleChange("Backup")
        vm.onValuesChange("AAAA\nBBBB\nCCCC")
        assertTrue(vm.submitForm())
        awaitSets(vm) { it.sets.size == 1 }
        val setId = vm.uiState.value.sets[0].id
        val codeId = vm.uiState.value.sets[0].codes[0].id

        assertTrue(vm.markUsed(codeId, "code-1"))
        awaitSets(vm) { it.sets[0].remainingCount == 2 }
        assertEquals(1, vm.uiState.value.sets[0].usedCount)
        assertEquals(2, vm.uiState.value.remainingCount)

        assertTrue(vm.markUnused(codeId, "code-1"))
        awaitSets(vm) { it.sets[0].remainingCount == 3 }
        assertEquals(0, vm.uiState.value.sets[0].usedCount)
        assertEquals(3, vm.uiState.value.remainingCount)
        assertEquals(setId, vm.uiState.value.sets[0].id)
    }

    @Test
    fun `reveal and hide toggle per code`() = runBlocking {
        val vm = viewModel()
        awaitSets(vm) { !it.loading }
        vm.beginCreate()
        vm.onTitleChange("Backup")
        vm.onValuesChange("AAAA")
        assertTrue(vm.submitForm())
        awaitSets(vm) { it.sets.size == 1 }
        val codeId = vm.uiState.value.sets[0].codes[0].id

        assertFalse(vm.isRevealed(codeId))
        vm.toggleReveal(codeId)
        assertTrue(vm.isRevealed(codeId))
        vm.toggleReveal(codeId)
        assertFalse(vm.isRevealed(codeId))
    }

    @Test
    fun `single copy emits copy event with the code value`() = runBlocking {
        val vm = viewModel()
        awaitSets(vm) { !it.loading }
        vm.beginCreate()
        vm.onTitleChange("Backup")
        vm.onValuesChange("AAAA-1111")
        assertTrue(vm.submitForm())
        awaitSets(vm) { it.sets.size == 1 }
        val codeId = vm.uiState.value.sets[0].codes[0].id

        vm.copyCode(codeId)
        val event = vm.events.value
        assertTrue(event is RecoveryEvent.CopyCode)
        assertEquals("AAAA-1111", (event as RecoveryEvent.CopyCode).value)
    }

    @Test
    fun `copy all emits all values and copy remaining emits only unused`() = runBlocking {
        val vm = viewModel()
        awaitSets(vm) { !it.loading }
        vm.beginCreate()
        vm.onTitleChange("Backup")
        vm.onValuesChange("AAAA\nBBBB\nCCCC")
        assertTrue(vm.submitForm())
        awaitSets(vm) { it.sets.size == 1 }
        val setId = vm.uiState.value.sets[0].id
        vm.markUsed(vm.uiState.value.sets[0].codes[0].id, "c1")
        awaitSets(vm) { it.sets[0].remainingCount == 2 }

        vm.copyAll(setId)
        val allEvent = vm.events.value
        assertTrue(allEvent is RecoveryEvent.CopyAll)
        assertEquals(3, (allEvent as RecoveryEvent.CopyAll).values.size)

        vm.copyRemaining(setId)
        val remEvent = vm.events.value
        assertTrue(remEvent is RecoveryEvent.CopyRemaining)
        assertEquals(2, (remEvent as RecoveryEvent.CopyRemaining).values.size)
        assertFalse(remEvent.values.contains("AAAA"))
    }

    @Test
    fun `edit flow preserves stableId and used state`() = runBlocking {
        val vm = viewModel()
        awaitSets(vm) { !it.loading }
        vm.beginCreate()
        vm.onTitleChange("Backup")
        vm.onValuesChange("AAAA\nBBBB\nCCCC")
        assertTrue(vm.submitForm())
        awaitSets(vm) { it.sets.size == 1 }
        val setId = vm.uiState.value.sets[0].id
        val aId = vm.uiState.value.sets[0].codes[0].id
        val bId = vm.uiState.value.sets[0].codes[1].id
        vm.markUsed(vm.uiState.value.sets[0].codes[1].id, "c2")
        awaitSets(vm) { it.sets[0].remainingCount == 2 }

        vm.beginEdit(setId)
        assertEquals("Backup", vm.formState.value.title)
        vm.onValuesChange("AAAA\nBBBB\nDDDD")
        assertTrue(vm.submitForm())
        awaitSets(vm) { it.sets[0].codes.size == 3 && it.sets[0].codes.none { c -> c.value == "CCCC" } }

        val codes = vm.uiState.value.sets[0].codes
        val b = codes.first { it.value == "BBBB" }
        assertEquals(bId, b.id)
        assertTrue(b.isUsed)
        val d = codes.first { it.value == "DDDD" }
        assertFalse(d.isUsed)
        // A kept the same id.
        assertEquals(aId, codes.first { it.value == "AAAA" }.id)
    }

    @Test
    fun `delete set emits deleted and undo restores`() = runBlocking {
        val vm = viewModel()
        awaitSets(vm) { !it.loading }
        vm.beginCreate()
        vm.onTitleChange("Backup")
        vm.onValuesChange("AAAA\nBBBB")
        assertTrue(vm.submitForm())
        awaitSets(vm) { it.sets.size == 1 }
        val setId = vm.uiState.value.sets[0].id
        val setStableId = vm.uiState.value.sets[0].id

        assertTrue(vm.deleteSet(setId))
        awaitSets(vm) { it.sets.isEmpty() }
        assertTrue(vm.events.value is RecoveryEvent.Deleted)

        assertTrue(vm.undoDelete())
        awaitSets(vm) { it.sets.size == 1 }
        assertEquals(setStableId, vm.uiState.value.sets[0].id)
        assertEquals(2, vm.uiState.value.sets[0].codes.size)
    }

    @Test
    fun `session lock hides visible sensitive ui state`() = runBlocking {
        val vm = viewModel()
        awaitSets(vm) { !it.loading }
        vm.beginCreate()
        vm.onTitleChange("Backup")
        vm.onValuesChange("AAAA")
        assertTrue(vm.submitForm())
        awaitSets(vm) { it.sets.size == 1 }
        val codeId = vm.uiState.value.sets[0].codes[0].id
        vm.toggleReveal(codeId)
        assertTrue(vm.isRevealed(codeId))

        // Lock the session → reveal state cleared, UI emptied.
        session.lock()
        awaitSets(vm) { it.sets.isEmpty() }
        assertFalse(vm.isRevealed(codeId))
        assertEquals(RecoveryFormState(), vm.formState.value)
    }
}
