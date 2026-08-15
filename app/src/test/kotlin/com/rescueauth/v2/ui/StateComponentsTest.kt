package com.rescueauth.v2.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rescueauth.v2.ui.components.EmptyState
import com.rescueauth.v2.ui.components.ErrorState
import com.rescueauth.v2.ui.components.LoadingState
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Behaviour tests for the reusable Empty / Loading / Error states.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class StateComponentsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyStateShowsTitleAndBody() {
        composeRule.setContent {
            RescueAuthTheme {
                EmptyState(title = "No accounts yet", body = "Add a service and account")
            }
        }
        composeRule.onNodeWithText("No accounts yet").assertIsDisplayed()
        composeRule.onNodeWithText("Add a service and account").assertIsDisplayed()
    }

    @Test
    fun loadingStateShowsProgressIndicator() {
        composeRule.setContent {
            RescueAuthTheme {
                LoadingState()
            }
        }
        composeRule.onNodeWithTag("loading_indicator").assertExists()
    }

    @Test
    fun errorStateWithRetryInvokesCallback() {
        var retried = false
        composeRule.setContent {
            RescueAuthTheme {
                ErrorState(
                    title = "Something went wrong",
                    retryLabel = "Retry",
                    onRetry = { retried = true },
                )
            }
        }
        composeRule.onNodeWithText("Retry").performClick()
        composeRule.waitForIdle()
        assert(retried) { "Retry callback must be invoked" }
    }
}
