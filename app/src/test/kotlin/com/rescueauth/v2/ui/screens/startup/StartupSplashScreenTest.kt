package com.rescueauth.v2.ui.screens.startup

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Behaviour tests for the startup splash page (Issue #64).
 *
 * The splash is a minimal, non-interactive brand visual (logo + localized
 * brand name). It must render the brand name so the brand is recognizable at
 * launch, and it must NOT contain any lock-screen chrome (no Unlock/Exit).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class StartupSplashScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun splashShowsBrandNameWithoutLockScreenChrome() {
        composeRule.setContent {
            RescueAuthTheme {
                StartupSplashScreen()
            }
        }
        composeRule.onNodeWithTag(StartupSplashTestTags.SCREEN).assertIsDisplayed()
        // Localized brand name must be shown (default locale = English here).
        composeRule.onNodeWithText("RescueAuth").assertIsDisplayed()
        // It must NOT be the lock screen — no Unlock / Exit actions.
        composeRule.onNodeWithText("Unlock").assertDoesNotExist()
        composeRule.onNodeWithText("Exit").assertDoesNotExist()
    }
}
