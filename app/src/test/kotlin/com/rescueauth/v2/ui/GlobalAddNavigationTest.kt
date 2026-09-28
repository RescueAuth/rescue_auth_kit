package com.rescueauth.v2.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class GlobalAddNavigationTest {
    @get:Rule val rule = createComposeRule()

    private fun show() = rule.setContent { RescueAuthTheme { RescueAuthApp() } }

    @Test fun accountsHaveOneGlobalAddAndOpenTheCredentialWorkflow() {
        show()
        rule.onAllNodesWithTag("global_add").assertCountEquals(1)
        rule.onNodeWithTag("vault_add_menu").assertDoesNotExist()
        rule.onNodeWithTag("global_add").performClick()
        rule.onNodeWithTag("authenticator_add_sheet").assertIsDisplayed()
        rule.onNodeWithText("Create a service").assertIsDisplayed()
        rule.onNodeWithText("Add authenticator").performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("authenticator_add_sheet").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithTag("authenticator_add_sheet").assertDoesNotExist()
        rule.onNodeWithTag("add_totp_sheet").assertIsDisplayed()
    }

    @Test fun accountsKeepCreateServiceSeparateFromCredentials() {
        show()
        rule.onNodeWithTag("global_add").performClick()
        rule.onNodeWithText("Create a service").performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("authenticator_add_sheet").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithText("Provider name").assertExists()
        rule.onNodeWithTag("add_totp_sheet").assertDoesNotExist()
    }

    @Test fun developerUsesItsOwnSheetAndSelectedForm() {
        show()
        rule.onNodeWithTag(RescueAuthTestTags.NAV_DEVELOPER).performClick()
        rule.onAllNodesWithContentDescription("Add").assertCountEquals(1)
        rule.onNodeWithTag("global_add").performClick()
        rule.onNodeWithTag("developer_add_sheet").assertIsDisplayed()
        rule.onNodeWithText("Create a service").assertDoesNotExist()
        rule.onNodeWithTag("add_type_SSH_KEY").performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("developer_add_sheet").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithTag("developer_add_sheet").assertDoesNotExist()
        rule.onNodeWithTag("global_add").assertDoesNotExist()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithTag(RescueAuthTestTags.NAV_DEVELOPER).assertIsSelected()
        rule.onNodeWithTag("global_add").assertIsDisplayed()
    }

    @Test fun settingsRemoveTheActionAndReturningUsesTheCurrentPage() {
        show()
        rule.onNodeWithTag(RescueAuthTestTags.NAV_SETTINGS).performClick()
        rule.onNodeWithTag("global_add").assertDoesNotExist()
        rule.onNodeWithTag(RescueAuthTestTags.NAV_DEVELOPER).performClick()
        rule.onNodeWithTag("global_add").performClick()
        rule.onNodeWithTag("developer_add_sheet").assertIsDisplayed()
    }

    @Test fun olderCreateLinksWithAnEmptyEditIdStillOpenANewForm() {
        rule.setContent { RescueAuthTheme {
            RescueAuthApp(startRoute = "developer/form?editStableId=&type=SSH_KEY")
        } }
        rule.onNodeWithTag("developer_form_next").assertIsDisplayed()
        rule.onNodeWithTag("global_add").assertDoesNotExist()
    }
}
