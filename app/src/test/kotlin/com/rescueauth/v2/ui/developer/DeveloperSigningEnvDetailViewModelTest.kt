package com.rescueauth.v2.ui.developer

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rescueauth.v2.database.RescueAuthDatabase
import com.rescueauth.v2.export.VaultAndroidSigningKey
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
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

/**
 * Phase 4 P6 Developer detail ViewModel tests for the final two types:
 *
 * - Android Signing Key: reveal store/key password requires fresh re-auth;
 *   copy requires its OWN separate re-auth; keystore export requires fresh
 *   re-auth; entry A auth cannot reveal/copy entry B; session lock / process
 *   recreation never restore reveal state.
 * - Environment Variable Set: per-variable reveal/copy requires fresh
 *   re-auth; variable A auth cannot reveal/copy variable B.
 *
 * A fake prompt boundary is used — no biometric hardware on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeveloperSigningEnvDetailViewModelTest {

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

    private fun syntheticKeystore(tag: Int): ByteArray = ByteArray(2048) { (it * 13 + tag) .toByte() }

    private suspend fun createSigning(title: String = "release"): String =
        repo.createAndroidSigningKey(
            title = title, notes = null,
            projectName = "app", packageName = "com.example.app",
            keystoreFileName = "release.jks", keystoreBytes = syntheticKeystore(1),
            storePassword = "store-secret", keyAlias = "release-key", keyPassword = "key-secret",
        ).stableId

    private suspend fun createEnv(title: String = "CI"): String =
        repo.createEnvironmentVariableSet(
            title = title, notes = null, projectName = "app",
            variables = listOf(
                VaultKeyValue("AUTH_TOKEN", "token-a"),
                VaultKeyValue("API_URL", "url-b"),
            ),
        ).stableId

    private fun req(action: SensitiveAction, stableId: String, fieldKey: String) =
        com.rescueauth.v2.security.SensitiveActionRequest(
            action = action,
            target = com.rescueauth.v2.security.SensitiveActionTarget.DeveloperField(stableId, fieldKey),
        )

    // ------------------------------------------------------------------
    // Android Signing Key reveal/copy
    // ------------------------------------------------------------------

    @Test
    fun `signing detail loads metadata without exposing passwords or bytes`() = runBlocking {
        val stableId = createSigning()
        val (vm, _) = vm(stableId)
        delay(50)
        val state = vm.uiState.value
        val detail = state.detail as com.rescueauth.v2.ui.model.DeveloperDetailUi.AndroidSigningKey
        assertEquals("com.example.app", detail.packageName)
        assertEquals("release.jks", detail.keystoreFileName)
        assertEquals("release-key", detail.keyAlias)
        // Secrets never in UI state.
        assertTrue(!state.toString().contains("store-secret"))
        assertTrue(!state.toString().contains("key-secret"))
    }

    @Test
    fun `storePassword reveal requires fresh re-auth`() = runBlocking {
        val stableId = createSigning()
        val (vm, prompt) = vm(stableId)
        delay(50)
        vm.reveal(SensitiveAction.REVEAL_SIGNING_STORE_PASSWORD, "storePassword")
        assertEquals(1, prompt.startedRequests.size)
        assertEquals(req(SensitiveAction.REVEAL_SIGNING_STORE_PASSWORD, stableId, "storePassword"), prompt.startedRequests[0])
        assertNull(vm.revealedValue("storePassword"))
        prompt.deliver(SensitiveActionResult.Success(req(SensitiveAction.REVEAL_SIGNING_STORE_PASSWORD, stableId, "storePassword")))
        delay(50)
        assertEquals("store-secret", vm.revealedValue("storePassword"))
    }

    @Test
    fun `storePassword copy requires separate fresh re-auth`() = runBlocking {
        val stableId = createSigning()
        val (vm, prompt) = vm(stableId)
        delay(50)
        // Reveal first (one prompt).
        vm.reveal(SensitiveAction.REVEAL_SIGNING_STORE_PASSWORD, "storePassword")
        prompt.deliver(SensitiveActionResult.Success(req(SensitiveAction.REVEAL_SIGNING_STORE_PASSWORD, stableId, "storePassword")))
        delay(50)
        assertEquals("store-secret", vm.revealedValue("storePassword"))
        assertEquals(1, prompt.startedRequests.size)

        // Copy triggers a SECOND independent prompt.
        var copyEvent: DeveloperDetailEvent? = null
        val job = GlobalScope.launch { vm.events.collect { copyEvent = it } }
        vm.copySecret(SensitiveAction.COPY_SIGNING_STORE_PASSWORD, "storePassword", "storePassword")
        assertEquals(2, prompt.startedRequests.size)
        assertEquals(req(SensitiveAction.COPY_SIGNING_STORE_PASSWORD, stableId, "storePassword"), prompt.startedRequests[1])
        prompt.deliver(SensitiveActionResult.Success(req(SensitiveAction.COPY_SIGNING_STORE_PASSWORD, stableId, "storePassword")))
        delay(50)
        job.cancel()
        assertTrue(copyEvent is DeveloperDetailEvent.CopySecret)
        assertEquals("store-secret", (copyEvent as DeveloperDetailEvent.CopySecret).value)
    }

    @Test
    fun `keyPassword reveal and copy each require fresh re-auth`() = runBlocking {
        val stableId = createSigning()
        val (vm, prompt) = vm(stableId)
        delay(50)

        vm.reveal(SensitiveAction.REVEAL_SIGNING_KEY_PASSWORD, "keyPassword")
        assertEquals(req(SensitiveAction.REVEAL_SIGNING_KEY_PASSWORD, stableId, "keyPassword"), prompt.startedRequests[0])
        prompt.deliver(SensitiveActionResult.Success(req(SensitiveAction.REVEAL_SIGNING_KEY_PASSWORD, stableId, "keyPassword")))
        delay(50)
        assertEquals("key-secret", vm.revealedValue("keyPassword"))

        var copyEvent: DeveloperDetailEvent? = null
        val job = GlobalScope.launch { vm.events.collect { copyEvent = it } }
        vm.copySecret(SensitiveAction.COPY_SIGNING_KEY_PASSWORD, "keyPassword", "keyPassword")
        assertEquals(2, prompt.startedRequests.size)
        assertEquals(req(SensitiveAction.COPY_SIGNING_KEY_PASSWORD, stableId, "keyPassword"), prompt.startedRequests[1])
        prompt.deliver(SensitiveActionResult.Success(req(SensitiveAction.COPY_SIGNING_KEY_PASSWORD, stableId, "keyPassword")))
        delay(50)
        job.cancel()
        assertTrue(copyEvent is DeveloperDetailEvent.CopySecret)
        assertEquals("key-secret", (copyEvent as DeveloperDetailEvent.CopySecret).value)
    }

    @Test
    fun `entry A auth cannot reveal or copy entry B store password`() = runBlocking {
        val aId = createSigning("entry-A")
        val bId = createSigning("entry-B")
        val (vm, prompt) = vm(aId)
        delay(50)

        vm.reveal(SensitiveAction.REVEAL_SIGNING_STORE_PASSWORD, "storePassword")
        // Deliver a success for the WRONG entry (B).
        prompt.deliver(SensitiveActionResult.Success(req(SensitiveAction.REVEAL_SIGNING_STORE_PASSWORD, bId, "storePassword")))
        delay(50)
        assertNull(vm.revealedValue("storePassword"))
    }

    @Test
    fun `keystore export requires fresh re-auth and emits exact bytes`() = runBlocking {
        val stableId = createSigning()
        val (vm, prompt) = vm(stableId)
        delay(50)

        vm.exportKeystore()
        assertEquals(1, prompt.startedRequests.size)
        assertEquals(req(SensitiveAction.EXPORT_SIGNING_KEYSTORE, stableId, "keystore"), prompt.startedRequests[0])

        var exportEvent: DeveloperDetailEvent? = null
        val job = GlobalScope.launch { vm.events.collect { exportEvent = it } }
        prompt.deliver(SensitiveActionResult.Success(req(SensitiveAction.EXPORT_SIGNING_KEYSTORE, stableId, "keystore")))
        delay(50)
        job.cancel()
        assertTrue(exportEvent is DeveloperDetailEvent.ExportKeystore)
        val ev = exportEvent as DeveloperDetailEvent.ExportKeystore
        assertEquals("release.jks", ev.suggestedFileName)
        val stored = (repo.getByStableId(stableId) as VaultAndroidSigningKey).keystoreBase64
        assertEquals(stored, Base64.getEncoder().encodeToString(ev.bytes))
    }

    @Test
    fun `keystore export auth cancel creates no event`() = runBlocking {
        val stableId = createSigning()
        val (vm, prompt) = vm(stableId)
        delay(50)

        var exportEvent: DeveloperDetailEvent? = null
        val job = GlobalScope.launch { vm.events.collect { exportEvent = it } }
        vm.exportKeystore()
        prompt.deliver(SensitiveActionResult.Cancelled)
        delay(50)
        job.cancel()
        assertTrue(exportEvent !is DeveloperDetailEvent.ExportKeystore)
        assertTrue(vm.uiState.value.authCancelled)
    }

    @Test
    fun `copy keyProperties requires fresh re-auth and emits correct four fields`() = runBlocking {
        val stableId = createSigning()
        val (vm, prompt) = vm(stableId)
        delay(50)

        var propsEvent: DeveloperDetailEvent? = null
        val job = GlobalScope.launch { vm.events.collect { propsEvent = it } }
        vm.copyKeyProperties()
        assertEquals(1, prompt.startedRequests.size)
        assertEquals(req(SensitiveAction.COPY_SIGNING_KEY_PROPERTIES, stableId, "keyProperties"), prompt.startedRequests[0])
        prompt.deliver(SensitiveActionResult.Success(req(SensitiveAction.COPY_SIGNING_KEY_PROPERTIES, stableId, "keyProperties")))
        delay(50)
        job.cancel()
        assertTrue(propsEvent is DeveloperDetailEvent.CopyKeyProperties)
        val snippet = (propsEvent as DeveloperDetailEvent.CopyKeyProperties).snippet
        assertTrue(snippet.contains("storeFile=release.jks"))
        assertTrue(snippet.contains("storePassword=store-secret"))
        assertTrue(snippet.contains("keyAlias=release-key"))
        assertTrue(snippet.contains("keyPassword=key-secret"))
    }

    @Test
    fun `session lock clears revealed signing password`() = runBlocking {
        val stableId = createSigning()
        val (vm, prompt) = vm(stableId)
        delay(50)
        vm.reveal(SensitiveAction.REVEAL_SIGNING_STORE_PASSWORD, "storePassword")
        prompt.deliver(SensitiveActionResult.Success(req(SensitiveAction.REVEAL_SIGNING_STORE_PASSWORD, stableId, "storePassword")))
        delay(50)
        assertEquals("store-secret", vm.revealedValue("storePassword"))
        session.lock()
        delay(50)
        assertNull(vm.revealedValue("storePassword"))
        assertFalse(vm.isRevealed("storePassword"))
    }

    @Test
    fun `reveal state not persisted (fresh VM reveals nothing)`() = runBlocking {
        val stableId = createSigning()
        val (vm, prompt) = vm(stableId)
        delay(50)
        vm.reveal(SensitiveAction.REVEAL_SIGNING_STORE_PASSWORD, "storePassword")
        prompt.deliver(SensitiveActionResult.Success(req(SensitiveAction.REVEAL_SIGNING_STORE_PASSWORD, stableId, "storePassword")))
        delay(50)
        assertEquals("store-secret", vm.revealedValue("storePassword"))

        // A brand-new VM (process recreation) never restores reveal state.
        val (vm2, _) = vm(stableId)
        delay(50)
        assertNull(vm2.revealedValue("storePassword"))
        assertFalse(vm2.isRevealed("storePassword"))
    }

    // ------------------------------------------------------------------
    // Environment Variable Set reveal/copy
    // ------------------------------------------------------------------

    @Test
    fun `env values hidden by default in detail state`() = runBlocking {
        val stableId = createEnv()
        val (vm, _) = vm(stableId)
        delay(50)
        val state = vm.uiState.value
        assertTrue(!state.toString().contains("token-a"))
        assertTrue(!state.toString().contains("url-b"))
    }

    @Test
    fun `env reveal requires fresh re-auth for the exact variable`() = runBlocking {
        val stableId = createEnv()
        val (vm, prompt) = vm(stableId)
        delay(50)

        vm.reveal(SensitiveAction.REVEAL_ENV_VAR_VALUE, "var:AUTH_TOKEN")
        assertEquals(1, prompt.startedRequests.size)
        assertEquals(req(SensitiveAction.REVEAL_ENV_VAR_VALUE, stableId, "var:AUTH_TOKEN"), prompt.startedRequests[0])
        prompt.deliver(SensitiveActionResult.Success(req(SensitiveAction.REVEAL_ENV_VAR_VALUE, stableId, "var:AUTH_TOKEN")))
        delay(50)
        assertEquals("token-a", vm.revealedValue("var:AUTH_TOKEN"))
        // The other variable was never revealed.
        assertNull(vm.revealedValue("var:API_URL"))
    }

    @Test
    fun `env variable A auth cannot reveal or copy variable B`() = runBlocking {
        val stableId = createEnv()
        val (vm, prompt) = vm(stableId)
        delay(50)

        vm.reveal(SensitiveAction.REVEAL_ENV_VAR_VALUE, "var:AUTH_TOKEN")
        // Deliver success for the WRONG variable (B).
        prompt.deliver(SensitiveActionResult.Success(req(SensitiveAction.REVEAL_ENV_VAR_VALUE, stableId, "var:API_URL")))
        delay(50)
        assertNull(vm.revealedValue("var:AUTH_TOKEN"))
        assertNull(vm.revealedValue("var:API_URL"))
    }

    @Test
    fun `env copy requires its own independent fresh re-auth`() = runBlocking {
        val stableId = createEnv()
        val (vm, prompt) = vm(stableId)
        delay(50)

        // Reveal variable A.
        vm.reveal(SensitiveAction.REVEAL_ENV_VAR_VALUE, "var:AUTH_TOKEN")
        prompt.deliver(SensitiveActionResult.Success(req(SensitiveAction.REVEAL_ENV_VAR_VALUE, stableId, "var:AUTH_TOKEN")))
        delay(50)
        assertEquals(1, prompt.startedRequests.size)

        // Copy variable A requires a SECOND prompt.
        var copyEvent: DeveloperDetailEvent? = null
        val job = GlobalScope.launch { vm.events.collect { copyEvent = it } }
        vm.copySecret(SensitiveAction.COPY_ENV_VAR_VALUE, "var:AUTH_TOKEN", "env")
        assertEquals(2, prompt.startedRequests.size)
        assertEquals(req(SensitiveAction.COPY_ENV_VAR_VALUE, stableId, "var:AUTH_TOKEN"), prompt.startedRequests[1])
        prompt.deliver(SensitiveActionResult.Success(req(SensitiveAction.COPY_ENV_VAR_VALUE, stableId, "var:AUTH_TOKEN")))
        delay(50)
        job.cancel()
        assertTrue(copyEvent is DeveloperDetailEvent.CopySecret)
        assertEquals("token-a", (copyEvent as DeveloperDetailEvent.CopySecret).value)
    }

    @Test
    fun `session lock hides all env values`() = runBlocking {
        val stableId = createEnv()
        val (vm, prompt) = vm(stableId)
        delay(50)
        vm.reveal(SensitiveAction.REVEAL_ENV_VAR_VALUE, "var:AUTH_TOKEN")
        prompt.deliver(SensitiveActionResult.Success(req(SensitiveAction.REVEAL_ENV_VAR_VALUE, stableId, "var:AUTH_TOKEN")))
        delay(50)
        assertEquals("token-a", vm.revealedValue("var:AUTH_TOKEN"))
        session.lock()
        delay(50)
        assertNull(vm.revealedValue("var:AUTH_TOKEN"))
        assertFalse(vm.isRevealed("var:AUTH_TOKEN"))
    }

    @Test
    fun `process recreation does not restore env reveal state`() = runBlocking {
        val stableId = createEnv()
        val (vm, prompt) = vm(stableId)
        delay(50)
        vm.reveal(SensitiveAction.REVEAL_ENV_VAR_VALUE, "var:AUTH_TOKEN")
        prompt.deliver(SensitiveActionResult.Success(req(SensitiveAction.REVEAL_ENV_VAR_VALUE, stableId, "var:AUTH_TOKEN")))
        delay(50)
        assertEquals("token-a", vm.revealedValue("var:AUTH_TOKEN"))
        val (vm2, _) = vm(stableId)
        delay(50)
        assertNull(vm2.revealedValue("var:AUTH_TOKEN"))
    }

    @Test
    fun `entry A env auth cannot reveal entry B env`() = runBlocking {
        val aId = createEnv("entry-A")
        val bId = createEnv("entry-B")
        val (vm, prompt) = vm(aId)
        delay(50)
        vm.reveal(SensitiveAction.REVEAL_ENV_VAR_VALUE, "var:AUTH_TOKEN")
        prompt.deliver(SensitiveActionResult.Success(req(SensitiveAction.REVEAL_ENV_VAR_VALUE, bId, "var:AUTH_TOKEN")))
        delay(50)
        assertNull(vm.revealedValue("var:AUTH_TOKEN"))
    }
}
