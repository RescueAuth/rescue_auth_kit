package com.rescueauth.v2.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.enableEdgeToEdge
import android.content.res.Configuration
import android.os.LocaleList
import android.os.Build
import android.view.inspector.WindowInspector
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
        rule.onNodeWithText("保存").assertIsDisplayed()
    }
    private fun capture(name: String) {
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(300, 5_000)
        // Dialog state can settle before its latest buffer is presented. Commit the modal's
        // actual window, rather than the covered Activity, before exporting a screenshot.
        if (Build.VERSION.SDK_INT >= 29) {
            val committed = CountDownLatch(1)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                WindowInspector.getGlobalWindowViews().lastOrNull { it.isAttachedToWindow && it.isShown }?.let { window ->
                    window.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
                    window.invalidate()
                } ?: committed.countDown()
            }
            assertTrue("Latest modal frame was not committed", committed.await(5, TimeUnit.SECONDS))
        }
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
        visible("account_add_sheet")
        rule.onNodeWithTag("authenticator_add_sheet").assertDoesNotExist()
        capture("03-account-form")
        visible("account_add_name").performTextInput("Review account")
        visible("account_add_submit").performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.waitUntil(10_000) { runBlocking {
            manager.databaseOrNull()!!.authAccountDao().listAll().any { it.serviceName == "GitHub" && it.accountName == "Review account" }
        } }
        rule.onNodeWithText("Review account").assertIsDisplayed()
        capture("04-account-created")
    }

    @Test fun homeFloatingActionDirectlyCreatesServiceAccountAndCode() {
        show()
        visible("provider_row_GitHub")
        click("global_add")
        visible("account_add_sheet")
        rule.onNodeWithTag("authenticator_add_sheet").assertDoesNotExist()
        rule.onNodeWithTag("account_add_provider").assertExists()
        rule.onNodeWithTag("account_add_secret").assertExists()
        capture("20-home-direct-code")
        rule.onNodeWithTag("account_add_provider").performScrollTo().performTextInput("Review new service")
        rule.onNodeWithTag("account_add_name").performScrollTo().performTextInput("Review account")
        rule.onNodeWithTag("account_add_secret").performScrollTo().performTextInput("JBSWY3DPEHPK3PXP")
        visible("account_add_submit").performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.waitUntil(10_000) { runBlocking { manager.databaseOrNull()!!.authAccountDao().listAll().any { it.serviceName == "Review new service" } } }
        val account = runBlocking { manager.databaseOrNull()!!.authAccountDao().listAll().single { it.serviceName == "Review new service" } }
        assertEquals(1, runBlocking { manager.databaseOrNull()!!.totpCredentialDao().listByAccount(account.id).size })
    }

    @Test fun secondLevelChoicesAndOptionsReturnToTheSameSheet() {
        show("authenticator/provider/GitHub")
        visible("account_row_$workId")
        click("global_add"); click("account_add_recipient")
        visible("account_add_choices")
        val search = visible("account_add_search").getUnclippedBoundsInRoot()
        val choices = visible("account_add_choices").getUnclippedBoundsInRoot()
        assertEquals(search.left, choices.left); assertEquals(search.right, choices.right)
        capture("21-account-picker-page")
        pressBack()
        visible("account_add_name")
        click("account_add_kind_TOTP"); click("account_add_advanced")
        visible("sheet_page_title")
        rule.onNodeWithTag("account_add_secret").assertDoesNotExist()
        capture("22-advanced-page")
        pressBack()
        visible("account_add_secret")
        click("account_add_kind_RECOVERY"); click("recovery_custom_name")
        visible("recovery_name")
        rule.onNodeWithTag("recovery_values").assertDoesNotExist()
        capture("23-recovery-name-page")
        click("sheet_page_done")
        visible("recovery_values")
        rule.onAllNodesWithTag("account_add_sheet").assertCountEquals(1)
        assertEquals(2, runBlocking { manager.databaseOrNull()!!.authAccountDao().listByServiceName("GitHub").size })
    }

    @Test fun serviceRecoveryTargetsChosenAccountAndDoesNotReopenAfterDismissal() {
        show()
        click("provider_row_GitHub")
        visible("account_row_$workId")
        click("global_add"); click("account_add_recipient")
        visible("account_add_target_$personalId"); visible("account_add_target_$workId")
        capture("05-inline-select-account")
        click("account_add_target_$workId"); click("account_add_kind_RECOVERY")
        visible("recovery_values")
        rule.onNodeWithTag("recovery_name").assertDoesNotExist()
        rule.onNodeWithTag("recovery_editor_sheet").assertDoesNotExist()
        rule.onNodeWithTag("add_target_sheet").assertDoesNotExist()
        capture("06-recovery-create")
        click("account_add_cancel")
        rule.onNodeWithTag("account_add_sheet").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        visible("account_row_$workId")
        rule.onNodeWithTag("account_add_sheet").assertDoesNotExist()
        click("global_add")
        visible("account_add_sheet")
        rule.onNodeWithTag("authenticator_add_sheet").assertDoesNotExist()
    }

    @Test fun singleAccountRecoverySkipsTheRecipientPicker() {
        show("authenticator/provider/Solo%20service")
        visible("account_row_$soloId")
        click("global_add"); click("account_add_recipient"); click("account_add_target_$soloId")
        click("account_add_kind_RECOVERY")
        visible("recovery_values")
        rule.onNodeWithTag("add_target_sheet").assertDoesNotExist()
        visible("account_add_name").assertTextContains("Only account")
    }

    @Test fun recoveryAccountPickerCanCreateItsOwnerWithoutLosingTheRequestedAction() {
        show("authenticator/provider/GitHub")
        visible("account_row_$personalId")
        click("global_add"); click("account_add_kind_RECOVERY")
        click("account_add_cancel")
        rule.onNodeWithTag("account_add_sheet").assertDoesNotExist()
        assertEquals(2, runBlocking { manager.databaseOrNull()!!.authAccountDao().listAll().count { it.serviceName == "GitHub" } })

        click("global_add")
        visible("account_add_name").performTextInput("Work")
        visible("account_add_submit").performSemanticsAction(SemanticsActions.OnClick) { it() }
        visible("account_add_error").assertTextContains("此账户已存在", substring = true)
        visible("account_add_sheet")
        assertEquals(2, runBlocking { manager.databaseOrNull()!!.authAccountDao().listAll().count { it.serviceName == "GitHub" } })

        visible("account_add_name").performTextClearance()
        click("account_add_kind_RECOVERY")
        onView(isRoot()).inRoot(isDialog()).perform(closeSoftKeyboard())
        capture("12-create-owner")
        visible("account_add_name").performTextInput("Recovery owner")
        rule.onNodeWithTag("recovery_values").performScrollTo().performTextInput("sample-one\nsample-two")
        visible("account_add_submit").performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.waitUntil(10_000) { runBlocking { manager.databaseOrNull()!!.authAccountDao().listAll().any { it.accountName == "Recovery owner" } } }
        val created = runBlocking { manager.databaseOrNull()!!.authAccountDao().listAll().single { it.accountName == "Recovery owner" } }
        assertEquals("GitHub", created.serviceName)
        assertEquals("恢复码", runBlocking { manager.databaseOrNull()!!.recoveryCodeSetDao().listByAccount(created.id).single().title })
        visible("account_row_${created.id}")
    }

    @Test fun codeAccountPickerContinuesWithTheNewlyCreatedAccount() {
        show("authenticator/provider/GitHub")
        visible("account_row_$personalId")
        click("global_add"); click("account_add_kind_TOTP")
        visible("account_add_name").performTextInput("Code owner")
        rule.onNodeWithTag("account_add_secret").performScrollTo().performTextInput("JBSWY3DPEHPK3PXP")
        visible("account_add_submit").performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.waitUntil(10_000) { runBlocking { manager.databaseOrNull()!!.authAccountDao().listAll().any { it.accountName == "Code owner" } } }
        val created = runBlocking { manager.databaseOrNull()!!.authAccountDao().listAll().single { it.accountName == "Code owner" } }
        assertEquals("GitHub", created.serviceName)
        assertEquals(1, runBlocking { manager.databaseOrNull()!!.totpCredentialDao().listByAccount(created.id).size })
    }

    @Test fun accountFloatingActionOpensItsCodeAndRecoveryForms() {
        show("authenticator/account-detail/$workId")
        visible("account_recovery")
        rule.onNodeWithTag("vault_add_menu").assertDoesNotExist()
        click("global_add")
        rule.onNodeWithTag("add_account").assertDoesNotExist()
        rule.onNodeWithTag("authenticator_add_sheet").assertDoesNotExist()
        visible("account_add_secret")
        rule.onNodeWithTag("account_add_name").assertDoesNotExist()
        capture("09-code-context")
        click("account_add_kind_RECOVERY")
        visible("recovery_values")
        capture("08-account-recovery")
        click("account_add_cancel")
        visible("account_recovery")
    }

    @Test fun providerCodeFormUsesTheSelectedAccount() {
        show("authenticator/provider/GitHub")
        visible("account_row_$personalId")
        click("global_add"); click("account_add_recipient"); click("account_add_target_$personalId")
        visible("account_add_name").assertTextContains("Personal")
        visible("account_add_secret")
        rule.onNodeWithTag("add_totp_provider").assertDoesNotExist()
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
        click("global_add"); click("account_add_kind_RECOVERY")
        visible("account_add_sheet")
        runBlocking { VaultAccess.providerAccountRepository()!!.deleteProvider("GitHub") }
        visible("vault_item_unavailable")
        rule.onNodeWithTag("account_add_sheet").assertDoesNotExist()
        rule.onNodeWithTag("global_add").assertDoesNotExist()
    }

    @Test fun darkLargeProviderKeepsAllThreeActionsReachable() {
        show("authenticator/provider/GitHub", dark = true, large = true)
        visible("account_row_$workId")
        click("global_add")
        rule.onNodeWithTag("account_add_kind_RECOVERY").performScrollTo().performTouchInput { click() }
        visible("account_add_submit")
        capture("11-dark-large-provider-recovery")
        rule.onNodeWithTag("account_add_name").performScrollTo().performTextInput("Large text account")
        visible("account_add_submit").assertIsDisplayed()
        // No recovery values are entered in screenshot cases.
        capture("14-dark-large-keyboard")
    }
}
