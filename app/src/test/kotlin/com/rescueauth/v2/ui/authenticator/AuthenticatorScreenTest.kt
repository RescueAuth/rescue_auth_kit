package com.rescueauth.v2.ui.authenticator

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
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
        composeRule.onNodeWithText("996 554").assertDoesNotExist()
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
        composeRule.onNodeWithText("996 554").assertDoesNotExist()
        composeRule.onNodeWithTag("account_row_a1").performClick()
        composeRule.onNodeWithText("996 554").assertIsDisplayed()
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
        composeRule.onNodeWithText("111 111").assertIsDisplayed()
        composeRule.onNodeWithText("2222 2222").performScrollTo().assertIsDisplayed()
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
        composeRule.onNodeWithText("111 111").assertDoesNotExist()
        composeRule.onNodeWithText("222 222").assertDoesNotExist()

        // Drilling into GitHub shows its account + code.
        composeRule.onNodeWithTag("provider_row_GitHub").performClick()
        composeRule.onNodeWithText("alice@example.com").assertIsDisplayed()
        composeRule.onNodeWithText("111 111").assertDoesNotExist()
        composeRule.onNodeWithTag("account_row_a1").performClick()
        composeRule.onNodeWithText("111 111").assertIsDisplayed()
        composeRule.onNodeWithText("222 222").assertDoesNotExist()
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
        composeRule.onNodeWithText("Add authenticator").performSemanticsAction(SemanticsActions.OnClick) { it() }
        composeRule.waitUntil(5_000) { added }
        org.junit.Assert.assertTrue(added)
        org.junit.Assert.assertFalse(created)
        composeRule.onNodeWithTag("vault_add_menu").performClick()
        composeRule.onNodeWithText("Create a service").performSemanticsAction(SemanticsActions.OnClick) { it() }
        composeRule.waitUntil(5_000) { created }
        org.junit.Assert.assertTrue(created)
    }

    @Test
    fun removedAccountRetainsBackAndCanRecoverAfterUndo() {
        val account = AccountUi("a1", "GitHub", "Personal")
        val loaded = AuthenticatorUiState(loading = false, providers = listOf(ProviderUi("p1", "GitHub", listOf(account))))
        val state = mutableStateOf(loaded)
        var backCount = 0
        composeRule.setContent {
            RescueAuthTheme {
                com.rescueauth.v2.ui.screens.authenticator.AuthenticatorScreen(
                    uiState = state.value, initialAccountId = account.id,
                    onBack = { backCount++ }, onAddClick = {},
                )
            }
        }
        composeRule.onNodeWithText("Personal").assertIsDisplayed()
        composeRule.runOnIdle { state.value = AuthenticatorUiState(loading = false) }
        composeRule.onNodeWithTag("vault_item_unavailable").assertIsDisplayed()
        composeRule.onNodeWithTag("vault_add_menu").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Back").performClick()
        org.junit.Assert.assertEquals(1, backCount)
        // A snackbar Undo restoring the same ID must restore the detail page,
        // without having silently reset the navigation selection to root.
        composeRule.runOnIdle { state.value = loaded }
        composeRule.onNodeWithText("Personal").assertIsDisplayed()
        composeRule.onNodeWithTag("vault_item_unavailable").assertDoesNotExist()
    }

    @Test
    fun missingProviderDoesNotPretendToBeTheRootDirectory() {
        var backedOut = false
        composeRule.setContent {
            RescueAuthTheme {
                com.rescueauth.v2.ui.screens.authenticator.AuthenticatorScreen(
                    uiState = AuthenticatorUiState(loading = false), initialProviderName = "Removed service",
                    onBack = { backedOut = true },
                )
            }
        }
        composeRule.onNodeWithTag("vault_item_unavailable").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back").performClick()
        org.junit.Assert.assertTrue(backedOut)
    }

}
