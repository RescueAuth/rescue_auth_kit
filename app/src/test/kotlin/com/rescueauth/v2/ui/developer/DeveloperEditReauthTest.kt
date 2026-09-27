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
import com.rescueauth.v2.security.SensitiveActionRequest
import com.rescueauth.v2.security.SensitiveActionResult
import com.rescueauth.v2.security.SensitiveActionTarget
import com.rescueauth.v2.session.SecureSessionStateMachine
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real editor/repository boundary with a fake system prompt; all fixture values are invented. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeveloperEditReauthTest {
    private lateinit var db: RescueAuthDatabase
    private lateinit var session: SecureSessionStateMachine
    private lateinit var repo: DeveloperRepository
    private lateinit var prompt: FakeSensitiveActionPrompt
    private lateinit var gate: SensitiveActionGate
    private lateinit var scope: CoroutineScope
    private var reads = 0

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), RescueAuthDatabase::class.java)
            .allowMainThreadQueries().build()
        session = SecureSessionStateMachine().apply { beginAuthentication(); onAuthenticationSuccess() }
        repo = DeveloperRepository(VaultRepository(db, session), db, session)
        prompt = FakeSensitiveActionPrompt()
        gate = SensitiveActionGate(prompt, session, { "Verify" }, { null })
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After fun cleanup() { scope.cancel(); db.close() }

    private fun model(provider: () -> DeveloperRepository? = { reads++; repo }) = DeveloperFormViewModel(
        developerRepositoryProvider = provider, sessionState = session.state, scope = scope, sensitiveActionGate = gate,
    )
    private suspend fun fixture() = repo.createGenericSecret("Demo", null, listOf(VaultKeyValue("field", "fixture-value"))).stableId
    private fun open(model: DeveloperFormViewModel, id: String) = scope.async(start = CoroutineStart.UNDISPATCHED) { model.beginEdit(id) }
    private fun succeed() = prompt.deliver(SensitiveActionResult.Success(prompt.startedRequests.last()))

    @Test fun `all five editors read protected payload only after verification`() = runBlocking {
        val ids = listOf(
            repo.createApiCredential("Demo API", null, "Service", "Account", "fixture-key", "fixture-value").stableId,
            repo.createSshKey(title = "Demo SSH", notes = null, keyName = "Demo", publicKey = "", privateKey = "fixture-private", passphrase = "fixture-phrase").stableId,
            fixture(),
            repo.createAndroidSigningKey(title = "Demo signing", notes = null, projectName = "Project", packageName = "com.example.demo",
                keystoreFileName = "demo.jks", keystoreBytes = byteArrayOf(1, 2, 3), storePassword = "fixture-store", keyAlias = "demo", keyPassword = "fixture-key").stableId,
            repo.createEnvironmentVariableSet("Demo variables", null, "Project", listOf(VaultKeyValue("VARIABLE", "fixture-value"))).stableId,
        )
        val model = model()
        ids.forEachIndexed { index, id ->
            val result = open(model, id)
            assertFalse(result.isCompleted)
            assertEquals(index, reads)
            assertNull(model.formState.value.editingStableId)
            val request = prompt.startedRequests.last()
            assertEquals(SensitiveAction.EDIT_DEVELOPER_ENTRY, request.action)
            assertEquals(id, (request.target as SensitiveActionTarget.DeveloperEdit).stableId)
            succeed()
            assertTrue(result.await())
            assertEquals(index + 1, reads)
            assertEquals(id, model.formState.value.editingStableId)
            assertFalse(gate.isAuthorizedFor(request))
        }
    }

    @Test fun `cancel failure and unavailable leave payload unread`() = runBlocking {
        val id = fixture()
        val model = model()
        listOf(SensitiveActionResult.Cancelled, SensitiveActionResult.Failed, SensitiveActionResult.Unavailable).forEach { denial ->
            val result = open(model, id)
            prompt.deliver(denial)
            assertFalse(result.await())
            assertEquals(0, reads)
            assertNull(model.formState.value.editingStableId)
        }
    }

    @Test fun `a reveal grant and a previous edit never authorize a new edit`() = runBlocking {
        val id = fixture()
        val reveal = SensitiveActionRequest(SensitiveAction.REVEAL_GENERIC_SECRET, SensitiveActionTarget.DeveloperField(id, "field:field"))
        gate.authorize(reveal) {}
        prompt.deliver(SensitiveActionResult.Success(reveal))
        val model = model()
        val first = open(model, id)
        assertEquals(2, prompt.startedRequests.size)
        assertEquals(0, reads)
        assertFalse(gate.isAuthorizedFor(reveal))
        succeed()
        assertTrue(first.await())
        val firstRequest = prompt.startedRequests.last()
        val second = open(model, id)
        assertEquals(3, prompt.startedRequests.size)
        assertNull(model.formState.value.editingStableId)
        assertNotEquals(firstRequest, prompt.startedRequests.last())
        succeed()
        assertTrue(second.await())
    }

    @Test fun `a result for another entry or operation cannot populate the editor`() = runBlocking {
        val id = fixture()
        val model = model()
        repeat(2) { variant ->
            val result = open(model, id)
            val original = prompt.startedRequests.last()
            val wrong = if (variant == 0) original.copy(target = SensitiveActionTarget.DeveloperEdit("other", UUID.randomUUID().toString()))
                else original.copy(action = SensitiveAction.COPY_GENERIC_SECRET)
            prompt.deliver(SensitiveActionResult.Success(wrong))
            assertFalse(result.await())
            assertEquals(0, reads)
            assertNull(model.formState.value.editingStableId)
        }
    }

    @Test fun `duplicate requests do not replace the pending target`() = runBlocking {
        val original = fixture()
        val other = fixture()
        val model = model()
        val result = open(model, original)
        assertFalse(model.beginEdit(other))
        assertEquals(1, prompt.startedRequests.size)
        succeed()
        assertTrue(result.await())
        assertEquals(original, model.formState.value.editingStableId)
    }

    @Test fun `leaving the editor cancels the loader and ignores late success`() = runBlocking {
        val model = model()
        val result = open(model, fixture())
        val request = prompt.startedRequests.last()
        val lateCallback = prompt.lastCallback!!
        model.dismiss()
        assertTrue(result.isCancelled)
        lateCallback(SensitiveActionResult.Success(request))
        assertEquals(0, reads)
        assertNull(model.formState.value.editingStableId)
        assertFalse(gate.isAuthorizedFor(request))
    }

    @Test fun `session lock clears an opened editor and prevents saving`() = runBlocking {
        val id = fixture()
        val model = model()
        val result = open(model, id)
        succeed()
        assertTrue(result.await())
        session.lock()
        assertNull(model.formState.value.editingStableId)
        assertTrue(model.formState.value.fields.all { it.second.isEmpty() })
        assertFalse(model.submit())
    }

    @Test fun `locking while authentication is pending cancels the edit`() = runBlocking {
        val model = model()
        val result = open(model, fixture())
        val request = prompt.startedRequests.last()
        val lateCallback = prompt.lastCallback!!
        session.lock()
        assertTrue(result.isCancelled)
        lateCallback(SensitiveActionResult.Success(request))
        assertEquals(0, reads)
        assertNull(model.formState.value.editingStableId)
    }

    @Test fun `lifecycle cancellation finishes the waiting edit instead of leaving it loading`() = runBlocking {
        val model = model()
        val result = open(model, fixture())
        gate.onLifecyclePause()
        assertFalse(result.await())
        assertEquals(0, reads)
    }

    @Test fun `restored editor needs another prompt and cannot reuse an old attempt`() = runBlocking {
        val id = fixture()
        val old = model()
        val first = open(old, id)
        val firstRequest = prompt.startedRequests.last()
        succeed()
        assertTrue(first.await())
        old.dismiss()
        val restored = model()
        val next = open(restored, id)
        assertEquals(2, prompt.startedRequests.size)
        prompt.deliver(SensitiveActionResult.Success(firstRequest))
        assertFalse(next.await())
        assertNull(restored.formState.value.editingStableId)
        assertEquals(1, reads)
    }

    @Test fun `a rejected prompt does not read or leave a waiting edit`() = runBlocking {
        prompt.startResult = false
        val model = model()
        assertFalse(model.beginEdit(fixture()))
        assertEquals(0, reads)
        prompt.startResult = true
        val retry = open(model, fixture())
        succeed()
        assertTrue(retry.await())
    }

    @Test fun `a busy gate preserves the other pending operation`() = runBlocking {
        val id = fixture()
        val reveal = SensitiveActionRequest(SensitiveAction.REVEAL_GENERIC_SECRET, SensitiveActionTarget.DeveloperField(id, "field:field"))
        gate.authorize(reveal) {}
        assertFalse(model().beginEdit(id))
        assertEquals(reveal, gate.pendingRequestOrNull())
        assertEquals(1, prompt.startedRequests.size)
        assertEquals(0, reads)
    }

    @Test fun `lock between authorization and repository read cannot publish stale plaintext`() = runBlocking {
        val id = fixture()
        val model = model { reads++; session.lock(); repo }
        val result = open(model, id)
        succeed()
        assertFalse(result.await())
        assertNull(model.formState.value.editingStableId)
    }
}
