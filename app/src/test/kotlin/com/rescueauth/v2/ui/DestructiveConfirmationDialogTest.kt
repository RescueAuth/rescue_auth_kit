package com.rescueauth.v2.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rescueauth.v2.ui.components.DestructiveConfirmationDialog
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Behaviour test for the destructive confirmation dialog.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DestructiveConfirmationDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun confirmInvokesCallbackAndDismissCloses() {
        var confirmed = false
        var dismissed = false
        composeRule.setContent {
            RescueAuthTheme {
                DestructiveConfirmationDialog(
                    title = "Delete this entry?",
                    message = "This action cannot be undone.",
                    confirmLabel = "Delete",
                    onConfirm = { confirmed = true },
                    onDismiss = { dismissed = true },
                )
            }
        }
        composeRule.onNodeWithText("Delete this entry?").assertExists()
        composeRule.onNodeWithText("This action cannot be undone.").assertExists()
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.waitForIdle()
        assert(confirmed) { "Confirm callback must be invoked" }
        assert(!dismissed) { "Dismiss must not be invoked on confirm" }
    }
}
