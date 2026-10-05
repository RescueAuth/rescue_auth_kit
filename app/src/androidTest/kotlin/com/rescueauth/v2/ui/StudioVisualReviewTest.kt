package com.rescueauth.v2.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rescueauth.v2.ui.authenticator.AuthenticatorUiState
import com.rescueauth.v2.ui.components.RescueAuthNavigationBar
import com.rescueauth.v2.ui.developer.DeveloperListUiState
import com.rescueauth.v2.ui.model.*
import com.rescueauth.v2.ui.navigation.TopLevelDestinations
import com.rescueauth.v2.ui.screens.authenticator.AuthenticatorScreen
import com.rescueauth.v2.ui.screens.developer.DeveloperScreen
import com.rescueauth.v2.ui.screens.settings.SettingsTransferScreen
import com.rescueauth.v2.ui.screens.settings.SettingsScreen
import com.rescueauth.v2.ui.screens.startup.StartupIntroScreen
import com.rescueauth.v2.ui.screens.startup.StartupLockTestTags
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import com.rescueauth.v2.ui.theme.ThemeMode
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Compose renders with invented metadata only. No database, credentials or auth bypass. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class StudioVisualReviewTest {
    // The visual fixture has a reproducible 1x clock; NavigationDockTest also verifies disabled motion.
    private val motionScale = object : MotionDurationScale { override val scaleFactor = 1f }
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>(effectContext = motionScale)

    private val providers = listOf("GitHub", "Google", "Figma", "Linear", "Notion", "Slack").mapIndexed { i, name ->
        ProviderUi("provider:$name", name, (1..2).map { number ->
            AccountUi("$i-$number", name, if (number == 1) "Personal account" else "Workspace", isPinned = i == 0,
                totpCredentials = listOf(TotpCredentialUi("otp:$i:$number", "otp:$i:$number", name, "Demo account",
                    currentCode = null, remainingSeconds = 24, progressFraction = 0.8f)))
        })
    }
    private val entries = DeveloperEntryType.entries.flatMap { type ->
        (1..4).map { i -> DeveloperEntryUi("${type.name}:$i", "${type.name}:$i", type,
            listOf("Production", "Staging", "Personal project", "Deployment")[i - 1], "Demo workspace") }
    }

    private fun content(dark: Boolean? = false, fontScale: Float = 1f, chinese: Boolean = true, body: @Composable () -> Unit) {
        rule.runOnUiThread { rule.activity.enableEdgeToEdge() }
        rule.setContent {
            val base = LocalContext.current
            val config = Configuration(base.resources.configuration).apply {
                setLocales(LocaleList(if (chinese) Locale.SIMPLIFIED_CHINESE else Locale.ENGLISH))
            }
            val localized = base.createConfigurationContext(config)
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config,
                LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                RescueAuthTheme(darkTheme = dark ?: isSystemInDarkTheme(), content = body)
            }
        }
    }

    @Composable private fun shell(tab: Int, body: @Composable (Modifier) -> Unit) {
        Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), bottomBar = {
            RescueAuthNavigationBar(isSelected = { it == TopLevelDestinations.all[tab] }, onNavigate = {})
        }) { body(Modifier.padding(it)) }
    }

    private fun capture(name: String, dialog: Boolean = false) {
        if (dialog) rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.let(::File) ?: checkNotNull(rule.activity.getExternalFilesDir(null))
        val directory = File(output, "ui-review").apply { mkdirs() }
        // A bottom sheet owns a separate window. Capture the synthetic test display
        // for that case so both the sheet and its dimmed background remain visible.
        val bitmap = if (dialog) checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            else rule.onRoot().captureToImage().asAndroidBitmap()
        try {
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }

    @Test fun welcome() {
        var continued = false
        content { StartupIntroScreen(onContinue = { continued = true }, onImportV1 = {}) }
        capture("01-welcome")
        rule.onNodeWithTag(StartupLockTestTags.INTRO_CONTINUE_BUTTON).performScrollTo().performClick()
        assertTrue(continued)
    }

    @Test fun authenticatorDirectoryAndAccount() {
        content {
            var provider by remember { mutableStateOf<String?>(null) }
            var account by remember { mutableStateOf<String?>(null) }
            val screen: @Composable (Modifier) -> Unit = { modifier ->
                key(provider, account) {
                    AuthenticatorScreen(modifier = modifier,
                        uiState = AuthenticatorUiState(loading = false, providers = providers),
                        initialProviderName = provider, initialAccountId = account,
                        onOpenProvider = { provider = it }, onOpenAccount = { account = it },
                        onBack = { if (account != null) account = null else provider = null },
                        onAddClick = {}, onOpenSearch = {}, onAddProviderClick = {}, onCopyClick = {})
                }
            }
            if (provider == null && account == null) shell(0, screen) else screen(Modifier)
        }
        rule.onNodeWithContentDescription("拾遗坊").assertIsDisplayed()
        capture("02-authenticator")
        rule.onNodeWithTag("provider_row_GitHub").performScrollTo().performClick()
        rule.onNodeWithText("Personal account").assertIsDisplayed()
        capture("03-accounts")
        rule.onNodeWithTag("account_row_0-1").performClick()
        capture("04-credential")
    }

    @Test fun developerDirectory() {
        content {
            var category by remember { mutableStateOf<DeveloperEntryType?>(null) }
            val screen: @Composable (Modifier) -> Unit = { modifier ->
                key(category) { DeveloperScreen(modifier = modifier,
                    uiState = DeveloperListUiState(loading = false, entries = entries),
                    onOpenCategory = { category = it }, categoryType = category,
                    onBack = { category = null }, onAddClick = {}, onEntryClick = {}) }
            }
            if (category == null) shell(1, screen) else screen(Modifier)
        }
        rule.onNodeWithText("Production").assertDoesNotExist()
        capture("05-developer")
        rule.onNodeWithTag("developer_category_API_CREDENTIAL").performClick()
        rule.onNodeWithText("Production").assertIsDisplayed()
        capture("06-developer-list")
    }

    @Test fun settings() {
        content {
            var transfer by remember { mutableStateOf(false) }
            if (transfer) SettingsTransferScreen(onBack = { transfer = false }, onExportClick = {}, onImportClick = {}, onLegacyImportClick = {})
            else shell(2) { SettingsScreen(modifier = it, versionName = "1.0.0", onAboutClick = {},
                onTransferClick = { transfer = true }, onExportClick = {}, onImportClick = {}, onLegacyImportClick = {},
                onThemeModeChange = {}) }
        }
        capture("07-settings")
        rule.onNodeWithTag("settings_transfer_row").performClick()
        capture("08-transfer")
    }

    @Test fun darkDirectory() {
        content(dark = true) { shell(1) { DeveloperScreen(modifier = it,
            uiState = DeveloperListUiState(loading = false, entries = entries), onAddClick = {}) } }
        capture("09-dark")
    }

    @Test fun largeTextEnglishWelcome() {
        var continued = false
        content(fontScale = 1.5f, chinese = false) { StartupIntroScreen(onContinue = { continued = true }, onImportV1 = {}) }
        rule.onNodeWithTag(StartupLockTestTags.INTRO_CONTINUE_BUTTON).performScrollTo()
        capture("10-large-text")
        rule.onNodeWithTag(StartupLockTestTags.INTRO_CONTINUE_BUTTON).performScrollTo().performClick()
        assertTrue(continued)
    }

    @Test fun englishAuthenticator() {
        content(chinese = false) { shell(0) { AuthenticatorScreen(modifier = it,
            uiState = AuthenticatorUiState(loading = false, providers = providers),
            onAddClick = {}, onOpenSearch = {}) } }
        rule.onNodeWithContentDescription("Add").assertIsDisplayed()
        capture("12-english-authenticator")
    }

    @Test fun darkAuthenticatorBanner() {
        content(dark = true) { shell(0) { AuthenticatorScreen(modifier = it,
            uiState = AuthenticatorUiState(loading = false, providers = providers), onAddClick = {}, onOpenSearch = {}) } }
        rule.onNodeWithTag("vault_brand_banner").assertHeightIsEqualTo(88.dp)
        rule.onNodeWithContentDescription("拾遗坊").assertIsDisplayed()
        rule.onNodeWithContentDescription("12 个账户").assertIsDisplayed()
        capture("22-dark-authenticator-banner")
    }

    @Test fun narrowChineseBannerKeepsWordmarkSeparateFromCount() {
        content(fontScale = 1.5f) { shell(0) { AuthenticatorScreen(modifier = it.width(320.dp),
            uiState = AuthenticatorUiState(loading = false, providers = providers), onAddClick = {}, onOpenSearch = {}) } }
        rule.onAllNodesWithContentDescription("拾遗坊").assertCountEquals(1)
        val name = rule.onNodeWithContentDescription("拾遗坊").assertIsDisplayed().getUnclippedBoundsInRoot()
        val count = rule.onNodeWithContentDescription("12 个账户").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("The artwork must not overlap the account summary", name.right < count.left)
        rule.onNodeWithTag("vault_brand_banner").assertHeightIsEqualTo(120.dp)
        capture("24-narrow-chinese-wordmark")
    }

    @Test fun largeTextAuthenticatorBanner() {
        content(fontScale = 1.5f, chinese = false) { shell(0) { AuthenticatorScreen(modifier = it,
            uiState = AuthenticatorUiState(loading = false, providers = providers), onAddClick = {}, onOpenSearch = {}) } }
        rule.onNodeWithTag("vault_brand_banner").assertHeightIsEqualTo(120.dp)
        rule.onNodeWithContentDescription("12 accounts").assertIsDisplayed()
        rule.onNode(hasText("RescueAuth") and hasAnyAncestor(hasTestTag("vault_brand_banner"))).assertIsDisplayed()
        capture("23-large-authenticator-banner")
    }

    @Test fun largeTextEnglishDeveloper() {
        content(fontScale = 1.5f, chinese = false) { shell(1) { DeveloperScreen(modifier = it,
            uiState = DeveloperListUiState(loading = false, entries = entries), onAddClick = {}) } }
        rule.onNodeWithContentDescription("Add").assertIsDisplayed()
        capture("13-large-developer")
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("developer_category_ENVIRONMENT_VARIABLE_SET"))
        rule.onNodeWithTag("developer_category_ENVIRONMENT_VARIABLE_SET").performClick()
        rule.onNodeWithText("Production").assertIsDisplayed()
    }

    @Test fun largeTextEnglishSettingsAndTransfer() {
        content(fontScale = 1.5f, chinese = false) {
            var transfer by remember { mutableStateOf(false) }
            if (transfer) SettingsTransferScreen(onBack = { transfer = false }, onExportClick = {}, onImportClick = {}, onLegacyImportClick = {})
            else shell(2) { SettingsScreen(modifier = it, versionName = "1.0.0", onAboutClick = {},
                onTransferClick = { transfer = true }, onExportClick = {}, onImportClick = {}, onLegacyImportClick = {},
                onThemeModeChange = {}) }
        }
        capture("14-large-settings")
        rule.onNodeWithTag("settings_transfer_row").performScrollTo().performClick()
        rule.onNodeWithText("Per-export PIN").assertIsDisplayed()
        rule.onNodeWithText("Import Legacy v1 Vault").performScrollTo().assertIsDisplayed()
        capture("15-large-transfer")
    }

    @Test fun appearancePicker() {
        val mode = mutableStateOf(ThemeMode.SYSTEM)
        var dark = false
        content {
            dark = mode.value.isDark(isSystemInDarkTheme())
            RescueAuthTheme(darkTheme = dark) {
                shell(2) { SettingsScreen(modifier = it, versionName = "1.0.0", onAboutClick = {},
                    themeMode = mode.value, onThemeModeChange = { selected -> mode.value = selected }) }
            }
        }
        rule.onNodeWithTag("settings_appearance_row").performScrollTo().performClick()
        rule.onNodeWithTag("theme_mode_SYSTEM").assertIsSelected()
        rule.onNodeWithTag("theme_mode_SYSTEM").assert(hasText("跟随系统"))
        rule.onNodeWithTag("theme_mode_LIGHT").assert(hasText("浅色"))
        rule.onNodeWithTag("theme_mode_DARK").assert(hasText("深色"))
        capture("16-appearance-picker", dialog = true)
        rule.onNodeWithTag("theme_mode_DARK").performClick()
        rule.runOnIdle { assertEquals(ThemeMode.DARK, mode.value); assertTrue(dark) }
        capture("17-dark-settings")
        rule.onNodeWithTag("settings_appearance_row").performScrollTo().performClick()
        rule.onNodeWithTag("theme_mode_LIGHT").performClick()
        rule.runOnIdle { assertEquals(ThemeMode.LIGHT, mode.value); assertFalse(dark) }
        rule.onNodeWithTag("settings_appearance_row").performScrollTo().performClick()
        rule.onNodeWithTag("theme_mode_SYSTEM").performClick()
        rule.runOnIdle { assertEquals(ThemeMode.SYSTEM, mode.value) }
    }

    /** Run once in each actual emulator night mode, without forcing a screenshot-only palette. */
    @Test fun systemAppearance() {
        var systemDark = false
        var paletteDark = false
        content(dark = null) {
            systemDark = isSystemInDarkTheme()
            paletteDark = androidx.compose.material3.MaterialTheme.colorScheme.background.luminance() < 0.5f
            shell(2) { SettingsScreen(modifier = it, versionName = "1.0.0", onAboutClick = {}, onThemeModeChange = {}) }
        }
        rule.runOnIdle {
            assertEquals(systemDark, paletteDark)
            InstrumentationRegistry.getArguments().getString("expectedNight")?.let {
                assertEquals(it.toBooleanStrict(), systemDark)
            }
        }
        capture(if (systemDark) "19-system-dark" else "18-system-light")
    }

    /** Verifies real measured motion; an optional run records this metadata-only demo for review. */
    @Test fun dockMotion() {
        val selected = mutableStateOf(TopLevelDestinations.AUTHENTICATOR)
        content {
            Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), bottomBar = {
                RescueAuthNavigationBar(isSelected = { selected.value == it }, onNavigate = { selected.value = it })
            }) { padding ->
                val modifier = Modifier.padding(padding)
                when (selected.value) {
                    TopLevelDestinations.AUTHENTICATOR -> AuthenticatorScreen(modifier = modifier,
                        uiState = AuthenticatorUiState(loading = false, providers = providers), onAddClick = {}, onOpenSearch = {})
                    TopLevelDestinations.DEVELOPER -> DeveloperScreen(modifier = modifier,
                        uiState = DeveloperListUiState(loading = false, entries = entries), onAddClick = {})
                    else -> SettingsScreen(modifier = modifier, versionName = "1.0.0", onAboutClick = {}, onThemeModeChange = {})
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("navigation_dock").assertHeightIsEqualTo(56.dp)
        val record = InstrumentationRegistry.getArguments().getString("recordMotion") == "true"
        val recording = if (record) InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "screenrecord --size 540x1200 --bit-rate 2000000 --time-limit 7 /sdcard/Download/rescueauth-dock-motion.mp4",
        ) else null
        rule.mainClock.autoAdvance = false
        fun left(tag: String) = rule.onNodeWithTag(tag).getUnclippedBoundsInRoot().left.value
        fun frames(count: Int) {
            repeat(count) {
                val started = android.os.SystemClock.uptimeMillis()
                rule.mainClock.advanceTimeByFrame()
                if (record) android.os.SystemClock.sleep((16L - (android.os.SystemClock.uptimeMillis() - started)).coerceAtLeast(0L))
            }
        }
        try {
            if (record) frames(35)
            listOf(TopLevelDestinations.DEVELOPER, TopLevelDestinations.SETTINGS, TopLevelDestinations.AUTHENTICATOR).forEach { target ->
                val start = left("navigation_indicator")
                val end = left(target.testTag)
                rule.onNodeWithTag(target.testTag).performClick()
                frames(6)
                rule.onNodeWithTag(target.testTag).assertIsSelected()
                val middle = left("navigation_indicator")
                assertTrue("The indicator must travel rather than jump", middle > minOf(start, end) && middle < maxOf(start, end))
                frames(18)
                assertEquals(end, left("navigation_indicator"), 1f)
                if (record) frames(35)
            }
        } finally {
            rule.mainClock.autoAdvance = true
            // screenrecord stops itself after seven seconds and finalizes the MP4 before returning EOF.
            recording?.let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { output -> output.readBytes() } }
        }
    }

    @Test fun removedAccountRetainsItsBackAction() {
        val state = mutableStateOf(AuthenticatorUiState(loading = false, providers = providers))
        var backedOut = false
        content {
            AuthenticatorScreen(
                uiState = state.value, initialAccountId = "0-1",
                onBack = { backedOut = true }, onAddClick = {},
            )
        }
        rule.onNodeWithText("Personal account").assertIsDisplayed()
        rule.runOnIdle { state.value = AuthenticatorUiState(loading = false) }
        rule.onNodeWithTag("vault_item_unavailable").assertIsDisplayed()
        rule.onNodeWithTag("vault_add_menu").assertDoesNotExist()
        capture("11-removed-account")
        rule.onNodeWithTag("authenticator_back").performClick()
        assertTrue(backedOut)
    }

}
