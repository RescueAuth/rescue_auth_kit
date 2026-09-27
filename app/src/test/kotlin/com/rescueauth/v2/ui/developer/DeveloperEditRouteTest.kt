package com.rescueauth.v2.ui.developer

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rescueauth.v2.database.RescueAuthDatabase
import com.rescueauth.v2.export.VaultKeyValue
import com.rescueauth.v2.repository.DeveloperRepository
import com.rescueauth.v2.repository.VaultRepository
import com.rescueauth.v2.security.FakeSensitiveActionPrompt
import com.rescueauth.v2.security.SensitiveActionGate
import com.rescueauth.v2.security.SensitiveActionResult
import com.rescueauth.v2.session.SecureSessionStateMachine
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The actual form route remains non-editable until the guarded loader has completed. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DeveloperEditRouteTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var db: RescueAuthDatabase
    private lateinit var model: DeveloperFormViewModel
    private lateinit var repo: DeveloperRepository
    private lateinit var prompt: FakeSensitiveActionPrompt
    private lateinit var id: String
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val visible = mutableStateOf(true)
    private var backs = 0
    private var saves = 0
    private var reads = 0

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), RescueAuthDatabase::class.java)
            .allowMainThreadQueries().build()
        val session = SecureSessionStateMachine().apply { beginAuthentication(); onAuthenticationSuccess() }
        repo = DeveloperRepository(VaultRepository(db, session), db, session)
        id = runBlocking { repo.createGenericSecret("Demo edit", null, listOf(VaultKeyValue("field", "fixture-value"))).stableId }
        prompt = FakeSensitiveActionPrompt()
        model = DeveloperFormViewModel(
            developerRepositoryProvider = { reads++; repo }, sessionState = session.state, scope = scope,
            sensitiveActionGate = SensitiveActionGate(prompt, session, { "Verify" }, { null }),
        )
        rule.setContent {
            RescueAuthTheme {
                if (visible.value) DeveloperFormRoute(
                    editingStableId = id, initialType = DeveloperFormType.GENERIC_SECRET,
                    onBack = { backs++ }, onSaved = { saves++ }, formViewModel = model,
                )
            }
        }
        rule.waitUntil(5_000) { prompt.startedRequests.size == 1 }
    }

    @After fun cleanup() { scope.cancel(); db.close() }

    @Test fun directEditRouteDoesNotRenderAFormBeforeVerification() {
        rule.onNodeWithTag("page_action_bar").assertDoesNotExist()
        rule.onNodeWithText("Title").assertDoesNotExist()
        assertEquals(0, reads)
        rule.runOnIdle { prompt.deliver(SensitiveActionResult.Success(prompt.startedRequests.single())) }
        rule.waitUntil(5_000) { model.formState.value.editingStableId == id }
        rule.onNodeWithText("Demo edit").assertIsDisplayed()
        rule.onNodeWithTag("developer_form_next").assertIsDisplayed()
        assertEquals(0, backs)
    }

    @Test fun cancellingVerificationReturnsWithoutOpeningTheEditor() {
        rule.runOnIdle { prompt.deliver(SensitiveActionResult.Cancelled) }
        rule.waitUntil(5_000) { backs == 1 }
        rule.onNodeWithTag("page_action_bar").assertDoesNotExist()
        assertEquals(0, reads)
        assertNull(model.formState.value.editingStableId)
    }

    @Test fun leavingTheRouteIgnoresLateSuccessAndDoesNotPopAgain() {
        val request = prompt.startedRequests.single()
        val callback = prompt.lastCallback!!
        rule.runOnIdle { visible.value = false }
        rule.waitForIdle()
        rule.runOnIdle { callback(SensitiveActionResult.Success(request)) }
        assertEquals(0, backs)
        assertEquals(0, reads)
        assertNull(model.formState.value.editingStableId)
    }

    @Test fun savingAnAuthorizedEditCompletesWithoutWaitingForAnUnhostedSnackbar() {
        rule.runOnIdle { prompt.deliver(SensitiveActionResult.Success(prompt.startedRequests.single())) }
        rule.waitUntil(5_000) { model.formState.value.editingStableId == id }
        rule.onNodeWithText("Demo edit").performTextReplacement("Updated demo")
        rule.onNodeWithTag("developer_form_next").performClick()
        rule.onNodeWithTag("developer_form_save").performClick()
        rule.waitUntil(5_000) { saves == 1 }
        assertEquals("Updated demo", runBlocking { repo.getByStableId(id)!!.title })
        assertEquals(1, prompt.startedRequests.size)
        assertNull(model.formState.value.editingStableId)
    }
}
