package com.rescueauth.v2.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rescueauth.v2.ui.authenticator.*
import com.rescueauth.v2.ui.model.AccountUi
import com.rescueauth.v2.ui.screens.authenticator.AccountAddSheet
import com.rescueauth.v2.ui.screens.authenticator.RecoveryCodeEditorSheet
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en")
class AccountAddSheetTest {
    @get:Rule val rule = createComposeRule()
    private fun click(tag: String) = rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick) { it() }

    @Test fun accountNameAndOptionalContentShareOneSheetWithOneSave() {
        val draft = mutableStateOf(AccountAddFormState(visible = true, provider = "GitHub", accountName = "Personal"))
        var submitted = 0
        rule.setContent { RescueAuthTheme {
            AccountAddSheet(draft.value, listOf(AccountUi("work", "GitHub", "Work")),
                onDismiss = {}, onAccountSelected = {}, onAccountNameChange = {},
                onKindChange = { draft.value = draft.value.copy(kind = it) }, onTotpChange = {},
                onRecoveryTitleChange = {}, onRecoveryValuesChange = {}, onStartScan = {}, onSubmit = { submitted++ })
        } }
        rule.onNodeWithTag("account_add_name").assertIsDisplayed()
        rule.onNodeWithTag("account_add_submit").assertIsDisplayed()
        click("account_add_kind_RECOVERY")
        rule.onNodeWithTag("recovery_values").assertExists()
        rule.onNodeWithTag("recovery_name").assertDoesNotExist()
        rule.onNodeWithTag("add_target_sheet").assertDoesNotExist()
        rule.onNodeWithTag("recovery_editor_sheet").assertDoesNotExist()
        click("account_add_kind_TOTP")
        rule.onNodeWithTag("account_add_secret").assertExists()
        rule.onNodeWithTag("account_add_submit").assertIsDisplayed()
        click("account_add_submit")
        assertEquals(1, submitted)
    }

    @Test fun recipientUsesASecondLevelWithFullWidthChoicesAndReturnsToTheForm() {
        var selected: AccountUi? = null
        val work = AccountUi("work", "GitHub", "Work")
        rule.setContent { RescueAuthTheme {
            AccountAddSheet(AccountAddFormState(visible = true, provider = "GitHub"), listOf(work),
                onDismiss = {}, onAccountSelected = { selected = it }, onAccountNameChange = {},
                onKindChange = {}, onTotpChange = {}, onRecoveryTitleChange = {}, onRecoveryValuesChange = {},
                onStartScan = {}, onSubmit = {})
        } }
        click("account_add_recipient")
        rule.onNodeWithTag("account_add_name").assertDoesNotExist()
        val input = rule.onNodeWithTag("account_add_search").getUnclippedBoundsInRoot()
        val choices = rule.onNodeWithTag("account_add_choices").getUnclippedBoundsInRoot()
        assertEquals(input.left, choices.left)
        assertEquals(input.right, choices.right)
        click("account_add_target_work")
        assertEquals(work, selected)
        rule.onNodeWithTag("add_target_sheet").assertDoesNotExist()
        rule.onNodeWithTag("account_add_name").assertExists()
        rule.onAllNodesWithTag("account_add_sheet").assertCountEquals(1)
    }

    @Test fun homeOpensTheSameFormWithProviderAndManualCodeFieldsAlreadyVisible() {
        rule.setContent { RescueAuthTheme {
            AccountAddSheet(AccountAddFormState(visible = true, scope = AccountAddScope.VAULT, kind = AccountAddContentKind.TOTP),
                listOf(AccountUi("work", "GitHub", "Work")), onDismiss = {}, onAccountSelected = {}, onAccountNameChange = {},
                onKindChange = {}, onTotpChange = {}, onRecoveryTitleChange = {}, onRecoveryValuesChange = {}, onStartScan = {}, onSubmit = {})
        } }
        rule.onNodeWithTag("account_add_provider").assertExists()
        rule.onNodeWithTag("account_add_name").assertExists()
        rule.onNodeWithTag("account_add_secret").assertExists()
        rule.onNodeWithTag("authenticator_add_sheet").assertDoesNotExist()
        rule.onNodeWithTag("totp_change_method").assertDoesNotExist()
    }

    @Test fun advancedParametersUseAChildPageWithoutSavingOrLosingTheDraft() {
        val form = mutableStateOf(AccountAddFormState(visible = true, provider = "GitHub", accountName = "Personal",
            kind = AccountAddContentKind.TOTP, totp = AddTotpFormState(mode = AddMode.MANUAL, secret = "SYNTHETIC")))
        var saved = 0
        rule.setContent { RescueAuthTheme {
            AccountAddSheet(form.value, emptyList(), {}, {}, {}, {},
                onTotpChange = { change -> form.value = form.value.copy(totp = change(form.value.totp)) },
                onRecoveryTitleChange = {}, onRecoveryValuesChange = {}, onStartScan = {}, onSubmit = { saved++ })
        } }
        click("account_add_advanced")
        rule.onNodeWithTag("account_add_secret").assertDoesNotExist()
        rule.onNodeWithText("SHA256").performSemanticsAction(SemanticsActions.OnClick) { it() }
        click("sheet_page_done")
        rule.onNodeWithTag("account_add_secret").assertExists()
        assertEquals("SHA256", form.value.totp.algorithm)
        assertEquals("SYNTHETIC", form.value.totp.secret)
        assertEquals(0, saved)
    }

    @Test fun recoveryNameIsAnOptionalExpansionAndExistingNamesRemainEditable() {
        rule.setContent { RescueAuthTheme {
            RecoveryCodeEditorSheet(RecoveryFormState(), {}, {}, {}, {})
        } }
        rule.onNodeWithTag("recovery_values").assertExists()
        rule.onNodeWithTag("recovery_name").assertDoesNotExist()
        click("recovery_custom_name")
        rule.onNodeWithTag("recovery_name").assertExists()
    }
}
