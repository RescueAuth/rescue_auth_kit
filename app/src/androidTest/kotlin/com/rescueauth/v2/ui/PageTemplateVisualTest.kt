package com.rescueauth.v2.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rescueauth.v2.export.SnapshotScope
import com.rescueauth.v2.exportimport.DeveloperPreviewSummary
import com.rescueauth.v2.exportimport.ExportImportViewModel
import com.rescueauth.v2.exportimport.ImportPreview
import com.rescueauth.v2.legacyimport.LegacyDeveloperPreviewSummary
import com.rescueauth.v2.legacyimport.LegacyImportPreview
import com.rescueauth.v2.legacyimport.LegacyImportViewModel
import com.rescueauth.v2.ui.screens.exportimport.*
import com.rescueauth.v2.ui.screens.legacyimport.*
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Native page-template checks. No file picker, import, database or non-empty credential is used. */
@RunWith(AndroidJUnit4::class)
class PageTemplateVisualTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val legacyPreview = LegacyImportPreview(1, "demo", 2, 2, 1, 4,
        LegacyDeveloperPreviewSummary(0, 0, 0, 0, 0, emptyList()), 5, 0, 0, 0, 0)
    private val nativePreview = ImportPreview("demo", "2026-09-27T00:00:00Z", "Android", "1.0.0", SnapshotScope.FULL_VAULT,
        2, 2, 1, 4, DeveloperPreviewSummary.from(emptyList()), 5, 0, 0, 0, 0)

    private fun content(dark: Boolean = false, fontScale: Float = 1f, narrow: Boolean = false, body: @Composable () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            rule.activity.enableEdgeToEdge()
            rule.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            WindowCompat.getInsetsController(rule.activity.window, rule.activity.window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        rule.setContent {
            val base = LocalContext.current
            val config = Configuration(base.resources.configuration).apply { setLocales(LocaleList(Locale.SIMPLIFIED_CHINESE)) }
            CompositionLocalProvider(LocalContext provides base.createConfigurationContext(config),
                LocalConfiguration provides config, LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                RescueAuthTheme(darkTheme = dark) {
                    Surface(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize()) {
                            Box((if (narrow) Modifier.width(320.dp).height(600.dp) else Modifier.fillMaxSize())
                                .testTag("template_test_viewport")) { body() }
                        }
                    }
                }
            }
        }
    }

    @Composable private fun legacy(state: LegacyImportViewModel.State) = LegacyImportScreen(state,
        {}, { false }, {}, {}, {}, {}, {}, onBack = {})
    @Composable private fun nativeImport(state: ExportImportViewModel.ImportState) = ImportNativePackageScreen(state,
        {}, {}, {}, {}, {}, {}, {}, {}, onBack = {})

    private fun capture(name: String, fullWindow: Boolean = false) {
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        if (fullWindow) automation.waitForIdle(500, 5_000)
        val bitmap = if (fullWindow) checkNotNull(automation.takeScreenshot())
            else rule.onNodeWithTag("template_test_viewport").captureToImage().asAndroidBitmap()
        val dir = File(rule.activity.getExternalFilesDir(null), "page-template-review").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun legacyPasswordUsesTheSharedFooter() {
        content { legacy(LegacyImportViewModel.State.AwaitingPassword) }
        rule.onNodeWithTag("page_action_bar").assertIsDisplayed()
        rule.onNodeWithTag(LegacyImportTestTags.PASSWORD_SUBMIT).assertIsNotEnabled()
        capture("01-legacy-password")
    }

    @Test fun legacyPreviewKeepsActionsWhileContentScrolls() {
        content { legacy(LegacyImportViewModel.State.Preview(legacyPreview)) }
        val before = rule.onNodeWithTag(LegacyImportTestTags.CONFIRM).getUnclippedBoundsInRoot()
        capture("02-legacy-preview")
        rule.onNodeWithText("恢复码状态差异").performScrollTo().assertIsDisplayed()
        assertEquals(before, rule.onNodeWithTag(LegacyImportTestTags.CONFIRM).getUnclippedBoundsInRoot())
        capture("03-legacy-preview-scrolled")
    }

    @Test fun nativePreviewUsesTheSameSummaryAndActions() {
        content { nativeImport(ExportImportViewModel.ImportState.Preview(nativePreview)) }
        rule.onNodeWithTag("page_action_bar").assertIsDisplayed()
        rule.onNodeWithTag(ExportImportTestTags.IMPORT_CONFIRM).assertIsEnabled()
        capture("04-native-preview")
    }

    @Test fun narrowLargePreviewCanReachWarningsAndCancel() {
        content(fontScale = 1.5f, narrow = true) { legacy(LegacyImportViewModel.State.Preview(legacyPreview.copy(conflicts = 1))) }
        rule.onNodeWithTag(LegacyImportTestTags.CONFIRM).assertIsNotEnabled().assertIsDisplayed()
        rule.onNodeWithTag(LegacyImportTestTags.CANCEL).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText("确认导入", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse(layouts.single().hasVisualOverflow)
        rule.onNodeWithText("恢复码状态差异").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("您可以返回并选择其他文件。").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("无法自动应用导入").assertIsDisplayed()
        capture("05-narrow-large-preview")
    }

    @Test fun passwordFooterStaysAboveRealKeyboard() {
        var keyboard: SoftwareKeyboardController? = null
        var ime = 0
        var height = 0
        var density = 1f
        content {
            keyboard = LocalSoftwareKeyboardController.current
            density = LocalDensity.current.density
            ime = WindowInsets.ime.getBottom(LocalDensity.current)
            height = LocalView.current.height
            legacy(LegacyImportViewModel.State.AwaitingPassword)
        }
        rule.onNodeWithTag(LegacyImportTestTags.PASSWORD_FIELD).performClick()
        rule.runOnIdle { keyboard?.show() }
        rule.waitUntil(10_000) { ime > 0 }
        val footer = rule.onNodeWithTag(LegacyImportTestTags.PASSWORD_CANCEL).assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(footer.bottom.value * density <= height - ime + 1)
        capture("06-legacy-password-keyboard", fullWindow = true)
    }

    @Test fun exportPinFooterRemainsReachableWithLargeText() {
        content(fontScale = 1.5f, narrow = true) {
            ExportVaultScreen(ExportImportViewModel.ExportState.AwaitingPin, {}, {}, { _, _ -> null }, {}, {}, {}, onBack = {})
        }
        rule.onNodeWithTag(ExportImportTestTags.EXPORT_CONFIRM_FIELD).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(ExportImportTestTags.EXPORT_SUBMIT).assertIsDisplayed().assertIsNotEnabled()
        capture("07-export-pin-large")
    }

    @Test fun darkLegacyPreviewKeepsTheSameHierarchy() {
        content(dark = true) { legacy(LegacyImportViewModel.State.Preview(legacyPreview)) }
        capture("08-dark-legacy-preview")
    }
}
