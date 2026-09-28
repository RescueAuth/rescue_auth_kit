package com.rescueauth.v2.ui.authenticator

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rescueauth.v2.ui.model.AccountUi
import com.rescueauth.v2.ui.model.ProviderUi
import com.rescueauth.v2.ui.model.RecoveryCodeSetUi
import com.rescueauth.v2.ui.model.TotpCredentialUi
import com.rescueauth.v2.ui.screens.authenticator.AuthenticatorScreen
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en")
class AccountPresentationTest {
    @get:Rule val rule = createComposeRule()
    private val credential = TotpCredentialUi("otp", "stable-otp", "GitHub", "Personal", currentCode = "123456", remainingSeconds = 24, progressFraction = .8f)
    private val account = AccountUi("personal", "GitHub", "Personal", totpCredentials = listOf(credential),
        recoverySets = listOf(RecoveryCodeSetUi("recovery-a", "First", 2, 10), RecoveryCodeSetUi("recovery-b", "Second", 1, 10)))
    private fun state(value: AccountUi = account) = AuthenticatorUiState(loading = false,
        providers = listOf(ProviderUi("github", "GitHub", listOf(value))))

    @Test fun detailOmitsProviderAndKeepsAccountManagementReachable() {
        val actions = mutableListOf<String>()
        rule.setContent { RescueAuthTheme { AuthenticatorScreen(uiState = state(), initialAccountId = account.id,
            onTogglePin = { actions += "pin:${it.id}" }, onRenameAccount = { actions += "rename:${it.id}" },
            onMoveAccount = { actions += "move:${it.id}" }, onMergeAccount = { actions += "merge:${it.id}" },
            onDeleteAccount = { actions += "delete:${it.id}" }) } }
        rule.onAllNodesWithText("Personal").assertCountEquals(1)
        rule.onNodeWithText("GitHub").assertDoesNotExist()
        for (label in listOf("Pin", "Rename Account", "Move", "Merge", "Delete Account")) {
            rule.onNodeWithContentDescription("Actions").performClick()
            rule.onNodeWithText(label).performClick()
        }
        assertEquals(listOf("pin:personal", "rename:personal", "move:personal", "merge:personal", "delete:personal"), actions)
    }

    @Test fun groupedCodeCopiesLatestRawValueAndOriginalIdentity() {
        val current = mutableStateOf(state())
        val copies = mutableListOf<TotpCardUi>()
        rule.setContent { RescueAuthTheme { AuthenticatorScreen(uiState = current.value, initialAccountId = account.id,
            onCopyClick = { copies += it }) } }
        rule.onNodeWithText("123 456").assertIsDisplayed()
        rule.onNodeWithTag("totp_copy_otp").performClick()
        rule.runOnIdle { current.value = state(account.copy(totpCredentials = listOf(credential.copy(currentCode = "87654321", digits = 8)))) }
        rule.onNodeWithText("8765 4321").assertIsDisplayed()
        rule.onNodeWithTag("totp_copy_otp").performClick()
        assertEquals(listOf("123456", "87654321"), copies.map { it.currentCode })
        assertTrue(copies.all { it.credentialId == "otp" && it.stableId == "stable-otp" && it.accountId == "personal" })
    }

    @Test fun unavailableCodeCannotCopyPlaceholderButCanStillBeDeleted() {
        var copied = false; var deleted: String? = null
        rule.setContent { RescueAuthTheme { AuthenticatorScreen(uiState = state(account.copy(totpCredentials = listOf(credential.copy(currentCode = null)))),
            initialAccountId = account.id, onCopyClick = { copied = true }, onDeleteClick = { deleted = it.credentialId }) } }
        rule.onNodeWithTag("totp_copy_otp").assertIsNotEnabled()
        rule.onNodeWithTag("totp_actions_otp").performClick()
        rule.onNodeWithText("Delete").performClick()
        assertFalse(copied); assertEquals("otp", deleted)
    }

    @Test fun recoverySummaryUsesAvailableCodesAndOpensOwningAccount() {
        var opened: String? = null
        rule.setContent { RescueAuthTheme { AuthenticatorScreen(uiState = state(), initialAccountId = account.id,
            onOpenRecovery = { opened = it }) } }
        rule.onNodeWithText("2 sets · 17 available").assertIsDisplayed()
        rule.onNodeWithTag("account_recovery").performClick()
        assertEquals("personal", opened)
    }

    @Test fun providerDirectoryKeepsAccountAndCreateCallbacks() {
        var opened: String? = null; var added: String? = null
        rule.setContent { RescueAuthTheme { AuthenticatorScreen(uiState = state(), initialProviderName = "GitHub",
            onOpenAccount = { opened = it }, onAddAccount = { added = it }) } }
        // Callback contracts here; native AccountPresentationVisualTest verifies actual touch hit testing.
        val row = rule.onNodeWithTag("account_row_personal").performScrollTo().assertIsDisplayed()
        row.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        rule.runOnIdle { assertEquals("personal", opened) }
        rule.onNodeWithText("Add Account").assertDoesNotExist()
        rule.onNodeWithTag("global_add").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        rule.runOnIdle { assertEquals("GitHub", added) }
        rule.onNodeWithText("123 456").assertDoesNotExist()
    }
}
