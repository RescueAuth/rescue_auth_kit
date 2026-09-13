package com.rescueauth.v2.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Compose renders with invented metadata only. No database, credentials or auth bypass. */
@RunWith(AndroidJUnit4::class)
class StudioVisualReviewTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

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

    private fun content(dark: Boolean = false, fontScale: Float = 1f, chinese: Boolean = true, body: @Composable () -> Unit) {
        rule.activity.enableEdgeToEdge()
        rule.setContent {
            val base = LocalContext.current
            val config = Configuration(base.resources.configuration).apply {
                setLocales(LocaleList(if (chinese) Locale.SIMPLIFIED_CHINESE else Locale.ENGLISH))
            }
            val localized = base.createConfigurationContext(config)
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config,
                LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                RescueAuthTheme(darkTheme = dark, content = body)
            }
        }
    }

    @Composable private fun shell(tab: Int, body: @Composable (Modifier) -> Unit) {
        Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), bottomBar = {
            RescueAuthNavigationBar(isSelected = { it == TopLevelDestinations.all[tab] }, onNavigate = {})
        }) { body(Modifier.padding(it)) }
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        val directory = File(rule.activity.getExternalFilesDir(null), "ui-review").apply { mkdirs() }
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
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
                onTransferClick = { transfer = true }, onExportClick = {}, onImportClick = {}, onLegacyImportClick = {}) }
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
        rule.onNodeWithTag(StartupLockTestTags.INTRO_CONTINUE_BUTTON).performScrollTo().performClick()
        assertTrue(continued)
        capture("10-large-text")
    }
}
