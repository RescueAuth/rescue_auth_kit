package com.rescueauth.v2.ui.developer

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rescueauth.v2.domain.DeletedDeveloperEntrySnapshot
import com.rescueauth.v2.domain.DeveloperEntryType
import com.rescueauth.v2.session.SecureSessionStateMachine
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en")
class InactiveDeveloperPageTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    @After fun clear() { scope.cancel(); DeveloperUndoStore.clear() }

    @Test fun retainedInactivePageDoesNotShowOrExpireUndo() {
        val vm = DeveloperListViewModel({ null }, MutableStateFlow(SecureSessionStateMachine.State.UNLOCKED), scope)
        val pending = DeletedDeveloperEntrySnapshot("review", DeveloperEntryType.GENERIC_SECRET, "Review entry",
            null, "2026-09-29T00:00:00Z", "2026-09-29T00:00:00Z", 0, null)
        DeveloperUndoStore.store(pending)
        val active = mutableStateOf(false)
        rule.setContent { RescueAuthTheme { DeveloperRoute(viewModel = vm, isActive = active.value) } }
        rule.mainClock.advanceTimeBy(5_000)
        rule.onNodeWithText("Developer entry deleted").assertDoesNotExist()
        assertSame(pending, DeveloperUndoStore.pending)
        rule.runOnIdle { active.value = true }
        rule.onNodeWithText("Developer entry deleted").assertIsDisplayed()
        rule.runOnIdle { active.value = false }
        rule.mainClock.advanceTimeBy(5_000)
        assertSame(pending, DeveloperUndoStore.pending)
    }
}
