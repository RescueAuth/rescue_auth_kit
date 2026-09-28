package com.rescueauth.v2.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
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
import com.rescueauth.v2.ui.model.*
import com.rescueauth.v2.ui.screens.authenticator.AuthenticatorScreen
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Native content review with fictional metadata and fixed example codes; never a real Vault. */
@RunWith(AndroidJUnit4::class)
class AccountPresentationVisualTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var opened: String? = null
    private var added = 0
    private var copied = 0

    private fun show(detail: Boolean, dark: Boolean = false, large: Boolean = false, empty: Boolean = false) {
        rule.activity.enableEdgeToEdge()
        val accounts = if (empty) emptyList() else listOf(
            AccountUi("personal", "GitHub", if (large) "personal.account@example.test" else "个人账户", isPinned = true,
                totpCredentials = listOf(TotpCredentialUi("otp", "otp", "GitHub", "Demo", digits = if (large) 8 else 6,
                    currentCode = if (large) "12345678" else "123456", remainingSeconds = 21, progressFraction = .7f)),
                recoverySets = listOf(RecoveryCodeSetUi("first", "Demo A", 0, 16), RecoveryCodeSetUi("second", "Demo B", 0, 16))),
            AccountUi("work", "GitHub", if (large) "work@example.test" else "工作账户",
                totpCredentials = listOf(TotpCredentialUi("work-otp", "work-otp", "GitHub", "Demo")),
                recoverySets = listOf(RecoveryCodeSetUi("work-recovery", "Demo", 0, 8))),
        )
        rule.setContent {
            val base = LocalContext.current
            val config = Configuration(base.resources.configuration).apply { setLocales(LocaleList(if (large) Locale.ENGLISH else Locale.SIMPLIFIED_CHINESE)) }
            CompositionLocalProvider(LocalContext provides base.createConfigurationContext(config), LocalConfiguration provides config,
                LocalDensity provides Density(LocalDensity.current.density, if (large) 1.5f else 1f)) {
                RescueAuthTheme(darkTheme = dark) {
                    Box(if (large) Modifier.width(320.dp).fillMaxHeight() else Modifier.fillMaxSize()) {
                        AuthenticatorScreen(uiState = AuthenticatorUiState(loading = false, providers = listOf(ProviderUi("github", "GitHub", accounts))),
                            initialAccountId = if (detail) "personal" else null, initialProviderName = if (detail) null else "GitHub",
                            onBack = {}, onAddClick = { added++ }, onAddAccount = { added++ }, onOpenAccount = { opened = it },
                            onOpenRecovery = { opened = it }, onCopyClick = { copied++ }, onDeleteClick = {}, onRenameAccount = {}, onTogglePin = {})

                    }
                }
            }
        }
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        val output = checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir"))
        val file = File(output, "account-review/$name.png").apply { parentFile!!.mkdirs() }
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        try { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { bitmap.recycle() }
    }

    @Test fun providerLightAndAccountNavigation() {
        show(detail = false)
        rule.onNodeWithTag("account_row_personal").assertIsDisplayed()
        capture("provider-light")
        rule.onNodeWithTag("account_row_personal").performTouchInput { click() }
        rule.runOnIdle { assertEquals("personal", opened) }
        rule.onNodeWithText("添加账户").assertDoesNotExist()
        rule.onNodeWithTag("global_add").performTouchInput { click() }
        rule.onNodeWithTag("add_account").performTouchInput { click() }
        rule.runOnIdle { assertEquals(1, added) }
    }

    @Test fun accountLightAndCopyRecoveryActions() {
        show(detail = true)
        rule.onNodeWithText("GitHub").assertDoesNotExist()
        rule.onNodeWithText("123 456").assertIsDisplayed()
        rule.onNodeWithText("2 组 · 32 个可用").assertIsDisplayed()
        capture("account-light")
        rule.onNodeWithTag("totp_copy_otp").performTouchInput { click() }
        rule.runOnIdle { assertEquals(1, copied) }
        rule.onNodeWithTag("account_recovery").performTouchInput { click() }
        rule.runOnIdle { assertEquals("personal", opened) }
    }

    @Test fun accountDarkKeepsCodeAndActionsLegible() {
        show(detail = true, dark = true)
        rule.onNodeWithTag("totp_copy_otp").assertIsDisplayed()
        capture("account-dark")
    }

    @Test fun providerDarkKeepsJoinedRowsLegible() {
        show(detail = false, dark = true)
        rule.onNodeWithTag("account_row_work").assertIsDisplayed()
        capture("provider-dark")
    }

    @Test fun narrowLargeProviderKeepsNamesAndCreateActionReachable() {
        show(detail = false, large = true)
        rule.onNodeWithText("personal.account@example.test").assertIsDisplayed()
        rule.onNodeWithText("Add Account").assertDoesNotExist()
        rule.onNodeWithTag("global_add").assertIsDisplayed()
        capture("provider-large")
    }

    @Test fun narrowLargeAccountKeepsEightDigitsAndControlsWithinCard() {
        show(detail = true, large = true)
        val card = rule.onNodeWithTag("credential_otp").getUnclippedBoundsInRoot()
        val code = rule.onNodeWithTag("totp_value_otp").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(code.left >= card.left && code.right <= card.right)
        rule.onNodeWithTag("totp_copy_otp").assertIsDisplayed()
        rule.onNodeWithTag("account_recovery").performScrollTo().assertIsDisplayed()
        capture("account-large")
    }

    @Test fun emptyProviderStillOffersCreateAccount() {
        show(detail = false, empty = true)
        rule.onNodeWithTag("global_add").assertIsDisplayed().performTouchInput { click() }
        rule.onNodeWithTag("add_account").performTouchInput { click() }
        rule.runOnIdle { assertEquals(1, added) }
        capture("provider-empty")
    }
}
