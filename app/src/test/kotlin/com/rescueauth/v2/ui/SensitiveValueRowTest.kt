package com.rescueauth.v2.ui

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rescueauth.v2.ui.components.SensitiveValueRow
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Component behaviour + accessibility-semantics tests for the sensitive value
 * row (hidden/reveal contract).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SensitiveValueRowTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun hiddenValueIsMaskedAndRevealIconHasContentDescription() {
        composeRule.setContent {
            RescueAuthTheme {
                SensitiveValueRow(label = "apiKey", value = "sk_live_abc123")
            }
        }
        // Value is masked when hidden.
        composeRule.onNodeWithText("••••••••").assertExists()
        // Reveal icon has a11y content description.
        composeRule.onNodeWithContentDescription("Reveal").assertExists()
    }

    @Test
    fun revealToggleShowsPlaintextValue() {
        val revealedState = androidx.compose.runtime.mutableStateOf(false)
        composeRule.setContent {
            RescueAuthTheme {
                SensitiveValueRow(
                    label = "apiKey",
                    value = "sk_live_abc123",
                    revealed = revealedState.value,
                    onRevealToggle = { revealedState.value = it },
                )
            }
        }
        composeRule.onNodeWithText("••••••••").assertExists()
        composeRule.onNodeWithContentDescription("Reveal").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("sk_live_abc123").assertExists()
        composeRule.onNodeWithContentDescription("Hide").assertExists()
    }

    @Test
    fun revealToggleRespectsControllerAndCanHideAgain() {
        val revealedState = androidx.compose.runtime.mutableStateOf(true)
        composeRule.setContent {
            RescueAuthTheme {
                SensitiveValueRow(
                    label = "sshKey",
                    value = "PRIVATE-KEY",
                    revealed = revealedState.value,
                    onRevealToggle = { revealedState.value = it },
                )
            }
        }
        composeRule.onNodeWithText("PRIVATE-KEY").assertExists()
        composeRule.onNodeWithContentDescription("Hide").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("••••••••").assertExists()
    }
}
