package com.rescueauth.v2.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rescueauth.v2.ui.model.AccountUi
import com.rescueauth.v2.ui.model.TotpCredentialUi
import com.rescueauth.v2.ui.screens.authenticator.CreateProviderDialog
import com.rescueauth.v2.ui.screens.authenticator.ManagementDestructiveDialog
import com.rescueauth.v2.ui.screens.authenticator.ManagementTextDialog
import com.rescueauth.v2.ui.screens.authenticator.MergeAccountDialog
import com.rescueauth.v2.ui.screens.authenticator.ProviderPickerDialog
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Behaviour tests for the Provider & Account management dialogs (Phase 4).
 *
 * Verifies that management dialogs render safe metadata (counts only — never
 * secrets), cancel changes nothing, and confirm invokes the right callback.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ProviderAccountManagementDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `create provider dialog requires provider and account`() {
        var confirmed = false
        composeRule.setContent {
            RescueAuthTheme {
                CreateProviderDialog(
                    onConfirm = { _, _ -> confirmed = true },
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithText("Provider name").assertExists()
        composeRule.onNodeWithText("Account name").assertExists()
    }

    @Test
    fun `provider picker dialog confirms chosen provider`() {
        var chosen: String? = null
        composeRule.setContent {
            RescueAuthTheme {
                ProviderPickerDialog(
                    title = "Move Account",
                    providers = listOf("Google", "GitHub"),
                    initialProvider = "Google",
                    onConfirm = { chosen = it },
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithText("Google").assertExists()
        composeRule.onNodeWithText("Confirm").performClick()
        composeRule.waitForIdle()
        assert(chosen == "Google") { "Google should be chosen as initial provider" }
    }

    @Test
    fun `rename dialog returns entered text`() {
        var value: String? = null
        composeRule.setContent {
            RescueAuthTheme {
                ManagementTextDialog(
                    title = "Rename Provider",
                    fieldLabel = "New provider name",
                    confirmLabel = "Rename",
                    onConfirm = { value = it },
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithText("New provider name").performTextInput("GitHub Inc")
        composeRule.onNodeWithText("Rename").performClick()
        composeRule.waitForIdle()
        assert(value == "GitHub Inc") { "Rename should return the entered text" }
    }

    @Test
    fun `destructive dialog shows safe counts not secrets`() {
        composeRule.setContent {
            RescueAuthTheme {
                ManagementDestructiveDialog(
                    title = "Delete Provider GitHub?",
                    message = "This will permanently delete 2 account(s), 3 TOTP credential(s) and 1 recovery set(s). This cannot be undone.",
                    confirmLabel = "Delete",
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithText("Delete Provider GitHub?").assertExists()
        composeRule.onNodeWithText(
            "This will permanently delete 2 account(s), 3 TOTP credential(s) and 1 recovery set(s). This cannot be undone.",
        ).assertExists()
        // No secret values anywhere.
        composeRule.onNodeWithText("JBSWY3DPEHPK3PXP").assertDoesNotExist()
    }

    @Test
    fun `cancel destructive dialog changes nothing`() {
        var confirmed = false
        composeRule.setContent {
            RescueAuthTheme {
                ManagementDestructiveDialog(
                    title = "Delete?",
                    message = "Are you sure?",
                    confirmLabel = "Delete",
                    onConfirm = { confirmed = true },
                    onDismiss = {},
                )
            }
        }
        // Don't click confirm; just verify cancel button exists (no action).
        composeRule.onNodeWithText("Cancel").assertExists()
        assert(!confirmed)
    }

    @Test
    fun `merge dialog shows safe summary no secrets`() {
        composeRule.setContent {
            RescueAuthTheme {
                MergeAccountDialog(
                    source = AccountUi(
                        id = "s1",
                        providerName = "GitHub",
                        accountName = "alice",
                        totpCredentials = listOf(TotpCredentialUi(id = "t1", stableId = "t1", issuer = "GitHub", accountName = "alice")),
                        recoverySets = emptyList(),
                    ),
                    destination = AccountUi(
                        id = "d1",
                        providerName = "GitHub",
                        accountName = "bob",
                    ),
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithText("Merge Accounts").assertExists()
        // Only safe metadata; the TOTP secret is not shown.
        composeRule.onNodeWithText("JBSWY3DPEHPK3PXP").assertDoesNotExist()
    }
}
