package com.rescueauth.v2.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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

    private fun setAppContent() {
        composeRule.setContent {
            RescueAuthTheme {
                RescueAuthApp()
            }
        }
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
    fun navigatingBackToAuthenticatorFromDeveloperRestoresFirstDestination() {
        setAppContent()
        composeRule.onNodeWithTag(RescueAuthTestTags.NAV_DEVELOPER).performClick()
        composeRule.onNodeWithTag(RescueAuthTestTags.NAV_AUTHENTICATOR).performClick()
        composeRule.onNodeWithTag(RescueAuthTestTags.SCREEN_AUTHENTICATOR).assertIsDisplayed()
    }
}
