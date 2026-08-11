package com.rescueauth.v2.ui.screens.startup

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Behaviour tests for the startup lock / no-secure-device screens (Issue #50).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class StartupLockScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun lockScreenShowsUnlockAndExitAndInvokesCallbacks() {
        var unlocked = false
        var exited = false
        composeRule.setContent {
            RescueAuthTheme {
                StartupLockScreen(
                    onUnlock = { unlocked = true },
                    onExit = { exited = true },
                )
            }
        }
        composeRule.onNodeWithTag(StartupLockTestTags.SCREEN).assertIsDisplayed()
        composeRule.onNodeWithText("Unlock").assertIsDisplayed()
        composeRule.onNodeWithText("Exit").assertIsDisplayed()

        composeRule.onNodeWithTag(StartupLockTestTags.UNLOCK_BUTTON).performClick()
        assertTrue("Unlock callback must fire", unlocked)

        composeRule.onNodeWithTag(StartupLockTestTags.EXIT_BUTTON).performClick()
        assertTrue("Exit callback must fire", exited)
    }

    @Test
    fun noSecureDeviceScreenShowsBlockingStateAndExit() {
        var exited = false
        composeRule.setContent {
            RescueAuthTheme {
                NoSecureDeviceScreen(onExit = { exited = true })
            }
        }
        composeRule.onNodeWithTag(StartupLockTestTags.SCREEN).assertIsDisplayed()
        composeRule.onNodeWithText("Secure device lock required").assertIsDisplayed()
        composeRule.onNodeWithTag(StartupLockTestTags.EXIT_BUTTON).performClick()
        assertTrue("Exit callback must fire", exited)
    }
}
