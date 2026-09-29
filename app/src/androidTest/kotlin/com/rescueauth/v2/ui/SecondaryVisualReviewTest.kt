package com.rescueauth.v2.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rescueauth.v2.exportimport.ExportImportViewModel
import com.rescueauth.v2.legacyimport.LegacyImportViewModel
import com.rescueauth.v2.search.SearchResult
import com.rescueauth.v2.ui.authenticator.RecoveryUiState
import com.rescueauth.v2.ui.developer.DeveloperDetailUiState
import com.rescueauth.v2.ui.developer.DeveloperFormState
import com.rescueauth.v2.ui.model.DeveloperDetailUi
import com.rescueauth.v2.ui.model.RecoveryCodeSetUi
import com.rescueauth.v2.ui.model.RecoveryCodeUi
import com.rescueauth.v2.ui.screens.about.AboutScreen
import com.rescueauth.v2.ui.screens.authenticator.RecoveryCodesScreen
import com.rescueauth.v2.ui.screens.developer.DeveloperDetailScreen
import com.rescueauth.v2.ui.screens.developer.DeveloperFormScreen
import com.rescueauth.v2.ui.screens.exportimport.ExportVaultScreen
import com.rescueauth.v2.ui.screens.exportimport.ImportNativePackageScreen
import com.rescueauth.v2.ui.screens.legacyimport.LegacyImportScreen
import com.rescueauth.v2.ui.screens.search.SearchScreen
import com.rescueauth.v2.ui.search.SearchUiState
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import com.rescueauth.v2.update.UpdateUiState
import java.io.File
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

/**
 * Native screenshot tour of secondary pages. Only invented metadata is supplied;
 * secret/recovery values stay empty, reveal callbacks do nothing, and no database,
 * authentication, file picker, network request or production route is entered.
 * This is visual evidence, not a substitute for route or security integration tests.
 */
