package com.rescueauth.v2.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Navigation-structure test for the app shell.
 *
 * Verifies the three top-level destinations (Authenticator / Developer /
 * Settings) are reachable from the bottom navigation bar and that the shell
 * starts on Authenticator. Runs on Robolectric as a JVM unit test.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RescueAuthAppNavigationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setAppContent(startRoute: String? = null) {
        composeRule.setContent {
            RescueAuthTheme {
                RescueAuthApp(startRoute = startRoute)
            }
        }
    }

    @Test
    fun topLevelStartRouteSelectsTheMatchingHomePage() {
        setAppContent(com.rescueauth.v2.ui.navigation.RescueAuthRoutes.DEVELOPER)
        composeRule.onNodeWithTag(RescueAuthTestTags.SCREEN_DEVELOPER).assertIsDisplayed()
        composeRule.onNodeWithTag(RescueAuthTestTags.NAV_DEVELOPER).assertIsSelected()
    }

    @Test
    fun shellStartsOnAuthenticatorDestination() {
        setAppContent()
        composeRule.onNodeWithTag(RescueAuthTestTags.SCREEN_AUTHENTICATOR).assertIsDisplayed()
    }

    @Test
    fun navigationBarExposesAllThreeTopLevelDestinations() {
        setAppContent()
        composeRule.onNodeWithTag(RescueAuthTestTags.NAV_AUTHENTICATOR).assertIsDisplayed()
        composeRule.onNodeWithTag(RescueAuthTestTags.NAV_DEVELOPER).assertIsDisplayed()
        composeRule.onNodeWithTag(RescueAuthTestTags.NAV_SETTINGS).assertIsDisplayed()
    }

    @Test
    fun developerIsFirstClassDestinationNotHiddenInSettings() {
        setAppContent()
        // Developer is a top-level nav destination (never a Settings sub-entry).
        composeRule.onNodeWithTag(RescueAuthTestTags.NAV_DEVELOPER).performClick()
        composeRule.onNodeWithTag(RescueAuthTestTags.SCREEN_DEVELOPER).assertIsDisplayed()
    }

    @Test
    fun settingsDestinationIsReachableFromNavBar() {
        setAppContent()
        composeRule.onNodeWithTag(RescueAuthTestTags.NAV_SETTINGS).performClick()
        composeRule.onNodeWithTag(RescueAuthTestTags.SCREEN_SETTINGS).assertIsDisplayed()
    }

    @Test
    fun settingsShowsLegacyImportEntrySeparateFromNativeImport() {
        setAppContent()
        composeRule.onNodeWithTag(RescueAuthTestTags.NAV_SETTINGS).performClick()
        composeRule.onNodeWithTag("settings_transfer_row").performScrollTo().performClick()
        composeRule.onNodeWithTag(RescueAuthTestTags.NAV_SETTINGS).assertDoesNotExist()
        // Native and Legacy remain separate choices in the transfer hub.
        composeRule.onNodeWithText("Import Native Package").assertIsDisplayed()
        composeRule.onNodeWithText("Import Legacy v1 Vault").assertIsDisplayed()
        // Opening the Legacy entry navigates to the Legacy import screen.
        composeRule.onNodeWithText("Import Legacy v1 Vault").performClick()
        composeRule.onNodeWithTag(com.rescueauth.v2.ui.screens.legacyimport.LegacyImportTestTags.SCREEN).assertIsDisplayed()
    }

    @Test
    fun legacyImportEntryIsNotHiddenInsideDeveloper() {
        setAppContent()
        // The Legacy import entry is under Settings → Import/Export (an import
        // hub), NOT hidden inside the Developer destination.
        composeRule.onNodeWithTag(RescueAuthTestTags.NAV_DEVELOPER).performClick()
        composeRule.onNodeWithText("Import Legacy v1 Vault", substring = true).assertDoesNotExist()
    }

    @Test
    fun settingsLeadsToAboutViaAboutEntry() {
        setAppContent()
        composeRule.onNodeWithTag(RescueAuthTestTags.NAV_SETTINGS).performClick()
        composeRule.onNodeWithTag(com.rescueauth.v2.ui.screens.settings.SettingsTestTags.ABOUT_ROW).assertExists()
        composeRule.onNodeWithTag(com.rescueauth.v2.ui.screens.settings.SettingsTestTags.ABOUT_ROW).performScrollTo().performClick()
        composeRule.onNodeWithTag(com.rescueauth.v2.ui.screens.about.AboutTestTags.SCREEN).assertExists()
    }

    @Test
    fun navigatingBackToAuthenticatorFromDeveloperRestoresFirstDestination() {
        setAppContent()
        composeRule.onNodeWithTag(RescueAuthTestTags.NAV_DEVELOPER).performClick()
        composeRule.onNodeWithTag(RescueAuthTestTags.NAV_AUTHENTICATOR).performClick()
        composeRule.onNodeWithTag(RescueAuthTestTags.SCREEN_AUTHENTICATOR).assertIsDisplayed()
    }
}
