package com.rescueauth.v2.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rescueauth.v2.exportimport.ExportImportViewModel
import com.rescueauth.v2.legacyimport.*
import com.rescueauth.v2.ui.authenticator.RecoveryUiState
import com.rescueauth.v2.ui.authenticator.RecoveryFormState
import com.rescueauth.v2.ui.components.ErrorState
import com.rescueauth.v2.ui.model.RecoveryCodeSetUi
import com.rescueauth.v2.ui.screens.authenticator.PermissionDeniedContent
import com.rescueauth.v2.ui.screens.authenticator.RecoveryCodesScreen
import com.rescueauth.v2.ui.screens.authenticator.RecoveryCodeEditorSheet
import com.rescueauth.v2.ui.screens.exportimport.*
import com.rescueauth.v2.ui.screens.legacyimport.*
import com.rescueauth.v2.ui.screens.search.SearchScreen
import com.rescueauth.v2.ui.screens.startup.StartupBlockedScreen
import com.rescueauth.v2.ui.screens.startup.StartupLockTestTags
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import com.rescueauth.v2.ui.theme.Spacing
import java.io.File
import java.util.Locale
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production views, empty inputs and synthetic counts; no vault or camera access. */
@RunWith(AndroidJUnit4::class)
class CardHeaderReviewTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var original: Configuration? = null
    @Suppress("DEPRECATION")
    private fun show(dark: Boolean = false, large: Boolean = false, english: Boolean = false, narrow: Boolean = false, body: @Composable () -> Unit) {
        val locale = if (english) Locale.ENGLISH else Locale.SIMPLIFIED_CHINESE
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val resources = rule.activity.resources
            original = Configuration(resources.configuration)
            resources.updateConfiguration(Configuration(resources.configuration).apply {
                setLocales(LocaleList(locale)); fontScale = if (large) 1.5f else 1f
            }, resources.displayMetrics)
            rule.activity.enableEdgeToEdge()
            WindowCompat.getInsetsController(rule.activity.window, rule.activity.window.decorView).apply {
                isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
            }
        }
        rule.setContent {
            val base = LocalContext.current
            val config = Configuration(base.resources.configuration).apply { setLocales(LocaleList(locale)) }
            CompositionLocalProvider(LocalContext provides base.createConfigurationContext(config), LocalConfiguration provides config,
                LocalDensity provides Density(LocalDensity.current.density, if (large) 1.5f else 1f)) {
                RescueAuthTheme(darkTheme = dark) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        Box((if (narrow) Modifier.width(320.dp).fillMaxHeight() else Modifier.fillMaxSize()).testTag("review_canvas")) { body() }
                    }
                }
            }
        }
    }

    @After @Suppress("DEPRECATION") fun restoreConfiguration() {
        original?.let { config -> InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val resources = rule.activity.resources
            resources.updateConfiguration(config, resources.displayMetrics)
        } }
    }


    private fun checkHeader(parent: String) {
        val ancestor = hasAnyAncestor(hasTestTag(parent))
        val icon = rule.onNode(hasTestTag("card_header_icon") and ancestor, useUnmergedTree = true).assertIsDisplayed().getUnclippedBoundsInRoot()
        val title = rule.onNode(hasTestTag("card_header_title") and ancestor, useUnmergedTree = true).assertIsDisplayed().getUnclippedBoundsInRoot()
        val box = rule.onNodeWithTag(parent).getUnclippedBoundsInRoot()
        assertTrue(title.left >= icon.right)
        assertTrue(title.right <= box.right)
        assertTrue(kotlin.math.abs((icon.right - icon.left).value - 52f) < 0.5f)
        assertTrue(icon.top < title.bottom && title.top < icon.bottom)
    }
    private fun click(tag: String) = rule.onNodeWithTag(tag).performTouchInput { click() }
    private fun capture(name: String, narrow: Boolean = false) {
        rule.mainClock.advanceTimeBy(600); rule.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(350, 5_000)
        val bitmap = if (narrow) rule.onNodeWithTag("review_canvas").captureToImage().asAndroidBitmap() else checkNotNull(automation.takeScreenshot())
        val output = checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir"))
        val file = File(output, "card-headers/$name.png").apply { parentFile!!.mkdirs() }
        try { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { bitmap.recycle() }
    }
    @Composable private fun legacy(onSubmit: () -> Unit = {}) {
        LegacyImportScreen(LegacyImportViewModel.State.AwaitingPassword, {}, { onSubmit(); false }, {}, {}, {}, {}, {}, {})
    }

    @Test fun legacyPasswordGroupsItsHeaderAndHidesTheExplanationUntilRequested() {
        var submitted = 0
        show { legacy { submitted++ } }
        checkHeader("legacy_password_help_info")
        rule.onNodeWithTag("legacy_password_help_explanation").assertDoesNotExist()
        capture("legacy-collapsed")
        click("legacy_password_help_info")
        rule.onNodeWithTag("legacy_password_help_explanation").assertIsDisplayed()
        capture("legacy-explanation")
        click("legacy_password_help_close")
        rule.onNodeWithTag(LegacyImportTestTags.PASSWORD_FIELD).assertIsDisplayed()
        assertEquals(0, submitted)
    }

    @Test fun legacyHeaderWrapsAt320DpInDarkLargeText() {
        show(dark = true, large = true, narrow = true) { legacy() }
        checkHeader("legacy_password_help_info")
        rule.onNodeWithTag(LegacyImportTestTags.PASSWORD_FIELD).performScrollTo().assertIsDisplayed()
        capture("legacy-dark-large", narrow = true)
        rule.onNodeWithTag("legacy_password_help_info").performScrollTo().performTouchInput { click() }
        rule.onNodeWithTag("legacy_password_help_explanation").assertIsDisplayed()
        rule.onNodeWithTag("legacy_password_help_close").performScrollTo().assertIsDisplayed()
        capture("legacy-dark-explanation")
    }

    @Test fun exportPinUsesOneCardWithAnExplanationHeader() {
        show { ExportVaultScreen(ExportImportViewModel.ExportState.AwaitingPin, {}, {}, { _, _ -> null }, {}, {}, {}) }
        checkHeader("export_pin_help_info")
        rule.onNodeWithTag(ExportImportTestTags.EXPORT_PIN_FIELD).assertIsDisplayed()
        rule.onNodeWithTag(ExportImportTestTags.EXPORT_CONFIRM_FIELD).assertIsDisplayed()
        rule.onNodeWithTag("export_pin_help_explanation").assertDoesNotExist()
        capture("export-pin")
        click("export_pin_help_info")
        rule.onNodeWithTag("export_pin_help_explanation").assertIsDisplayed()
        capture("export-explanation")
    }

    @Test fun legacyPreviewPairsItsSummaryIconWithTheTitle() {
        val preview = LegacyImportPreview(1, "synthetic-source", 2, 0, 0, 0,
            LegacyDeveloperPreviewSummary(0, 0, 0, 0, 0, emptyList()), 2, 0, 0, 0, 0)
        show { LegacyImportScreen(LegacyImportViewModel.State.Preview(preview), {}, { false }, {}, {}, {}, {}, {}, {}) }
        checkHeader("summary_card_header")
        capture("legacy-preview")
    }

    @Test fun searchEmptyStateKeepsItsTextAlongsideTheIcon() {
        show { SearchScreen() }
        rule.waitForIdle(); closeSoftKeyboard()
        checkHeader("empty_state_header")
        capture("search-empty")
    }

    @Test fun errorsKeepTheirCauseVisibleAndRetryReachable() {
        var retried = 0
        show { ErrorState("暂时无法加载", message = "请稍后重试。", retryLabel = "重试", onRetry = { retried++ }) }
        checkHeader("error_state_header")
        rule.onNodeWithText("请稍后重试。").assertIsDisplayed()
        capture("error-card")
        rule.onNodeWithText("重试").performTouchInput { click() }
        assertEquals(1, retried)
    }

    @Test fun recoverySummaryUsesTheSharedHeaderWithoutRevealingCodes() {
        show { RecoveryCodesScreen(
            RecoveryUiState(accountId = "demo", accountName = "示例账户", providerName = "示例服务",
                sets = listOf(RecoveryCodeSetUi("demo-set", "示例分组", 0, 0))), onBack = {}, onAddClick = {}) }
        checkHeader("recovery_summary_header")
        capture("recovery-summary")
    }

    @Test fun recoveryEditorKeepsItsHeaderAndSaveActionVisible() {
        var submitted = 0
        show { RecoveryCodeEditorSheet(RecoveryFormState(), {}, {}, {}, { submitted++ }) }
        checkHeader("recovery_editor_header")
        rule.onNodeWithText("保存").assertIsDisplayed()
        capture("recovery-editor")
        rule.onNodeWithText("保存").performTouchInput { click() }
        assertEquals(1, submitted)
    }

    @Test fun blockedStartupKeepsExplanationAndExitVisibleWithLargeText() {
        var exited = 0
        show(dark = true, large = true, narrow = true) {
            StartupBlockedScreen("需要屏幕锁定", "请先为设备设置屏幕锁定，再回来开启保险库。", onExit = { exited++ })
        }
        checkHeader("startup_blocked_header")
        rule.onNodeWithTag(StartupLockTestTags.EXIT_BUTTON).performScrollTo().assertIsDisplayed()
        capture("startup-blocked-large", narrow = true)
        click(StartupLockTestTags.EXIT_BUTTON)
        assertEquals(1, exited)
    }

    @Test fun cameraPermissionPromptKeepsItsRequestAction() {
        var requested = 0
        show { PermissionDeniedContent(needsSettings = false, onDismiss = {}, onRequest = { requested++ },
            modifier = Modifier.fillMaxSize().padding(Spacing.lg)) }
        checkHeader("camera_permission_header")
        capture("camera-permission")
        rule.onNodeWithText("允许相机").performTouchInput { click() }
        assertEquals(1, requested)
    }
}
