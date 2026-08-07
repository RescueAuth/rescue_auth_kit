package com.rescueauth.v2.ui.authenticator

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * UI behaviour tests for the production Authenticator screen state model:
 * empty state, real list state and countdown values are rendered from
 * [AuthenticatorUiState] without touching Room or the repository.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AuthenticatorScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyStateShowsEmptyMessage() {
        composeRule.setContent {
            RescueAuthTheme {
                com.rescueauth.v2.ui.screens.authenticator.AuthenticatorScreen(
                    uiState = AuthenticatorUiState(loading = false, totpCards = emptyList()),
                )
            }
        }
        composeRule.onNodeWithText("No accounts yet").assertIsDisplayed()
    }

    @Test
    fun realListShowsIssuerAccountCodeAndCountdown() {
        composeRule.setContent {
            RescueAuthTheme {
                com.rescueauth.v2.ui.screens.authenticator.AuthenticatorScreen(
                    uiState = AuthenticatorUiState(
                        loading = false,
                        totpCards = listOf(
                            TotpCardUi(
                                credentialId = "t1",
                                stableId = "t1",
                                accountId = "a1",
                                issuer = "GitHub",
                                accountName = "alice@example.com",
                                algorithm = "SHA1",
                                digits = 6,
                                periodSeconds = 30,
                                currentCode = "996554",
                                remainingSeconds = 24,
                                progressFraction = 0.8f,
                            ),
                        ),
                    ),
                    onAddClick = {},
                )
            }
        }
        composeRule.onNodeWithText("GitHub").assertIsDisplayed()
        composeRule.onNodeWithText("alice@example.com").assertIsDisplayed()
        composeRule.onNodeWithText("996554").assertIsDisplayed()
        composeRule.onNodeWithText("24").assertIsDisplayed()
    }
}
