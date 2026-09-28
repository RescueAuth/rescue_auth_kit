package com.rescueauth.v2.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.enableEdgeToEdge
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.room.Room
import androidx.core.view.WindowCompat
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rescueauth.v2.database.RescueAuthDatabase
import com.rescueauth.v2.repository.VaultAccess
import com.rescueauth.v2.session.SecureSessionStateMachine
import com.rescueauth.v2.session.SessionManager
import com.rescueauth.v2.ui.model.DeveloperEntryType
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import kotlinx.coroutines.*
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real shell/routes/ViewModels/repositories, using an isolated in-memory Room database.
 * No live vault is opened and secret form inputs stay empty in every screenshot. */
@RunWith(AndroidJUnit4::class)
class DeepAddFlowInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var restoration: StateRestorationTester
    private lateinit var manager: SessionManager
    private lateinit var scope: CoroutineScope
    private var previousManager: SessionManager? = null
    private lateinit var personalId: String
    private lateinit var workId: String
    private lateinit var soloId: String
    private var originalConfiguration: Configuration? = null
    private val contentMounted = mutableStateOf(true)

    @Before fun openIsolatedTestVault() {
        restoration = StateRestorationTester(rule)
        previousManager = VaultAccess.sessionManager
        VaultAccess.clear()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        manager = SessionManager(context, SecureSessionStateMachine(), scope,
            databaseFactory = { ctx, _ -> Room.inMemoryDatabaseBuilder(ctx, RescueAuthDatabase::class.java).allowMainThreadQueries().build() })
        val key = ByteArray(32) { it.toByte() }
        try { check(manager.unlockWithFreshKey(key)) } finally { key.fill(0) }
        VaultAccess.sessionManager = manager
        runBlocking {
            val repo = checkNotNull(VaultAccess.providerAccountRepository())
            personalId = repo.createProvider("GitHub", "Personal").id
            workId = repo.createAccount("GitHub", "Work").id
            soloId = repo.createProvider("Solo service", "Only account").id
        }
    }

    @After
    @Suppress("DEPRECATION")
    fun closeIsolatedTestVault() {
        // Dispose route scopes before closing their Activity and repository.
        rule.runOnIdle { contentMounted.value = false }
        rule.waitForIdle()
        originalConfiguration?.let { original ->
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val resources = rule.activity.resources
                resources.updateConfiguration(original, resources.displayMetrics)
            }
        }
        rule.activityRule.scenario.close()
        runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
        manager.lock()
        VaultAccess.clear()
        VaultAccess.sessionManager = previousManager
    }

    @Suppress("DEPRECATION")
    private fun show(start: String? = null, dark: Boolean = false, large: Boolean = false) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            // Bottom sheets/dialogs use the Activity's resources in a separate window.
            val resources = rule.activity.resources
            originalConfiguration = Configuration(resources.configuration)
            resources.updateConfiguration(Configuration(resources.configuration).apply {
                setLocales(LocaleList(Locale.SIMPLIFIED_CHINESE))
                fontScale = if (large) 1.5f else 1f
            }, resources.displayMetrics)
            rule.activity.enableEdgeToEdge()
            WindowCompat.getInsetsController(rule.activity.window, rule.activity.window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        restoration.setContent {
            val base = LocalContext.current
            val config = Configuration(base.resources.configuration).apply { setLocales(LocaleList(Locale.SIMPLIFIED_CHINESE)) }
            CompositionLocalProvider(LocalContext provides base.createConfigurationContext(config), LocalConfiguration provides config,
                LocalActivityResultRegistryOwner provides rule.activity, LocalOnBackPressedDispatcherOwner provides rule.activity,
                LocalDensity provides Density(LocalDensity.current.density, if (large) 1.5f else 1f)) {
                if (contentMounted.value) RescueAuthTheme(darkTheme = dark) { RescueAuthApp(startRoute = start) }
            }
        }
    }

    private fun visible(tag: String): SemanticsNodeInteraction {
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }
        return rule.onNodeWithTag(tag).assertIsDisplayed()
    }
    private fun click(tag: String) { visible(tag).performTouchInput { click() } }
    private fun expandRecoveryEditorForReview() {
        // The recovery editor is hosted in a dialog, not the covered Activity window.
        onView(isRoot()).inRoot(isDialog()).perform(closeSoftKeyboard())
        val expand = SemanticsMatcher.keyIsDefined(SemanticsActions.Expand)
        if (rule.onAllNodes(expand).fetchSemanticsNodes().isNotEmpty()) {
            rule.onNode(expand).performSemanticsAction(SemanticsActions.Expand) { it() }
        }
        rule.onNodeWithText("保存").performScrollTo().assertIsDisplayed()
    }
    private fun capture(name: String) {
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(300, 5_000)
        val image = checkNotNull(automation.takeScreenshot())
        val output = checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir"))
        val file = File(output, "deep-add-real/$name.png").apply { parentFile!!.mkdirs() }
        try { file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { image.recycle() }
    }

    @Test fun serviceFloatingActionCreatesAccountInItsProvider() {
        show("authenticator/provider/GitHub")
        visible("account_row_$workId")
        rule.onNodeWithText("添加账户").assertDoesNotExist()
        rule.onNodeWithTag("vault_add_menu").assertDoesNotExist()
        capture("01-provider")
        click("global_add")
        visible("add_account"); visible("add_authenticator"); visible("add_recovery")
        capture("02-provider-options")
        click("add_account")
        rule.onNodeWithText("账户名称").assertIsDisplayed()
        capture("03-account-form")
        rule.onNodeWithText("账户名称").performTextInput("Review account")
        rule.onNodeWithText("确认").performTouchInput { click() }
        rule.waitUntil(10_000) { runBlocking {
            manager.databaseOrNull()!!.authAccountDao().listAll().any { it.serviceName == "GitHub" && it.accountName == "Review account" }
        } }
        rule.onNodeWithText("Review account").assertIsDisplayed()
        capture("04-account-created")
    }

    @Test fun serviceRecoveryTargetsChosenAccountAndDoesNotReopenAfterDismissal() {
        show()
        click("provider_row_GitHub")
        visible("account_row_$workId")
        click("global_add"); click("add_recovery")
        visible("add_target_$personalId"); visible("add_target_$workId")
        capture("05-select-account")
        click("add_target_$workId")
        visible("recovery_editor_sheet")
        rule.onNodeWithTag("recovery_scope_$workId").assertExists()
        rule.onNodeWithTag("recovery_scope_$personalId").assertDoesNotExist()
        expandRecoveryEditorForReview()
        capture("06-recovery-create")
        rule.onNodeWithText("取消").performScrollTo().performTouchInput { click() }
        rule.onNodeWithTag("recovery_editor_sheet").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        visible("recovery_scope_$workId")
        rule.onNodeWithTag("recovery_editor_sheet").assertDoesNotExist()
        capture("07-recovery-page")
        click("global_add")
        visible("recovery_editor_sheet")
        rule.onNodeWithTag("authenticator_add_sheet").assertDoesNotExist()
    }

    @Test fun singleAccountRecoverySkipsTheRecipientPicker() {
        show("authenticator/provider/Solo%20service")
        visible("account_row_$soloId")
        click("global_add"); click("add_recovery")
        visible("recovery_editor_sheet")
        rule.onNodeWithTag("add_target_sheet").assertDoesNotExist()
        rule.onNodeWithTag("recovery_scope_$soloId").assertExists()
    }

    @Test fun recoveryAccountPickerCanCreateItsOwnerWithoutLosingTheRequestedAction() {
        show("authenticator/provider/GitHub")
        visible("account_row_$personalId")
        click("global_add"); click("add_recovery"); click("add_target_create_account")
        rule.onNodeWithText("取消").performTouchInput { click() }
        rule.onNodeWithTag("recovery_editor_sheet").assertDoesNotExist()
        assertEquals(2, runBlocking { manager.databaseOrNull()!!.authAccountDao().listAll().count { it.serviceName == "GitHub" } })

        click("global_add"); click("add_recovery"); click("add_target_create_account")
        rule.onNodeWithText("账户名称").performTextInput("Work")
        rule.onNodeWithText("确认").performTouchInput { click() }
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Account already exists", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("recovery_editor_sheet").assertDoesNotExist()
        assertEquals(2, runBlocking { manager.databaseOrNull()!!.authAccountDao().listAll().count { it.serviceName == "GitHub" } })

        click("global_add"); click("add_recovery"); click("add_target_create_account")
        capture("12-create-owner")
        rule.onNodeWithText("账户名称").performTextInput("Recovery owner")
        rule.onNodeWithText("确认").performTouchInput { click() }
        visible("recovery_editor_sheet")
        val created = runBlocking { manager.databaseOrNull()!!.authAccountDao().listAll().single { it.accountName == "Recovery owner" } }
        assertEquals("GitHub", created.serviceName)
        rule.onNodeWithTag("recovery_scope_${created.id}").assertExists()
        expandRecoveryEditorForReview()
        capture("13-new-owner-recovery")
    }

    @Test fun codeAccountPickerContinuesWithTheNewlyCreatedAccount() {
        show("authenticator/provider/GitHub")
        visible("account_row_$personalId")
        click("global_add"); click("add_authenticator"); click("add_target_create_account")
        rule.onNodeWithText("账户名称").performTextInput("Code owner")
        rule.onNodeWithText("确认").performTouchInput { click() }
        click("totp_method_MANUAL")
        visible("add_totp_provider").assertTextContains("GitHub")
        visible("add_totp_account").assertTextContains("Code owner")
        val created = runBlocking { manager.databaseOrNull()!!.authAccountDao().listAll().single { it.accountName == "Code owner" } }
        assertEquals("GitHub", created.serviceName)
    }

    @Test fun accountFloatingActionOpensItsCodeAndRecoveryForms() {
        show("authenticator/account-detail/$workId")
        visible("account_recovery")
        rule.onNodeWithTag("vault_add_menu").assertDoesNotExist()
        click("global_add")
        rule.onNodeWithTag("add_account").assertDoesNotExist()
        capture("08-account-options")
        click("add_authenticator"); click("totp_method_MANUAL")
        visible("add_totp_provider").assertTextContains("GitHub")
        visible("add_totp_account").assertTextContains("Work")
        capture("09-code-context")
        pressBack()
        click("global_add"); click("add_recovery")
        visible("recovery_editor_sheet")
        rule.onNodeWithTag("recovery_scope_$workId").assertExists()
    }

    @Test fun providerCodeFormUsesTheSelectedAccount() {
        show("authenticator/provider/GitHub")
        visible("account_row_$personalId")
        click("global_add"); click("add_authenticator"); click("add_target_$personalId")
        click("totp_method_MANUAL")
        visible("add_totp_provider").assertTextContains("GitHub")
        visible("add_totp_account").assertTextContains("Personal")
    }

    @Test fun allDeveloperCategoriesGoStraightToTheCorrectForm() {
        show("developer")
        for (type in DeveloperEntryType.entries) {
            visible("developer_directory").performScrollToNode(hasTestTag("developer_category_${type.name}"))
            click("developer_category_${type.name}")
            click("global_add")
            visible("developer_form_${type.name}")
            rule.onNodeWithTag("developer_add_sheet").assertDoesNotExist()
            capture("10-developer-${type.name.lowercase()}")
            rule.onNodeWithContentDescription("返回").performTouchInput { click() }
            click("global_add")
            visible("developer_form_${type.name}")
            rule.onNodeWithContentDescription("返回").performTouchInput { click() }
            rule.onNodeWithContentDescription("返回").performTouchInput { click() }
        }
    }

    @Test fun floatingHorizontalPositionIsSharedWithDeeperScreens() {
        show()
        visible("provider_row_GitHub")
        visible("global_add").performTouchInput { swipe(center, Offset(-600f, center.y), durationMillis = 400) }
        rule.mainClock.advanceTimeBy(1_000); rule.waitForIdle()
        val rootX = visible("global_add").getUnclippedBoundsInRoot().left
        click("provider_row_GitHub"); visible("account_row_$workId")
        assertEquals(rootX, visible("global_add").getUnclippedBoundsInRoot().left)
        click("account_row_$workId"); visible("account_recovery")
        assertEquals(rootX, visible("global_add").getUnclippedBoundsInRoot().left)
        click("account_recovery"); visible("recovery_scope_$workId")
        assertEquals(rootX, visible("global_add").getUnclippedBoundsInRoot().left)
    }
    @Test fun deletedProviderCannotLeaveAStaleAddTargetOpen() {
        show("authenticator/provider/GitHub")
        visible("account_row_$personalId")
        click("global_add"); click("add_recovery")
        visible("add_target_sheet")
        runBlocking { VaultAccess.providerAccountRepository()!!.deleteProvider("GitHub") }
        visible("vault_item_unavailable")
        rule.onNodeWithTag("add_target_sheet").assertDoesNotExist()
        rule.onNodeWithTag("global_add").assertDoesNotExist()
    }

    @Test fun darkLargeProviderKeepsAllThreeActionsReachable() {
        show("authenticator/provider/GitHub", dark = true, large = true)
        visible("account_row_$workId")
        click("global_add")
        visible("add_recovery").performScrollTo().assertIsDisplayed()
        capture("11-dark-large-provider-options")
        click("add_recovery"); click("add_target_$workId")
        visible("recovery_editor_sheet")
        rule.onNodeWithTag("recovery_scope_$workId").assertExists()
    }
}
