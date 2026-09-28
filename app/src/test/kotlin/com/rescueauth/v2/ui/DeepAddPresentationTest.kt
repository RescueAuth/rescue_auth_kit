package com.rescueauth.v2.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rescueauth.v2.ui.authenticator.AuthenticatorUiState
import com.rescueauth.v2.ui.authenticator.RecoveryUiState
import com.rescueauth.v2.ui.developer.DeveloperRoute
import com.rescueauth.v2.ui.model.AccountUi
import com.rescueauth.v2.ui.model.DeveloperEntryType
import com.rescueauth.v2.ui.model.ProviderUi
import com.rescueauth.v2.ui.screens.authenticator.AuthenticatorScreen
import com.rescueauth.v2.ui.screens.authenticator.AccountAddKind
import com.rescueauth.v2.ui.screens.authenticator.RecoveryCodesScreen
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en")
class DeepAddPresentationTest {
    @get:Rule val rule = createComposeRule()
    private val personal = AccountUi("personal", "GitHub", "Personal")
    private val work = AccountUi("work", "GitHub", "Work")
    private fun state(accounts: List<AccountUi>) = AuthenticatorUiState(loading = false,
        providers = listOf(ProviderUi("github", "GitHub", accounts)))
    private fun click(tag: String) = rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick) { it() }

    @Test fun providerMenuTargetsTheSelectedAccountAndKeepsCreateAccount() {
        var created: String? = null; var recovery: String? = null; var credential: String? = null
        rule.setContent { RescueAuthTheme { AuthenticatorScreen(uiState = state(listOf(personal, work)), initialProviderName = "GitHub",
            onAddClick = {}, onAddAccount = { created = it }, onAddRecovery = { recovery = it },
            onAddCredentialToAccount = { credential = it }) } }
        rule.onNodeWithText("Add Account").assertDoesNotExist()
        rule.onNodeWithTag("vault_add_menu").assertDoesNotExist()
        click("global_add")
        rule.onNodeWithTag("add_provider").assertDoesNotExist()
        click("add_recovery")
        rule.runOnIdle { assertNull(recovery) }
        click("add_target_work")
        rule.runOnIdle { assertEquals("work", recovery) }
        click("global_add"); click("add_authenticator"); click("add_target_personal")
        rule.runOnIdle { assertEquals("personal", credential) }
        click("global_add"); click("add_account")
        rule.runOnIdle { assertEquals("GitHub", created) }
    }

    @Test fun singleAccountSkipsRecipientSelection() {
        var recovery: String? = null
        rule.setContent { RescueAuthTheme { AuthenticatorScreen(uiState = state(listOf(work)), initialProviderName = "GitHub",
            onAddAccount = {}, onAddRecovery = { recovery = it }) } }
        click("global_add"); click("add_recovery")
        rule.onNodeWithTag("add_target_sheet").assertDoesNotExist()
        rule.runOnIdle { assertEquals("work", recovery) }
    }

    @Test fun accountPickerCanCreateAnAccountForTheOriginalAction() {
        val requests = mutableListOf<Pair<String, AccountAddKind>>()
        rule.setContent { RescueAuthTheme { AuthenticatorScreen(uiState = state(listOf(personal, work)), initialProviderName = "GitHub",
            onAddClick = {}, onAddRecovery = {}, onAddAccount = {},
            onCreateAccountFor = { provider, kind -> requests += provider to kind }) } }
        click("global_add"); click("add_recovery"); click("add_target_create_account")
        rule.onNodeWithTag("add_target_sheet").assertDoesNotExist()
        click("global_add"); click("add_authenticator"); click("add_target_create_account")
        rule.runOnIdle { assertEquals(listOf("GitHub" to AccountAddKind.RECOVERY, "GitHub" to AccountAddKind.CREDENTIAL), requests) }
    }

    @Test fun accountMenuHasOnlyItsTwoCredentialActions() {
        var code: String? = null; var recovery: String? = null
        rule.setContent { RescueAuthTheme { AuthenticatorScreen(uiState = state(listOf(personal, work)), initialAccountId = "work",
            onAddClick = {}, onAddCredentialToAccount = { code = it }, onAddRecovery = { recovery = it }) } }
        click("global_add")
        rule.onNodeWithTag("add_account").assertDoesNotExist()
        rule.onNodeWithTag("add_provider").assertDoesNotExist()
        click("add_authenticator")
        click("global_add"); click("add_recovery")
        rule.runOnIdle { assertEquals("work", code); assertEquals("work", recovery) }
    }

    @Test fun removedProviderClosesRecipientSelection() {
        val current = mutableStateOf(state(listOf(personal, work)))
        var recovery: String? = null
        rule.setContent { RescueAuthTheme { AuthenticatorScreen(uiState = current.value, initialProviderName = "GitHub",
            onAddAccount = {}, onAddRecovery = { recovery = it }) } }
        click("global_add"); click("add_recovery")
        rule.runOnIdle { current.value = AuthenticatorUiState(loading = false) }
        rule.onNodeWithTag("add_target_sheet").assertDoesNotExist()
        rule.onNodeWithTag("global_add").assertDoesNotExist()
        rule.runOnIdle { assertNull(recovery) }
    }

    @Test fun everyDeveloperCategoryCreatesItsOwnTypeWithoutAnotherPicker() {
        val category = mutableStateOf(DeveloperEntryType.API_CREDENTIAL)
        val selected = mutableListOf<String>()
        rule.setContent { RescueAuthTheme { key(category.value) {
            DeveloperRoute(categoryType = category.value, onAddTypeSelected = { selected += it.name }, onBack = {})
        } } }
        DeveloperEntryType.entries.forEach { type ->
            rule.runOnIdle { category.value = type }
            click("global_add")
            rule.onNodeWithTag("developer_add_sheet").assertDoesNotExist()
        }
        rule.runOnIdle { assertEquals(DeveloperEntryType.entries.map { it.name }, selected) }
    }

    @Test fun recoveryPageFloatingActionDirectlyUsesItsExistingCreateCallback() {
        var added = 0
        rule.setContent { RescueAuthTheme { RecoveryCodesScreen(
            uiState = RecoveryUiState(accountId = "work", accountName = "Work"), onAddClick = { added++ }) } }
        click("global_add")
        rule.runOnIdle { assertEquals(1, added) }
    }
}
