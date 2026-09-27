package com.rescueauth.v2.ui

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rescueauth.v2.ui.authenticator.AuthenticatorUiState
import com.rescueauth.v2.ui.developer.DeveloperListUiState
import com.rescueauth.v2.ui.components.StudioVaultHero
import com.rescueauth.v2.ui.model.AccountUi
import com.rescueauth.v2.ui.model.ProviderUi
import com.rescueauth.v2.ui.screens.authenticator.AuthenticatorScreen
import com.rescueauth.v2.ui.screens.developer.DeveloperScreen
import com.rescueauth.v2.ui.screens.settings.SettingsScreen
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A brand anchor with only the authenticator's total account count. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class StudioBannerTest {
    @get:Rule val rule = createComposeRule()
    private val providers = listOf(
        ProviderUi("first", "First", listOf(
            AccountUi("one", "First", "Personal", isPinned = true),
            AccountUi("two", "First", "Work"),
        )),
        ProviderUi("second", "Second", listOf(AccountUi("three", "Second", "Personal"))),
    )
    private val insideBanner = hasAnyAncestor(hasTestTag("vault_brand_banner"))

    @Test fun backgroundArtworkAddsNoSpokenLabelOrTouchTarget() {
        rule.setContent { RescueAuthTheme { StudioVaultHero(accountCount = 12) } }
        rule.onAllNodes(hasText("RescueAuth")).assertCountEquals(1)
        rule.onNodeWithTag("vault_brand_banner").assertHasNoClickAction()
        rule.onAllNodes(hasClickAction() and insideBanner).assertCountEquals(0)
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription) and insideBanner)
            .assertCountEquals(1)
        rule.onNode(hasContentDescription("12 accounts") and insideBanner).assertIsDisplayed()
    }

    @Test fun authenticatorShowsTotalAccountsWithoutOtherOverviewStatistics() {
        rule.setContent { RescueAuthTheme {
            AuthenticatorScreen(uiState = AuthenticatorUiState(loading = false, providers = providers))
        } }
        rule.onNode(hasText("RescueAuth") and insideBanner).assertIsDisplayed()
        rule.onNode(hasContentDescription("3 accounts") and insideBanner).assertIsDisplayed()
        rule.onNode(hasText("3") and insideBanner, useUnmergedTree = true).assertIsDisplayed()
        rule.onNode(hasContentDescription("2 services") and insideBanner).assertDoesNotExist()
        rule.onNode(hasContentDescription("0 authenticators") and insideBanner).assertDoesNotExist()
        // The English wordmark may wrap on the JVM host's narrow synthetic font metrics.
        // Native default and large-text sizes are covered by StudioVisualReviewTest.
        rule.onNodeWithTag("vault_brand_banner").assertHeightIsAtLeast(88.dp)
        val bounds = rule.onNodeWithTag("vault_brand_banner").getUnclippedBoundsInRoot()
        assertTrue(bounds.bottom.value - bounds.top.value <= 104f)
        rule.onNodeWithText("Pinned").performClick()
        // Filtering the directory must not make the total account summary change.
        rule.onNode(hasContentDescription("3 accounts") and insideBanner).assertIsDisplayed()
    }

    @Test fun accountCountUpdatesAndRepresentsAnEmptyVault() {
        val state = mutableStateOf(AuthenticatorUiState(loading = false, providers = providers))
        rule.setContent { RescueAuthTheme { AuthenticatorScreen(uiState = state.value) } }
        rule.onNode(hasContentDescription("3 accounts") and insideBanner).assertIsDisplayed()
        rule.runOnIdle { state.value = AuthenticatorUiState(loading = false, providers = emptyList()) }
        rule.onNode(hasContentDescription("0 accounts") and insideBanner).assertIsDisplayed()
        rule.onNode(hasContentDescription("3 accounts") and insideBanner).assertDoesNotExist()
    }

    @Test fun developerBannerHasBrandWithoutCredentialOrCategorySummary() {
        rule.setContent { RescueAuthTheme {
            DeveloperScreen(uiState = DeveloperListUiState(loading = false))
        } }
        rule.onNode(hasText("RescueAuth") and insideBanner).assertIsDisplayed()
        rule.onNode(hasText("Credentials") and insideBanner).assertDoesNotExist()
        rule.onNode(hasContentDescription("5 categories") and insideBanner).assertDoesNotExist()
        rule.onNodeWithTag("vault_banner_accounts").assertDoesNotExist()
        rule.onNodeWithTag("vault_brand_banner").assertHeightIsEqualTo(88.dp)
    }

    @Test fun settingsBannerHasBrandAndKeepsTransferEntryReachable() {
        rule.setContent { RescueAuthTheme { SettingsScreen(versionName = "1.0.0") } }
        rule.onNode(hasText("RescueAuth") and insideBanner).assertIsDisplayed()
        rule.onNode(hasText("Encrypted vault") and insideBanner).assertDoesNotExist()
        rule.onNodeWithTag("vault_banner_accounts").assertDoesNotExist()
        rule.onNodeWithTag("vault_brand_banner").assertHeightIsEqualTo(88.dp)
        rule.onNodeWithTag("settings_transfer_row").assertIsDisplayed()
    }

    @Test fun narrowLargeTextBannerKeepsBrandAndCountVisible() {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                RescueAuthTheme {
                    AuthenticatorScreen(modifier = Modifier.width(320.dp),
                        uiState = AuthenticatorUiState(loading = false, providers = providers))
                }
            }
        }
        rule.onNode(hasText("RescueAuth") and insideBanner).assertIsDisplayed()
        rule.onNode(hasContentDescription("3 accounts") and insideBanner).assertIsDisplayed()
        rule.onNodeWithTag("vault_brand_banner").assertHeightIsEqualTo(120.dp)
    }
}
