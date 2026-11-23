package com.rescueauth.v2.ui.startup

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rescueauth.v2.ui.screens.startup.FirstRunPinMode
import com.rescueauth.v2.ui.screens.startup.StartupBackupPinSetupScreen
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Compose-level checks for the unified first-run PIN surface (TDD red). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class UnifiedStartupBackupPinScreenTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun systemAuthDeviceShowsSetUpLater() {
        val mode = FirstRunPinMode(
            showSetUpLater = true,
            mustSetPinBeforeContinuing = false,
            copyVariant = FirstRunPinMode.Copy.STANDARD,
            createBranchForPinSubmission = com.rescueauth.v2.ui.screens.startup.FirstRunCreationBranch.DUAL_SLOT,
            createBranchForSetUpLater = com.rescueauth.v2.ui.screens.startup.FirstRunCreationBranch.SYSTEM_ONLY,
            allowSingleSlotFallback = false,
        )
        composeRule.setContent {
            RescueAuthTheme {
                StartupBackupPinSetupScreen(
                    submitting = false,
                    errorMessage = null,
                    mode = mode,
                    onBack = {},
                    onSetUpLater = {},
                    onCreateWithPin = {},
                )
            }
        }
        composeRule.onNodeWithTag("startup_pin_set_up_later").assertIsDisplayed()
        composeRule.onNodeWithText("Set up later").assertIsDisplayed()
    }

    @Test
    fun noSecureLockHidesSetUpLaterAndShowsBlockingExplanation() {
        val mode = FirstRunPinMode(
            showSetUpLater = false,
            mustSetPinBeforeContinuing = true,
            copyVariant = FirstRunPinMode.Copy.NO_SECURE_LOCK,
            createBranchForPinSubmission = com.rescueauth.v2.ui.screens.startup.FirstRunCreationBranch.BACKUP_PIN_ONLY,
            createBranchForSetUpLater = com.rescueauth.v2.ui.screens.startup.FirstRunCreationBranch.SYSTEM_ONLY,
            allowSingleSlotFallback = false,
        )
        composeRule.setContent {
            RescueAuthTheme {
                StartupBackupPinSetupScreen(
                    submitting = false,
                    errorMessage = null,
                    mode = mode,
                    onBack = {},
                    onSetUpLater = {},
                    onCreateWithPin = {},
                )
            }
        }
        composeRule.onNodeWithTag("startup_pin_set_up_later").assertDoesNotExist()
        composeRule.onNodeWithTag("startup_pin_required_notice").assertIsDisplayed()
        composeRule.onNodeWithText("You must set a Backup PIN to continue on this device.", substring = true).assertIsDisplayed()
    }

    @Test
    fun weakOnlyCopyUsesDeviceCredentialWording() {
        val mode = FirstRunPinMode(
            showSetUpLater = true,
            mustSetPinBeforeContinuing = false,
            copyVariant = FirstRunPinMode.Copy.WEAK_BIOMETRIC,
            createBranchForPinSubmission = com.rescueauth.v2.ui.screens.startup.FirstRunCreationBranch.BACKUP_PIN_ONLY,
            createBranchForSetUpLater = com.rescueauth.v2.ui.screens.startup.FirstRunCreationBranch.SYSTEM_ONLY,
            allowSingleSlotFallback = false,
        )
        composeRule.setContent {
            RescueAuthTheme {
                StartupBackupPinSetupScreen(
                    submitting = false,
                    errorMessage = null,
                    mode = mode,
                    onBack = {},
                    onSetUpLater = {},
                    onCreateWithPin = {},
                )
            }
        }
        composeRule.onNodeWithText("device PIN, pattern, or password", ignoreCase = true, substring = true).assertIsDisplayed()
    }
}