package com.rescueauth.v2.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rescueauth.v2.ui.authenticator.AuthenticatorUiState
import com.rescueauth.v2.ui.developer.DeveloperListUiState
import com.rescueauth.v2.ui.model.AccountUi
import com.rescueauth.v2.ui.model.ProviderUi
import com.rescueauth.v2.ui.model.TotpCredentialUi
import com.rescueauth.v2.ui.screens.authenticator.AuthenticatorScreen
import com.rescueauth.v2.ui.screens.developer.DeveloperScreen
import com.rescueauth.v2.ui.screens.settings.SettingsTransferScreen
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Reduced visible copy must preserve action names, touch targets and useful spoken metadata. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class VisualLanguageTest {
    @get:Rule val rule = createComposeRule()

    private val account = AccountUi("demo-account", "Demo", "Personal", totpCredentials = listOf(
        TotpCredentialUi("demo-code", "demo-code", "Demo", "Personal", currentCode = null,
            remainingSeconds = 24, progressFraction = 0.8f),
    ))
    private val state = AuthenticatorUiState(loading = false, providers = listOf(
        ProviderUi("demo-provider", "Demo", listOf(account)),
    ))

    @Test fun addIconKeepsSeparateCredentialAndProviderActions() {
        var credentials = 0
        var providers = 0
        rule.setContent { RescueAuthTheme {
            AuthenticatorScreen(uiState = state, onAddClick = { credentials++ }, onAddProviderClick = { providers++ })
        } }
        rule.onNodeWithContentDescription("Add").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithText("Create a service").performClick()
        rule.onNodeWithContentDescription("Add").performClick()
        rule.onNodeWithText("Add authenticator").performClick()
        assertEquals(1, credentials)
        assertEquals(1, providers)
    }

    @Test fun providerCountRetainsItsSpokenMeaning() {
        rule.setContent { RescueAuthTheme { AuthenticatorScreen(uiState = state) } }
        rule.onNodeWithTag("provider_row_Demo").assert(hasContentDescription("1 account(s)"))
    }

    @Test fun copyIconHasAnActionLabelAndKeepsItsCredentialTarget() {
        var copied: String? = null
        rule.setContent { RescueAuthTheme {
            AuthenticatorScreen(uiState = state, initialAccountId = account.id, onCopyClick = { copied = it.credentialId })
        } }
        rule.onNodeWithContentDescription("Copy code").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals("demo-code", copied)
    }

    @Test fun developerAddIconStillOpensTheAddFlow() {
        var added = 0
        rule.setContent { RescueAuthTheme {
            DeveloperScreen(uiState = DeveloperListUiState(loading = false), onAddClick = { added++ })
        } }
        rule.onNodeWithContentDescription("Add").performClick()
        assertEquals(1, added)
    }

    @Test fun compactTransferKeepsProtectionAndImportChoicesExplicit() {
        var native = 0
        var legacy = 0
        rule.setContent { RescueAuthTheme {
            SettingsTransferScreen(onBack = {}, onExportClick = {}, onImportClick = { native++ }, onLegacyImportClick = { legacy++ })
        } }
        rule.onNodeWithText("Manual export").assertExists()
        rule.onNodeWithText("Per-export PIN").assertExists()
        rule.onNodeWithText("Merge on import").assertExists()
        rule.onNodeWithText("Import Native Package").performScrollTo().performClick()
        rule.onNodeWithText("Import Legacy v1 Vault").performScrollTo().performClick()
        assertEquals(1, native)
        assertEquals(1, legacy)
    }
}
