package com.rescueauth.v2.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rescueauth.v2.ui.authenticator.RecoveryFormState
import com.rescueauth.v2.ui.screens.authenticator.RecoveryCodeEditorSheet
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** No credentials: verify the real modal's exit and cancellation, rather than a mocked delay. */
@RunWith(AndroidJUnit4::class)
class SheetDismissAnimationTest {
    @get:Rule val rule = createComposeRule()
    private val mounted = mutableStateOf(true)
    private var dismissals = 0

    private fun show() {
        rule.setContent {
            RescueAuthTheme {
                if (mounted.value) RecoveryCodeEditorSheet(
                    form = RecoveryFormState(), onTitleChange = {}, onValuesChange = {}, onSubmit = {},
                    onDismiss = { dismissals++; mounted.value = false },
                    modifier = Modifier.testTag("dismiss_sheet"),
                )
            }
        }
        rule.waitForIdle()
    }

    private fun cancel() = rule.onNodeWithText(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.getString(com.rescueauth.v2.R.string.common_cancel)).performSemanticsAction(SemanticsActions.OnClick) { it() }

    @Test fun cancelMovesTheSheetDownBeforeRemovingIt() {
        show()
        val start = rule.onNodeWithTag("recovery_editor_header").getUnclippedBoundsInRoot()
        rule.mainClock.autoAdvance = false
        cancel()
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(64)
        rule.waitForIdle()
        assertEquals("The dismiss callback must wait for the exit", 0, dismissals)
        rule.onNodeWithTag("dismiss_sheet").assertIsDisplayed()
        val moving = rule.onNodeWithTag("recovery_editor_header").getUnclippedBoundsInRoot()
        assertTrue("The sheet must move down during cancel", moving.top > start.top)
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals(1, dismissals)
        rule.onNodeWithTag("dismiss_sheet").assertDoesNotExist()
    }

    @Test fun leavingTheHostCancelsThePendingDismissCallback() {
        show()
        rule.mainClock.autoAdvance = false
        cancel()
        rule.runOnIdle { mounted.value = false }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals("A disposed sheet must not invoke a late callback", 0, dismissals)
    }

    @Test fun rapidCancelTapsOnlyDismissOnce() {
        show()
        rule.mainClock.autoAdvance = false
        cancel(); cancel()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals(1, dismissals)
    }
}
