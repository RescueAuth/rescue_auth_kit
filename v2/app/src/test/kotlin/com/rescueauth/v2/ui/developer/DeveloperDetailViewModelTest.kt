package com.rescueauth.v2.ui.developer

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rescueauth.v2.database.RescueAuthDatabase
import com.rescueauth.v2.export.VaultKeyValue
import com.rescueauth.v2.repository.DeveloperRepository
import com.rescueauth.v2.repository.VaultRepository
import com.rescueauth.v2.security.FakeSensitiveActionPrompt
import com.rescueauth.v2.security.SensitiveAction
import com.rescueauth.v2.security.SensitiveActionGate
import com.rescueauth.v2.security.SensitiveActionResult
import com.rescueauth.v2.session.SecureSessionStateMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 4 P4 Developer detail ViewModel tests (Issue #20 §14/§15/§26–§28):
 *
 * - reveal requires re-auth, success reveals, hide removes, lock removes;
 * - copy requires its own re-auth (never bypassed by reveal state);
 * - unavailable authenticator blocks reveal/copy.
 *
 * A fake prompt boundary is used — no biometric hardware on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeveloperDetailViewModelTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: RescueAuthDatabase
    private lateinit var session: SecureSessionStateMachine
    private lateinit var vault: VaultRepository
    private lateinit var repo: DeveloperRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, RescueAuthDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        session = SecureSessionStateMachine()
        session.beginAuthentication()
        session.onAuthenticationSuccess()
        vault = VaultRepository(db, session)
        repo = DeveloperRepository(vault, db, session)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun gate(prompt: FakeSensitiveActionPrompt): SensitiveActionGate =
        SensitiveActionGate(
            prompt = prompt,
            session = session,
            titleProvider = { "Authenticate to continue" },
            subtitleProvider = { null },
        )

    private fun vm(
        stableId: String,
        prompt: FakeSensitiveActionPrompt = FakeSensitiveActionPrompt(),
        scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
    ): Pair<DeveloperDetailViewModel, FakeSensitiveActionPrompt> =
        DeveloperDetailViewModel(
            developerRepositoryProvider = { repo },
            sensitiveActionGate = gate(prompt),
            sessionState = session.state,
            stableId = stableId,
            scope = scope,
        ) to prompt

    private suspend fun createApi(stableId: String = "api-1"): String {
        // DeveloperRepository mints its own stableId; use the returned one.
        val e = repo.createApiCredential(
            title = "Stripe", notes = null,
            serviceName = "stripe", accountName = "alice",
            apiKey = "sk_live_123", apiSecret = "secret-abc",
        )
        return e.stableId
    }

    @Test
    fun `api detail loads metadata without exposing secrets`() = runBlocking {
        val stableId = createApi()
        val (vm, _) = vm(stableId)
        // Wait for the load.
        kotlinx.coroutines.delay(50)
        val state = vm.uiState.value
        val detail = state.detail as com.rescueauth.v2.ui.model.DeveloperDetailUi.ApiCredential
        assertEquals("Stripe", detail.title)
        assertEquals("stripe", detail.serviceName)
        assertEquals("alice", detail.accountName)
        // Secrets never in UI state.
        assertTrue(!state.toString().contains("sk_live_123"))
        assertTrue(!state.toString().contains("secret-abc"))
    }

    @Test
    fun `reveal requires a fresh re-auth`() = runBlocking {
        val stableId = createApi()
        val (vm, prompt) = vm(stableId)
        kotlinx.coroutines.delay(50)

        vm.reveal(SensitiveAction.REVEAL_API_SECRET, "apiSecret")
        assertEquals(1, prompt.startedActions.size)
        // No value revealed yet.
        assertNull(vm.revealedValue("apiSecret"))

        prompt.deliver(SensitiveActionResult.Success(SensitiveAction.REVEAL_API_SECRET))
        kotlinx.coroutines.delay(50)
        assertEquals("secret-abc", vm.revealedValue("apiSecret"))
    }

    @Test
    fun `auth success reveals and hide removes visible state`() = runBlocking {
        val stableId = createApi()
        val (vm, prompt) = vm(stableId)
        kotlinx.coroutines.delay(50)

        vm.reveal(SensitiveAction.REVEAL_API_SECRET, "apiSecret")
        prompt.deliver(SensitiveActionResult.Success(SensitiveAction.REVEAL_API_SECRET))
        kotlinx.coroutines.delay(50)
        assertEquals("secret-abc", vm.revealedValue("apiSecret"))
        assertTrue(vm.isRevealed("apiSecret"))

        // Manual hide clears the in-memory value.
        vm.clearRevealed()
        assertNull(vm.revealedValue("apiSecret"))
        assertFalse(vm.isRevealed("apiSecret"))
    }

    @Test
    fun `session lock removes visible state`() = runBlocking {
        val stableId = createApi()
        val (vm, prompt) = vm(stableId)
        kotlinx.coroutines.delay(50)

        vm.reveal(SensitiveAction.REVEAL_API_SECRET, "apiSecret")
        prompt.deliver(SensitiveActionResult.Success(SensitiveAction.REVEAL_API_SECRET))
        kotlinx.coroutines.delay(50)
        assertEquals("secret-abc", vm.revealedValue("apiSecret"))

        session.lock()
        // The ViewModel's session collector clears revealed state.
        kotlinx.coroutines.delay(50)
        assertNull(vm.revealedValue("apiSecret"))
        assertFalse(vm.isRevealed("apiSecret"))
    }

    @Test
    fun `copy requires its own re-auth even when value is revealed`() = runBlocking {
        val stableId = createApi()
        val (vm, prompt) = vm(stableId)
        kotlinx.coroutines.delay(50)

        // Reveal first.
        vm.reveal(SensitiveAction.REVEAL_API_SECRET, "apiSecret")
        prompt.deliver(SensitiveActionResult.Success(SensitiveAction.REVEAL_API_SECRET))
        kotlinx.coroutines.delay(50)
        assertEquals("secret-abc", vm.revealedValue("apiSecret"))
        assertEquals(1, prompt.startedActions.size)

        // Copy triggers a SECOND fresh re-auth (never bypassed by reveal).
        vm.copySecret(SensitiveAction.COPY_API_SECRET, "apiSecret", "apiSecret")
        assertEquals(2, prompt.startedActions.size)
        assertEquals(SensitiveAction.COPY_API_SECRET, prompt.startedActions[1])

        var copyEvent: DeveloperDetailEvent? = null
        val job = kotlinx.coroutines.GlobalScope.launch {
            vm.events.collect { copyEvent = it }
        }
        prompt.deliver(SensitiveActionResult.Success(SensitiveAction.COPY_API_SECRET))
        kotlinx.coroutines.delay(50)
        job.cancel()

        assertTrue(copyEvent is DeveloperDetailEvent.CopySecret)
        assertEquals("secret-abc", (copyEvent as DeveloperDetailEvent.CopySecret).value)
    }

    @Test
    fun `unavailable authenticator blocks reveal`() = runBlocking {
        val stableId = createApi()
        val (vm, prompt) = vm(stableId)
        kotlinx.coroutines.delay(50)

        prompt.autoResult = SensitiveActionResult.Unavailable
        var unavailableEvent: DeveloperDetailEvent? = null
        val job = kotlinx.coroutines.GlobalScope.launch {
            vm.events.collect { unavailableEvent = it }
        }
        vm.reveal(SensitiveAction.REVEAL_API_SECRET, "apiSecret")
        kotlinx.coroutines.delay(50)
        job.cancel()

        assertNull(vm.revealedValue("apiSecret"))
        assertTrue(vm.uiState.value.authUnavailable)
        assertTrue(unavailableEvent is DeveloperDetailEvent.AuthUnavailable)
    }

    @Test
    fun `cancel clears authPending`() = runBlocking {
        val stableId = createApi()
        val (vm, prompt) = vm(stableId)
        kotlinx.coroutines.delay(50)

        vm.reveal(SensitiveAction.REVEAL_API_SECRET, "apiSecret")
        assertTrue(vm.uiState.value.authPending)
        prompt.deliver(SensitiveActionResult.Cancelled)
        assertFalse(vm.uiState.value.authPending)
        assertTrue(vm.uiState.value.authCancelled)
        assertNull(vm.revealedValue("apiSecret"))
    }
}
