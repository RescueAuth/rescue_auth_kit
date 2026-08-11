package com.rescueauth.v2.ui.authenticator

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rescueauth.v2.ui.model.AccountUi
import com.rescueauth.v2.ui.model.ProviderUi
import com.rescueauth.v2.ui.model.TotpCredentialUi
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * UI behaviour tests for the production Authenticator screen state model:
 * empty state, real list state and countdown values are rendered from
 * [AuthenticatorUiState] without touching Room or the repository.
 *
 * The new nested layout renders TOTP codes directly inside each Account card
 * (Provider → Account → TOTP code), so tests provide data through the
 * `providers` structure.
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
                        providers = listOf(
                            ProviderUi(
                                id = "provider:GitHub",
                                serviceName = "GitHub",
                                accounts = listOf(
                                    AccountUi(
                                        id = "a1",
                                        providerName = "GitHub",
                                        accountName = "alice@example.com",
                                        totpCredentials = listOf(
                                            TotpCredentialUi(
                                                id = "t1",
                                                stableId = "t1",
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
                                ),
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

    @Test
    fun multipleTotpUnderSameAccountAreAllShown() {
        composeRule.setContent {
            RescueAuthTheme {
                com.rescueauth.v2.ui.screens.authenticator.AuthenticatorScreen(
                    uiState = AuthenticatorUiState(
                        loading = false,
                        providers = listOf(
                            ProviderUi(
                                id = "provider:GitHub",
                                serviceName = "GitHub",
                                accounts = listOf(
                                    AccountUi(
                                        id = "a1",
                                        providerName = "GitHub",
                                        accountName = "alice@example.com",
                                        totpCredentials = listOf(
                                            TotpCredentialUi(
                                                id = "t1",
                                                stableId = "t1",
                                                issuer = "GitHub",
                                                accountName = "alice@example.com",
                                                algorithm = "SHA1",
                                                digits = 6,
                                                periodSeconds = 30,
                                                currentCode = "111111",
                                                remainingSeconds = 20,
                                                progressFraction = 0.6f,
                                            ),
                                            TotpCredentialUi(
                                                id = "t2",
                                                stableId = "t2",
                                                issuer = "GitHub",
                                                accountName = "alice@example.com",
                                                algorithm = "SHA256",
                                                digits = 8,
                                                periodSeconds = 30,
                                                currentCode = "22222222",
                                                remainingSeconds = 20,
                                                progressFraction = 0.6f,
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                    onAddClick = {},
                )
            }
        }
        // Both TOTP codes should be visible — no silent dropping.
        composeRule.onNodeWithText("111111").assertIsDisplayed()
        composeRule.onNodeWithText("22222222").assertIsDisplayed()
    }

    @Test
    fun multipleProvidersAreGroupedCorrectly() {
        composeRule.setContent {
            RescueAuthTheme {
                com.rescueauth.v2.ui.screens.authenticator.AuthenticatorScreen(
                    uiState = AuthenticatorUiState(
                        loading = false,
                        providers = listOf(
                            ProviderUi(
                                id = "provider:GitHub",
                                serviceName = "GitHub",
                                accounts = listOf(
                                    AccountUi(
                                        id = "a1",
                                        providerName = "GitHub",
                                        accountName = "alice@example.com",
                                        totpCredentials = listOf(
                                            TotpCredentialUi(
                                                id = "t1",
                                                stableId = "t1",
                                                issuer = "GitHub",
                                                accountName = "alice@example.com",
                                                algorithm = "SHA1",
                                                digits = 6,
                                                periodSeconds = 30,
                                                currentCode = "111111",
                                                remainingSeconds = 20,
                                                progressFraction = 0.6f,
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                            ProviderUi(
                                id = "provider:Google",
                                serviceName = "Google",
                                accounts = listOf(
                                    AccountUi(
                                        id = "a2",
                                        providerName = "Google",
                                        accountName = "bob@example.com",
                                        totpCredentials = listOf(
                                            TotpCredentialUi(
                                                id = "t2",
                                                stableId = "t2",
                                                issuer = "Google",
                                                accountName = "bob@example.com",
                                                algorithm = "SHA256",
                                                digits = 6,
                                                periodSeconds = 30,
                                                currentCode = "222222",
                                                remainingSeconds = 15,
                                                progressFraction = 0.5f,
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                    onAddClick = {},
                )
            }
        }
        composeRule.onNodeWithText("GitHub").assertIsDisplayed()
        composeRule.onNodeWithText("Google").assertExists()
        composeRule.onNodeWithText("111111").assertIsDisplayed()
        composeRule.onNodeWithText("222222").assertExists()
    }
}
