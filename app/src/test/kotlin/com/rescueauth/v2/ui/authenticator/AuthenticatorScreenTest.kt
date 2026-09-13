package com.rescueauth.v2.ui.authenticator

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
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
 * empty state and the level-1 (Provider list) / level-2 (Account list)
 * logical-path navigation.
 *
 * The home screen presents the Provider list (level 1); tapping a Provider
 * drills into its Account list (level 2), where TOTP codes are shown inline
 * inside each Account card. Tests provide data through the `providers`
 * structure and drive navigation by clicking the Provider row.
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
    fun homeShowsProvidersOnlyNotCodes() {
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
        // Level 1: only the Provider name is shown, not the TOTP code.
        composeRule.onNodeWithText("GitHub").assertIsDisplayed()
        composeRule.onNodeWithText("alice@example.com").assertDoesNotExist()
        composeRule.onNodeWithText("996554").assertDoesNotExist()
    }

    @Test
    fun accountDirectoryKeepsCodesOnDedicatedAccountPage() {
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
        // Drill into the provider's account list.
        composeRule.onNodeWithTag("provider_row_GitHub").performClick()
        composeRule.onNodeWithText("alice@example.com").assertIsDisplayed()
        composeRule.onNodeWithText("996554").assertDoesNotExist()
        composeRule.onNodeWithTag("account_row_a1").performClick()
        composeRule.onNodeWithText("996554").assertIsDisplayed()
        composeRule.onNodeWithText("24").assertIsDisplayed()
    }

    @Test
    fun multipleTotpUnderSameAccountAreAllShownAfterDrillDown() {
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
        composeRule.onNodeWithTag("provider_row_GitHub").performClick()
        composeRule.onNodeWithTag("account_row_a1").performClick()
        // Every code remains reachable on its owning account page.
        composeRule.onNodeWithText("111111").assertIsDisplayed()
        composeRule.onNodeWithText("22222222").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun multipleProvidersAreListedOnHome() {
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
        // Home lists both providers as entry points (codes are not shown yet).
        composeRule.onNodeWithText("GitHub").assertIsDisplayed()
        composeRule.onNodeWithText("Google").assertExists()
        composeRule.onNodeWithText("111111").assertDoesNotExist()
        composeRule.onNodeWithText("222222").assertDoesNotExist()

        // Drilling into GitHub shows its account + code.
        composeRule.onNodeWithTag("provider_row_GitHub").performClick()
        composeRule.onNodeWithText("alice@example.com").assertIsDisplayed()
        composeRule.onNodeWithText("111111").assertDoesNotExist()
        composeRule.onNodeWithTag("account_row_a1").performClick()
        composeRule.onNodeWithText("111111").assertIsDisplayed()
        composeRule.onNodeWithText("222222").assertDoesNotExist()
    }

    @Test
    fun pinnedFilterKeepsTheProviderDirectoryCompact() {
        composeRule.setContent {
            RescueAuthTheme {
                com.rescueauth.v2.ui.screens.authenticator.AuthenticatorScreen(
                    uiState = AuthenticatorUiState(
                        loading = false,
                        providers = listOf(
                            ProviderUi(
                                id = "provider:GitHub",
                                serviceName = "GitHub",
                                accounts = listOf(AccountUi("pinned", "GitHub", "alice", isPinned = true)),
                            ),
                            ProviderUi(
                                id = "provider:Google",
                                serviceName = "Google",
                                accounts = listOf(AccountUi("other", "Google", "bob")),
                            ),
                        ),
                    ),
                )
            }
        }
        composeRule.onNodeWithText("Pinned").performClick()
        composeRule.onNodeWithText("GitHub").assertIsDisplayed()
        composeRule.onNodeWithText("Google").assertDoesNotExist()
    }
    @Test
    fun addMenuSeparatesCredentialAndServiceCreation() {
        var added = false
        var created = false
        composeRule.setContent {
            RescueAuthTheme {
                com.rescueauth.v2.ui.screens.authenticator.AuthenticatorScreen(
                    uiState = AuthenticatorUiState(loading = false),
                    onAddClick = { added = true },
                    onAddProviderClick = { created = true },
                )
            }
        }
        composeRule.onNodeWithTag("vault_add_menu").performClick()
        composeRule.onNodeWithText("Add authenticator").performClick()
        org.junit.Assert.assertTrue(added)
        org.junit.Assert.assertFalse(created)
        composeRule.onNodeWithTag("vault_add_menu").performClick()
        composeRule.onNodeWithText("Create a service").performClick()
        org.junit.Assert.assertTrue(created)
    }

}
