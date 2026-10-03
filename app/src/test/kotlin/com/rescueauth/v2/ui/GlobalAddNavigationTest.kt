package com.rescueauth.v2.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import kotlinx.coroutines.cancelAndJoin
import androidx.compose.runtime.mutableStateOf
import org.junit.Rule
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class GlobalAddNavigationTest {
    @get:Rule val rule = createComposeRule()
    private var manager: com.rescueauth.v2.session.SessionManager? = null
    private var scope: kotlinx.coroutines.CoroutineScope? = null
    private val mounted = mutableStateOf(true)
    @After fun closeVault() {
        rule.runOnIdle { mounted.value = false }
        rule.waitForIdle()
        manager?.lock()
        if (manager != null) com.rescueauth.v2.repository.VaultAccess.clear()
        kotlinx.coroutines.runBlocking { scope?.coroutineContext?.get(kotlinx.coroutines.Job)?.cancelAndJoin() }
    }
    private fun openTestVault() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val testScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        scope = testScope
        manager = com.rescueauth.v2.session.SessionManager(context, com.rescueauth.v2.session.SecureSessionStateMachine(), testScope,
            databaseFactory = { ctx, _ -> androidx.room.Room.inMemoryDatabaseBuilder(ctx, com.rescueauth.v2.database.RescueAuthDatabase::class.java).allowMainThreadQueries().build() })
        val key = ByteArray(32) { it.toByte() }
        try { check(manager!!.unlockWithFreshKey(key)) } finally { key.fill(0) }
        com.rescueauth.v2.repository.VaultAccess.sessionManager = manager
    }

    private fun show() = rule.setContent { if (mounted.value) RescueAuthTheme { RescueAuthApp() } }

    @Test fun accountsHaveOneGlobalAddAndOpenTheCredentialWorkflow() {
        openTestVault()
        show()
        rule.onAllNodesWithTag("global_add").assertCountEquals(1)
        rule.onNodeWithTag("vault_add_menu").assertDoesNotExist()
        rule.onNodeWithTag("global_add").performClick()
        rule.onNodeWithTag("authenticator_add_sheet").assertDoesNotExist()
        rule.onNodeWithTag("account_add_sheet").assertIsDisplayed()
        rule.onNodeWithTag("account_add_provider").assertExists()
        rule.onNodeWithTag("account_add_name").assertExists()
        rule.onNodeWithTag("account_add_secret").assertExists()
    }

    @Test fun accountsCanCreateAServiceWithinTheSameForm() {
        openTestVault()
        show()
        rule.onNodeWithTag("global_add").performClick()
        rule.onNodeWithTag("account_add_kind_NONE").performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.onNodeWithTag("account_add_provider").assertExists()
        rule.onNodeWithTag("account_add_name").assertExists()
        rule.onNodeWithTag("account_add_secret").assertDoesNotExist()
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
