package com.rescueauth.v2.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rescueauth.v2.exportimport.ExportImportViewModel
import com.rescueauth.v2.legacyimport.LegacyImportViewModel
import com.rescueauth.v2.ui.authenticator.AddMode
import com.rescueauth.v2.ui.authenticator.AddTotpFormState
import com.rescueauth.v2.ui.screens.authenticator.AddTotpSheet
import com.rescueauth.v2.ui.screens.exportimport.*
import com.rescueauth.v2.ui.screens.legacyimport.*
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import java.io.File
import java.util.Locale
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual input screens with empty fields only; no file picker, vault, or secret fixture. */
@RunWith(AndroidJUnit4::class)
class PasswordVisibilityInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var original: Configuration? = null
    private val masked = SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)

    @Suppress("DEPRECATION")
    private fun show(dark: Boolean = false, large: Boolean = false, english: Boolean = false, body: @Composable () -> Unit) {
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
                RescueAuthTheme(darkTheme = dark, content = body)
            }
        }
    }

    @After @Suppress("DEPRECATION") fun restoreConfiguration() {
        original?.let { config -> InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val resources = rule.activity.resources
            resources.updateConfiguration(config, resources.displayMetrics)
        } }
    }

    private fun checkButton(fieldTag: String, buttonTag: String) {
        val button = rule.onNodeWithTag(buttonTag).performScrollTo().assertIsDisplayed().getUnclippedBoundsInRoot()
        val field = rule.onNodeWithTag(fieldTag).getUnclippedBoundsInRoot()
        // Compare physical pixels: subtracting dp coordinates at density 2.625 can
        // represent an exact 48 dp touch target as 47.99999 dp.
        with(rule.density) {
            val minimum = 48.dp.roundToPx()
            assertTrue("Eye button bounds: $button", (button.right - button.left).roundToPx() >= minimum &&
                (button.bottom - button.top).roundToPx() >= minimum)
        }
        assertTrue(button.left >= field.left && button.right <= field.right)
        assertTrue(button.top >= field.top && button.bottom <= field.bottom)
    }
    private fun toggle(tag: String) = rule.onNodeWithTag(tag).performTouchInput { click() }
    private fun capture(name: String) {
        rule.mainClock.advanceTimeBy(600); rule.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(350, 5_000)
        val bitmap = checkNotNull(automation.takeScreenshot())
        val output = checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir"))
        val file = File(output, "password-visibility/$name.png").apply { parentFile!!.mkdirs() }
        try { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { bitmap.recycle() }
    }

    @Test fun legacyPasswordEyeIsInsideAndBothActionsHaveAccessibleLabels() {
        show { LegacyImportScreen(LegacyImportViewModel.State.AwaitingPassword, {}, { false }, {}, {}, {}, {}, {}, {}) }
        checkButton(LegacyImportTestTags.PASSWORD_FIELD, "legacy_password_visibility")
        val field = rule.onNodeWithTag(LegacyImportTestTags.PASSWORD_FIELD)
        field.assert(masked)
        rule.onNodeWithContentDescription("显示").assertExists()
        rule.onNodeWithText("显示").assertDoesNotExist()
        capture("legacy-hidden")
        toggle("legacy_password_visibility"); field.assert(masked.not())
        rule.onNodeWithContentDescription("隐藏").assertExists()
        capture("legacy-visible")
        toggle("legacy_password_visibility"); field.assert(masked)
    }

    @Test fun exportPinAndConfirmationUseSeparateEyeControls() {
        show { ExportVaultScreen(ExportImportViewModel.ExportState.AwaitingPin, {}, {}, { _, _ -> null }, {}, {}, {}) }
        checkButton(ExportImportTestTags.EXPORT_PIN_FIELD, "pin_visibility")
        checkButton(ExportImportTestTags.EXPORT_CONFIRM_FIELD, "pin_confirm_visibility")
        toggle("pin_visibility")
        rule.onNodeWithTag(ExportImportTestTags.EXPORT_PIN_FIELD).assert(masked.not())
        rule.onNodeWithTag(ExportImportTestTags.EXPORT_CONFIRM_FIELD).assert(masked)
        capture("export-independent")
        toggle("pin_visibility")
        capture("export-hidden")
    }

    @Test fun importPinRemainsReachableInDarkLargeText() {
        show(dark = true, large = true) {
            ImportNativePackageScreen(ExportImportViewModel.ImportState.AwaitingPin, {}, {}, {}, {}, {}, {}, {}, {})
        }
        checkButton(ExportImportTestTags.IMPORT_PIN_FIELD, "pin_visibility")
        capture("import-dark-large")
        toggle("pin_visibility")
        rule.onNodeWithTag(ExportImportTestTags.IMPORT_PIN_FIELD).assert(masked.not())
        toggle("pin_visibility")
        rule.onNodeWithTag(ExportImportTestTags.IMPORT_PIN_FIELD).assert(masked)
    }

    @Test fun manualTotpSecretHasAnEyeWithoutSubmittingTheForm() {
        var submitted = false
        show(english = true) { AddTotpSheet(AddTotpFormState(mode = AddMode.MANUAL), {}, {}, onUriChange = {},
            onProviderChange = {}, onAccountNameChange = {}, onSecretChange = {}, onAlgorithmChange = {},
            onDigitsChange = {}, onPeriodChange = {}, onSubmit = { submitted = true }) }
        checkButton("add_totp_secret", "totp_secret_visibility")
        capture("totp-hidden")
        toggle("totp_secret_visibility")
        rule.onNodeWithTag("add_totp_secret").assert(masked.not())
        toggle("totp_secret_visibility")
        rule.onNodeWithTag("add_totp_secret").assert(masked)
        assertFalse(submitted)
    }
}
