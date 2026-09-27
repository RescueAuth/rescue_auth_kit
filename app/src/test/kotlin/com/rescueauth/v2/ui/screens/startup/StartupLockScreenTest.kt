package com.rescueauth.v2.ui.screens.startup

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Behaviour tests for the startup auth UX (Issue #50 UX rework).
 *
 * The normal flow has NO interactive lock screen: first-run shows a one-time
 * security intro, existing-vault launches request authentication automatically,
 * and the neutral auth host never exposes sensitive content. The no-secure
 * device case keeps a blocking screen with a "Go to system settings" action.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class StartupLockScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun introScreenShowsEnableAndInvokesCallback() {
        var enabled = false
        composeRule.setContent {
            RescueAuthTheme {
                StartupIntroScreen(
                    onContinue = { enabled = true },
                )
            }
        }
        composeRule.onNodeWithTag(StartupLockTestTags.INTRO_SCREEN).assertIsDisplayed()
        // Brand visual (Issue #64) must be present so the first-run page keeps
        // the launch brand recognisable (default locale = English here).
        composeRule.onNodeWithTag(StartupSplashTestTags.BRANDING).assertIsDisplayed()
        composeRule.onAllNodesWithText("RescueAuth").assertCountEquals(1)
        composeRule.onNodeWithText("RescueAuth").assertIsDisplayed()
        composeRule.onNodeWithText("Your vault.").assertDoesNotExist()
        composeRule.onNodeWithText("Codes").assertDoesNotExist()
        composeRule.onNodeWithText("Keys").assertDoesNotExist()
        composeRule.onNodeWithText("Recovery codes").assertDoesNotExist()
        composeRule.onNode(hasText("Fingerprint or screen lock") and
            hasAnyAncestor(hasTestTag(StartupLockTestTags.INTRO_CONTINUE_BUTTON)), useUnmergedTree = true)
            .assertExists()
        composeRule.onNodeWithText("Create vault").assertExists()

        composeRule.onNodeWithTag(StartupLockTestTags.INTRO_CONTINUE_BUTTON).performScrollTo().performClick()
        assertTrue("Enable callback must fire", enabled)
    }

    @Test
    fun authHostIsNonInteractiveNeutralHost() {
        composeRule.setContent {
            RescueAuthTheme {
                StartupAuthHost()
            }
        }
        composeRule.onNodeWithTag(StartupLockTestTags.AUTH_HOST).assertIsDisplayed()
        // Brand visual (Issue #64) must be present — the auth host is the layer
        // most visible between the system splash and the vault opening, so the
        // brand name must not disappear there.
        composeRule.onNodeWithTag(StartupSplashTestTags.BRANDING).assertIsDisplayed()
        composeRule.onNodeWithText("RescueAuth").assertIsDisplayed()
    }

    @Test
    fun noSecureDeviceScreenShowsBlockingStateWithSettingsAndExit() {
        var exited = false
        composeRule.setContent {
            RescueAuthTheme {
                NoSecureDeviceScreen(onExit = { exited = true })
            }
        }
        composeRule.onNodeWithTag(StartupLockTestTags.SCREEN).assertIsDisplayed()
        composeRule.onNodeWithText("Secure device lock required").assertIsDisplayed()
        composeRule.onNodeWithTag(StartupLockTestTags.SETTINGS_BUTTON).assertIsDisplayed()
        composeRule.onNodeWithTag(StartupLockTestTags.EXIT_BUTTON).performClick()
        assertTrue("Exit callback must fire", exited)
    }
}
