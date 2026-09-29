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

    private fun apiRevealRequest(stableId: String, fieldKey: String = "apiSecret") =
        com.rescueauth.v2.security.SensitiveActionRequest(
            action = SensitiveAction.REVEAL_API_SECRET,
            target = com.rescueauth.v2.security.SensitiveActionTarget.DeveloperField(stableId, fieldKey),
        )

    private fun apiCopyRequest(stableId: String, fieldKey: String = "apiSecret") =
        com.rescueauth.v2.security.SensitiveActionRequest(
            action = SensitiveAction.COPY_API_SECRET,
            target = com.rescueauth.v2.security.SensitiveActionTarget.DeveloperField(stableId, fieldKey),
        )

    @Test
    fun `reveal requires a fresh re-auth`() = runBlocking {
        val stableId = createApi()
        val (vm, prompt) = vm(stableId)
        kotlinx.coroutines.delay(50)

        vm.reveal(SensitiveAction.REVEAL_API_SECRET, "apiSecret")
        assertEquals(1, prompt.startedRequests.size)
        assertEquals(apiRevealRequest(stableId, "apiSecret"), prompt.startedRequests[0])
        // No value revealed yet.
        assertNull(vm.revealedValue("apiSecret"))

        prompt.deliver(SensitiveActionResult.Success(apiRevealRequest(stableId, "apiSecret")))
        kotlinx.coroutines.delay(50)
        assertEquals("secret-abc", vm.revealedValue("apiSecret"))
    }

    @Test
    fun `auth success reveals and hide removes visible state`() = runBlocking {
        val stableId = createApi()
        val (vm, prompt) = vm(stableId)
        kotlinx.coroutines.delay(50)

        vm.reveal(SensitiveAction.REVEAL_API_SECRET, "apiSecret")
        prompt.deliver(SensitiveActionResult.Success(apiRevealRequest(stableId, "apiSecret")))
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
        prompt.deliver(SensitiveActionResult.Success(apiRevealRequest(stableId, "apiSecret")))
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
        prompt.deliver(SensitiveActionResult.Success(apiRevealRequest(stableId, "apiSecret")))
        kotlinx.coroutines.delay(50)
        assertEquals("secret-abc", vm.revealedValue("apiSecret"))
        assertEquals(1, prompt.startedRequests.size)

        // Copy triggers a SECOND fresh re-auth (never bypassed by reveal).
        vm.copySecret(SensitiveAction.COPY_API_SECRET, "apiSecret", "apiSecret")
        assertEquals(2, prompt.startedRequests.size)
        assertEquals(apiCopyRequest(stableId, "apiSecret"), prompt.startedRequests[1])

        var copyEvent: DeveloperDetailEvent? = null
        val job = kotlinx.coroutines.GlobalScope.launch {
            vm.events.collect { copyEvent = it }
        }
        prompt.deliver(SensitiveActionResult.Success(apiCopyRequest(stableId, "apiSecret")))
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

    // ------------------------------------------------------------------
    // Security-boundary CR: reveal vs copy separation + target binding
    // ------------------------------------------------------------------

    @Test
    fun `reveal authorization cannot authorize copy in the ViewModel`() = runBlocking {
        val stableId = createApi()
        val (vm, prompt) = vm(stableId)
        kotlinx.coroutines.withTimeout(5_000) { vm.uiState.first { !it.loading } }

        // Reveal request is pending; the prompt succeeds for reveal.
        vm.reveal(SensitiveAction.REVEAL_API_SECRET, "apiSecret")
        prompt.deliver(SensitiveActionResult.Success(apiRevealRequest(stableId, "apiSecret")))
        // Room completes on its query executor; a fixed 50 ms sleep races under build load.
        kotlinx.coroutines.withTimeout(5_000) {
            while (vm.revealedValue("apiSecret") == null) kotlinx.coroutines.delay(10)
        }
        assertEquals("secret-abc", vm.revealedValue("apiSecret"))

        // A copy request issued while the reveal prompt was pending was never
        // accepted; a later copy needs its own fresh re-auth. The reveal
        // authorization is consumed by the reveal only.
        var copyEvent: DeveloperDetailEvent? = null
        val job = kotlinx.coroutines.GlobalScope.launch {
            vm.events.collect { copyEvent = it }
        }
        vm.copySecret(SensitiveAction.COPY_API_SECRET, "apiSecret", "apiSecret")
        assertEquals(2, prompt.startedRequests.size)
        assertEquals(apiCopyRequest(stableId, "apiSecret"), prompt.startedRequests[1])
        // Without delivering the copy auth, no copy event fires.
        kotlinx.coroutines.delay(50)
        job.cancel()
        assertTrue(copyEvent !is DeveloperDetailEvent.CopySecret)
    }

    @Test
    fun `reveal generic field A auth success cannot reveal field B`() = runBlocking {
        val stableId = runBlocking {
            repo.createGenericSecret(
                title = "Generic",
                notes = null,
                fields = listOf(
                    VaultKeyValue("A", "secret-a"),
                    VaultKeyValue("B", "secret-b"),
                ),
            ).stableId
        }
        val (vm, prompt) = vm(stableId)
        kotlinx.coroutines.delay(50)

        vm.reveal(SensitiveAction.REVEAL_GENERIC_SECRET, "field:A")
        assertEquals(1, prompt.startedRequests.size)
        assertEquals(
            com.rescueauth.v2.security.SensitiveActionRequest(
                action = SensitiveAction.REVEAL_GENERIC_SECRET,
                target = com.rescueauth.v2.security.SensitiveActionTarget.DeveloperField(stableId, "field:A"),
            ),
            prompt.startedRequests[0],
        )
        // Deliver success for field A.
        prompt.deliver(
            SensitiveActionResult.Success(
                com.rescueauth.v2.security.SensitiveActionRequest(
                    action = SensitiveAction.REVEAL_GENERIC_SECRET,
                    target = com.rescueauth.v2.security.SensitiveActionTarget.DeveloperField(stableId, "field:A"),
                ),
            ),
        )
        kotlinx.coroutines.delay(50)
        assertEquals("secret-a", vm.revealedValue("field:A"))
        // Field B was never revealed.
        assertNull(vm.revealedValue("field:B"))
    }

    @Test
    fun `copy generic field A auth success cannot copy field B`() = runBlocking {
        val stableId = runBlocking {
            repo.createGenericSecret(
                title = "Generic",
                notes = null,
                fields = listOf(
                    VaultKeyValue("A", "secret-a"),
                    VaultKeyValue("B", "secret-b"),
                ),
            ).stableId
        }
        val (vm, prompt) = vm(stableId)
        kotlinx.coroutines.delay(50)

        var copyEvent: DeveloperDetailEvent? = null
        val job = kotlinx.coroutines.GlobalScope.launch {
            vm.events.collect { copyEvent = it }
        }

        // Request copy of field A only.
        vm.copySecret(SensitiveAction.COPY_GENERIC_SECRET, "field:A", "generic")
        assertEquals(1, prompt.startedRequests.size)
        assertEquals(
            com.rescueauth.v2.security.SensitiveActionRequest(
                action = SensitiveAction.COPY_GENERIC_SECRET,
                target = com.rescueauth.v2.security.SensitiveActionTarget.DeveloperField(stableId, "field:A"),
            ),
            prompt.startedRequests[0],
        )
        // Deliver success for field A.
        prompt.deliver(
            SensitiveActionResult.Success(
                com.rescueauth.v2.security.SensitiveActionRequest(
                    action = SensitiveAction.COPY_GENERIC_SECRET,
                    target = com.rescueauth.v2.security.SensitiveActionTarget.DeveloperField(stableId, "field:A"),
                ),
            ),
        )
        kotlinx.coroutines.delay(50)
        job.cancel()
        assertTrue(copyEvent is DeveloperDetailEvent.CopySecret)
        assertEquals("secret-a", (copyEvent as DeveloperDetailEvent.CopySecret).value)
    }

    @Test
    fun `ssh passphrase reveal auth cannot authorize passphrase copy`() = runBlocking {
        val stableId = runBlocking {
            repo.createSshKey(
                title = "SSH", notes = null,
                keyName = "work", publicKey = "pub",
                privateKey = "priv-key", passphrase = "pass-secret",
            ).stableId
        }
        val (vm, prompt) = vm(stableId)
        kotlinx.coroutines.delay(50)

        // Reveal passphrase.
        vm.reveal(SensitiveAction.REVEAL_SSH_PASSPHRASE, "passphrase")
        assertEquals(1, prompt.startedRequests.size)
        assertEquals(
            com.rescueauth.v2.security.SensitiveActionRequest(
                action = SensitiveAction.REVEAL_SSH_PASSPHRASE,
                target = com.rescueauth.v2.security.SensitiveActionTarget.DeveloperField(stableId, "passphrase"),
            ),
            prompt.startedRequests[0],
        )
        prompt.deliver(
            SensitiveActionResult.Success(
                com.rescueauth.v2.security.SensitiveActionRequest(
                    action = SensitiveAction.REVEAL_SSH_PASSPHRASE,
                    target = com.rescueauth.v2.security.SensitiveActionTarget.DeveloperField(stableId, "passphrase"),
                ),
            ),
        )
        kotlinx.coroutines.delay(50)
        assertEquals("pass-secret", vm.revealedValue("passphrase"))

        // Copy passphrase requires its OWN copy re-auth — reveal auth does not
        // authorize copy.
        var copyEvent: DeveloperDetailEvent? = null
        val job = kotlinx.coroutines.GlobalScope.launch {
            vm.events.collect { copyEvent = it }
        }
        vm.copySecret(SensitiveAction.COPY_SSH_PASSPHRASE, "passphrase", "passphrase")
        assertEquals(2, prompt.startedRequests.size)
        assertEquals(
            com.rescueauth.v2.security.SensitiveActionRequest(
                action = SensitiveAction.COPY_SSH_PASSPHRASE,
                target = com.rescueauth.v2.security.SensitiveActionTarget.DeveloperField(stableId, "passphrase"),
            ),
            prompt.startedRequests[1],
        )
        // Only deliver the COPY success.
        prompt.deliver(
            SensitiveActionResult.Success(
                com.rescueauth.v2.security.SensitiveActionRequest(
                    action = SensitiveAction.COPY_SSH_PASSPHRASE,
                    target = com.rescueauth.v2.security.SensitiveActionTarget.DeveloperField(stableId, "passphrase"),
                ),
            ),
        )
        kotlinx.coroutines.delay(50)
        job.cancel()
        assertTrue(copyEvent is DeveloperDetailEvent.CopySecret)
        assertEquals("pass-secret", (copyEvent as DeveloperDetailEvent.CopySecret).value)
    }

    @Test
    fun `entry A request pending - auth success only reveals entry A field`() = runBlocking {
        val aId = createApi("entry-A")
        val bId = createApi("entry-B")
        // Create a second entry with a distinct secret.
        val bEntry = repo.editApiCredential(
            stableId = bId,
            title = "Other", notes = null,
            serviceName = "other", accountName = "bob",
            apiKey = "key-b", apiSecret = "secret-b",
        )
        assertEquals(bId, bEntry.stableId)

        // ViewModel bound to entry A.
        val (vm, prompt) = vm(aId)
        kotlinx.coroutines.delay(50)

        vm.reveal(SensitiveAction.REVEAL_API_SECRET, "apiSecret")
        assertEquals(1, prompt.startedRequests.size)
        // The request is bound to entry A.
        assertEquals(
            com.rescueauth.v2.security.SensitiveActionRequest(
                action = SensitiveAction.REVEAL_API_SECRET,
                target = com.rescueauth.v2.security.SensitiveActionTarget.DeveloperField(aId, "apiSecret"),
            ),
            prompt.startedRequests[0],
        )

        // Even if the gate somehow delivered a success for entry B (a stale
        // prompt), the ViewModel bound to A must NOT reveal A's value.
        prompt.deliver(
            SensitiveActionResult.Success(
                com.rescueauth.v2.security.SensitiveActionRequest(
                    action = SensitiveAction.REVEAL_API_SECRET,
                    target = com.rescueauth.v2.security.SensitiveActionTarget.DeveloperField(bId, "apiSecret"),
                ),
            ),
        )
        kotlinx.coroutines.delay(50)
        assertNull(vm.revealedValue("apiSecret"))
    }

    @Test
    fun `session lock invalidates pending target in ViewModel`() = runBlocking {
        val stableId = createApi()
        val (vm, prompt) = vm(stableId)
        kotlinx.coroutines.delay(50)

        vm.reveal(SensitiveAction.REVEAL_API_SECRET, "apiSecret")
        assertTrue(vm.uiState.value.authPending)

        // Lock while the reveal is pending.
        session.lock()
        // MainActivity invalidates the gate; the VM collector clears state.
        kotlinx.coroutines.delay(50)
        assertFalse(vm.uiState.value.authPending)
        assertNull(vm.revealedValue("apiSecret"))
    }
}
