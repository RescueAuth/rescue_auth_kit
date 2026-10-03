package com.rescueauth.v2.ui.authenticator

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rescueauth.v2.session.SecureSessionStateMachine
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en")
class InactiveAuthenticatorPageTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    @After fun close() { scope.cancel() }

    @Test fun inactiveRootPausesTickingAndHidesDialogsThenResumes() {
        val reads = AtomicInteger()
        val vm = AuthenticatorViewModel(repositoryProvider = { null }, recoveryRepositoryProvider = { null },
            managementRepositoryProvider = { null }, sessionState = MutableStateFlow(SecureSessionStateMachine.State.UNLOCKED),
            clock = AuthenticatorViewModel.Clock { reads.incrementAndGet().toLong() }, scope = scope)
        val baseline = reads.get()
        val active = mutableStateOf(false)
        val add = AuthenticatorAddState().apply { providerDialogVisible = true }
        val editor = AccountAddViewModel({ null }, MutableStateFlow(SecureSessionStateMachine.State.UNLOCKED), scope)
        rule.setContent { RescueAuthTheme { AuthenticatorRoute(viewModel = vm, addViewModel = editor, addState = add, isActive = active.value) } }
        rule.onNodeWithTag("account_add_sheet").assertDoesNotExist()
        assertEquals(baseline, reads.get())
        rule.runOnIdle { active.value = true; add.providerDialogVisible = true }
        rule.onNodeWithTag("account_add_sheet").assertIsDisplayed()
        assertTrue(reads.get() > baseline)
        rule.runOnIdle { active.value = false }
        rule.waitForIdle()
        val paused = reads.get()
        Thread.sleep(1_150)
        assertEquals("An offscreen page must not keep its one-second ticker", paused, reads.get())
        rule.onNodeWithTag("account_add_sheet").assertDoesNotExist()
        rule.runOnIdle { active.value = true }
        rule.onNodeWithTag("account_add_sheet").assertDoesNotExist()
        rule.runOnIdle { add.providerDialogVisible = true }
        rule.onNodeWithTag("account_add_sheet").assertIsDisplayed()
        assertTrue("Reactivation must use a fresh, uncancelled ticker scope", reads.get() > paused)
    }
}