@RunWith(AndroidJUnit4::class)
class SecondaryVisualReviewTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun content(dark: Boolean = false, fontScale: Float = 1f, chinese: Boolean = true, body: @Composable () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            rule.activity.enableEdgeToEdge()
            // Mirror RescueAuthAppearance's actual app-window treatment for full-window sheet captures.
            WindowCompat.getInsetsController(rule.activity.window, rule.activity.window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        rule.setContent {
            val base = LocalContext.current
            val config = Configuration(base.resources.configuration).apply {
                setLocales(LocaleList(if (chinese) Locale.SIMPLIFIED_CHINESE else Locale.ENGLISH))
            }
            CompositionLocalProvider(
                LocalContext provides base.createConfigurationContext(config),
                LocalConfiguration provides config,
                LocalActivityResultRegistryOwner provides rule.activity,
                LocalOnBackPressedDispatcherOwner provides rule.activity,
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
            ) {
                RescueAuthTheme(darkTheme = dark, content = body)
            }
        }
    }

    private fun capture(name: String, dialog: Boolean = false) {
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
        // Dialog window transitions run on Android's real clock, outside the Compose test clock.
        if (dialog) InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5_000)
        val directory = File(checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "secondary-ui-review").apply { mkdirs() }
        val bitmap = if (dialog) checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            else rule.onRoot().captureToImage().asAndroidBitmap()
        try {
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }

    @Test fun recoveryCodes() {
        content {
            RecoveryCodesScreen(
                uiState = RecoveryUiState(
                    accountId = "review-account", providerName = "GitHub", accountName = "Personal account",
                    sets = listOf(RecoveryCodeSetUi("review-set", "GitHub recovery", 1, 4,
                        (1..4).map { RecoveryCodeUi("review-code-$it", "", isUsed = it == 1) })),
                ),
                onBack = {}, onAddClick = {}, onRevealToggle = {}, onCopyCode = {},
                onCopyAll = {}, onCopyRemaining = {}, onMarkUsed = {}, onMarkUnused = {},
                onEdit = {}, onDelete = {}, onMove = {},
            )
        }
        rule.onNodeWithText("GitHub recovery").assertIsDisplayed()
        capture("24-recovery-codes")
        rule.onNodeWithTag("recovery_expand_review-set").performClick()
        rule.onAllNodesWithText("••••••••").assertCountEquals(4)
        capture("24b-recovery-expanded")
        rule.onNodeWithContentDescription("操作").performClick()
        capture("24c-recovery-menu", dialog = true)
    }

    private fun detailScreen(detail: DeveloperDetailUi, dark: Boolean = false, fontScale: Float = 1f, chinese: Boolean = true) {
        content(dark, fontScale, chinese) {
            DeveloperDetailScreen(
                uiState = DeveloperDetailUiState(loading = false, detail = detail),
                onBack = {}, onRevealApiKey = {}, onRevealApiSecret = {},
                onCopyApiKey = {}, onCopyApiSecret = {}, onRevealPrivateKey = {}, onCopyPrivateKey = {},
                onRevealPassphrase = {}, onCopyPassphrase = {}, onRevealGeneric = {}, onCopyGeneric = {},
                getRevealedValue = { null }, isRevealed = { false }, onEdit = {}, onDelete = {},
                onRevealStorePassword = {}, onCopyStorePassword = {}, onRevealKeyPassword = {},
                onCopyKeyPassword = {}, onExportKeystore = {}, onCopyKeyProperties = {},
                onRevealEnvVar = {}, onCopyEnvVar = {},
            )
        }
        rule.onNodeWithText(detail.title).assertIsDisplayed()
    }

    @Test fun apiCredential() {
        detailScreen(DeveloperDetailUi.ApiCredential("review-api", "Production", "GitHub", "Demo workspace"))
        capture("25-api-detail")
    }

    @Test fun sshKey() {
        detailScreen(DeveloperDetailUi.SshKey("review-ssh", "Deployment", "Demo deploy key", publicKeyPresent = true))
        capture("26-ssh-detail")
    }

    @Test fun signingKey() {
        detailScreen(DeveloperDetailUi.AndroidSigningKey("review-signing", "Demo release key",
            "Demo project", "com.example.demo", "demo-release.jks", "demo"))
        capture("27-signing-detail")
        rule.onNodeWithText("导出密钥库").performScrollTo().assertIsDisplayed()
        val copy = rule.onNodeWithText("复制 key.properties").performScrollTo().assertIsDisplayed()
        assertTrue(copy.getUnclippedBoundsInRoot().bottom <= rule.onNodeWithTag("page_action_bar").getUnclippedBoundsInRoot().top)
    }

    @Test fun environmentVariables() {
        detailScreen(DeveloperDetailUi.EnvironmentVariableSet("review-env", "Staging environment",
            "Demo workspace", listOf("API_ENDPOINT", "ACCESS_TOKEN", "DATABASE_URL")))
        capture("28-env-detail")
    }

    @Test fun genericSecret() {
        detailScreen(DeveloperDetailUi.GenericSecret("review-secret", "Personal project", listOf("Username", "Password")))
        capture("29-generic-detail")
    }

    @Test fun addApiCredential() {
        content { apiForm() }
        rule.onNodeWithText("Demo credential").assertIsDisplayed()
        capture("30-add-api")
        rule.onNodeWithTag("developer_form_next").performClick()
        rule.onNodeWithTag("developer_form_save").assertIsDisplayed()
        capture("30b-add-api-credentials")
    }

    @Composable private fun apiForm() {
        DeveloperFormScreen(
                form = DeveloperFormState(title = "Demo credential", serviceName = "GitHub", accountName = "Demo workspace"),
                onBack = {}, onTitleChange = {}, onNotesChange = {}, onServiceNameChange = {}, onAccountNameChange = {},
                onApiKeyChange = {}, onApiSecretChange = {}, onKeyNameChange = {}, onPublicKeyChange = {},
                onPrivateKeyChange = {}, onPassphraseChange = {}, onFieldLabelChange = { _, _ -> },
                onFieldValueChange = { _, _ -> }, onAddField = {}, onRemoveField = {}, onProjectNameChange = {},
                onPackageNameChange = {}, onStorePasswordChange = {}, onKeyAliasChange = {}, onKeyPasswordChange = {},
                onKeystoreSelected = { _, _ -> }, onClearKeystore = {}, onVariableNameChange = { _, _ -> },
                onVariableValueChange = { _, _ -> }, onAddVariable = {}, onRemoveVariable = {}, onSubmit = {},
        )
    }

    @Test fun darkDetailAndProtectionExplanation() {
        detailScreen(DeveloperDetailUi.GenericSecret("review-dark", "Personal project", listOf("Username", "Password")), dark = true)
        rule.onNodeWithTag("developer_detail_edit").assertIsDisplayed()
        capture("36-dark-detail")
        rule.onNodeWithTag("developer_protection_info").performClick()
        rule.onNodeWithTag("developer_protection_explanation").assertIsDisplayed()
        capture("37-dark-protection-explanation", dialog = true)
        rule.onNodeWithTag("developer_protection_close").performClick()
        rule.onNodeWithTag("developer_detail_delete").assertIsDisplayed()
    }

    @Test fun lightProtectionExplanation() {
        detailScreen(DeveloperDetailUi.GenericSecret("review-info", "Personal project", listOf("Username", "Password")))
        rule.onNodeWithTag("developer_protection_info").performClick()
        rule.onNodeWithTag("developer_protection_explanation").assertIsDisplayed()
        capture("38-protection-explanation", dialog = true)
    }

    @Test fun largeEnglishDetail() {
        detailScreen(DeveloperDetailUi.GenericSecret("review-large", "Personal project", listOf("Username", "Password")), fontScale = 1.5f, chinese = false)
        rule.onNodeWithTag("developer_detail_edit").assertIsDisplayed()
        rule.onNodeWithTag("developer_detail_delete").assertIsDisplayed()
        capture("39-large-detail")
    }

    @Test fun largeEnglishForm() {
        content(fontScale = 1.5f, chinese = false) { apiForm() }
        rule.onNodeWithTag("developer_form_next").performClick()
        rule.onNodeWithText("API Secret").performScrollTo()
        rule.onNodeWithTag("developer_form_save").assertIsDisplayed()
        rule.onNodeWithTag("developer_form_previous").assertIsDisplayed()
        capture("40-large-form")
    }

    @Test fun darkForm() {
        content(dark = true) { apiForm() }
        rule.onNodeWithTag("developer_form_next").performClick()
        capture("41-dark-form")
    }

    @Test fun longDetailKeepsTheFooterWhileScrolling() {
        detailScreen(DeveloperDetailUi.EnvironmentVariableSet("review-long", "Staging environment",
            "Demo workspace", (1..16).map { "VARIABLE_$it" }))
        val before = rule.onNodeWithTag("developer_detail_edit").getUnclippedBoundsInRoot()
        repeat(2) { rule.onNode(hasScrollAction()).performTouchInput { swipeUp() } }
        rule.onNodeWithTag("developer_detail_edit").assertIsDisplayed()
        rule.onNodeWithTag("developer_detail_delete").assertIsDisplayed()
        assertEquals(before, rule.onNodeWithTag("developer_detail_edit").getUnclippedBoundsInRoot())
        capture("42-scrolled-environment")
    }

    @Test fun formActionsStayAboveTheKeyboard() {
        var imeHeight = 0
        var windowHeight = 0
        var density = 1f
        var keyboard: SoftwareKeyboardController? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            rule.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        content {
            density = LocalDensity.current.density
            imeHeight = WindowInsets.ime.getBottom(LocalDensity.current)
            windowHeight = LocalView.current.height
            keyboard = LocalSoftwareKeyboardController.current
            apiForm()
        }
        rule.onNodeWithTag("developer_form_next").performClick()
        rule.onNodeWithText("GitHub").performClick()
        rule.onNodeWithText("GitHub").assertIsFocused()
        rule.runOnIdle { keyboard?.show() }
        rule.waitUntil(timeoutMillis = 10_000) { imeHeight > 0 }
        rule.onNodeWithTag("developer_form_save").assertIsDisplayed()
        rule.onNodeWithTag("developer_form_previous").assertIsDisplayed()
        val bottom = rule.onNodeWithTag("developer_form_save").getUnclippedBoundsInRoot().bottom.value * density
        assertTrue("Footer overlaps the IME", bottom <= windowHeight - imeHeight + 1)
        capture("43-form-keyboard", dialog = true)
    }

    @Test fun exportScope() {
        content {
            ExportVaultScreen(state = ExportImportViewModel.ExportState.Idle,
                onSelectScope = {}, onConfirmSelection = {}, onSubmitPin = { _, _ -> null },
                onChooseDestination = {}, onCancel = {}, onDismissResult = {}, onBack = {})
        }
        rule.onNodeWithTag("export_scope_full").assertIsDisplayed()
        capture("31-export-scope")
    }

    @Test fun nativeImport() {
        content {
            ImportNativePackageScreen(state = ExportImportViewModel.ImportState.Idle,
                onPickDocument = {}, onDecode = {}, onCancelPin = {}, onChooseScope = {},
                onConfirmSelection = {}, onConfirm = {}, onCancel = {}, onDismissResult = {}, onBack = {})
        }
        capture("32-native-import")
    }

    @Test fun legacyImport() {
        content {
            LegacyImportScreen(state = LegacyImportViewModel.State.Idle,
                onPickFile = {}, onSubmitPassword = { false }, onRetryPassword = {},
                onCancelPassword = {}, onConfirm = {}, onCancel = {}, onDismissResult = {}, onBack = {})
        }
        rule.onNodeWithTag("legacy_import_pick_file").assertIsDisplayed()
        capture("33-legacy-import")
    }

    @Test fun about() {
        content {
            AboutScreen(versionName = "1.0.0", versionCode = 10000, state = UpdateUiState.Idle,
                onCheckForUpdates = {}, onOpenReleasePage = {}, onBack = {})
        }
        rule.onNodeWithTag("about_check_button").assertIsDisplayed()
        capture("34-about")
    }

    @Test fun search() {
        content {
            SearchScreen(uiState = SearchUiState(query = "GitHub", unlocked = true, results = listOf(
                SearchResult.Provider("GitHub", null, "review-provider", accountCount = 2),
                SearchResult.Account("Personal account", "GitHub", "review-account", false, providerName = "GitHub"),
            )))
        }
        rule.onNodeWithTag("search_result_provider_review-provider").assertIsDisplayed()
        capture("35-search")
    }
}
