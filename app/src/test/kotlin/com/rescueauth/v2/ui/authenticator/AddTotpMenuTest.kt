package com.rescueauth.v2.ui.authenticator

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rescueauth.v2.totp.TotpCore
import com.rescueauth.v2.ui.screens.authenticator.AddTotpSheet
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Add TOTP menu behaviour (Phase 4 P2): the sheet exposes the three entry
 * modes Scan QR / Paste URI / Manual, and the Scan mode shows the camera
 * action.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AddTotpMenuTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `add menu exposes scan paste and manual`() {
        composeRule.setContent {
            RescueAuthTheme {
                AddTotpSheet(
                    form = AddTotpFormState(),
                    onDismiss = {},
                    onModeChange = {},
                    onUriChange = {},
                    onProviderChange = {},
                    onAccountNameChange = {},
                    onSecretChange = {},
                    onAlgorithmChange = {},
                    onDigitsChange = {},
                    onPeriodChange = {},
                    onSubmit = {},
                )
            }
        }
        composeRule.onNodeWithText("Scan QR").assertExists()
        composeRule.onNodeWithText("Paste URI").assertExists()
        composeRule.onNodeWithText("Manual").assertExists()
    }

    @Test
    fun `scan goes directly to the camera callback`() {
        var scanned = 0
        composeRule.setContent {
            RescueAuthTheme {
                AddTotpSheet(
                    form = AddTotpFormState(mode = AddMode.SCAN),
                    onDismiss = {},
                    onModeChange = {},
                    onStartScan = { scanned++ },
                    onUriChange = {},
                    onProviderChange = {},
                    onAccountNameChange = {},
                    onSecretChange = {},
                    onAlgorithmChange = {},
                    onDigitsChange = {},
                    onPeriodChange = {},
                    onSubmit = {},
                )
            }
        }
        composeRule.onNodeWithTag("totp_method_SCAN").performSemanticsAction(SemanticsActions.OnClick) { it() }
        org.junit.Assert.assertEquals(1, scanned)
        composeRule.onNodeWithText("Open camera").assertDoesNotExist()
    }

    @Test
    fun `manual mode defaults to sha1 six digits thirty seconds`() {
        composeRule.setContent {
            RescueAuthTheme {
                AddTotpSheet(
                    form = AddTotpFormState(mode = AddMode.MANUAL),
                    onDismiss = {},
                    onModeChange = {},
                    onUriChange = {},
                    onProviderChange = {},
                    onAccountNameChange = {},
                    onSecretChange = {},
                    onAlgorithmChange = {},
                    onDigitsChange = {},
                    onPeriodChange = {},
                    onSubmit = {},
                )
            }
        }
        // Defaults are asserted via the form model (not shown as a distinct
        // label) — verify the default constant used by the form state.
        val default = AddTotpFormState()
        org.junit.Assert.assertEquals(TotpCore.DEFAULT_ALGORITHM, default.algorithm)
        org.junit.Assert.assertEquals(6, default.digits)
        org.junit.Assert.assertEquals(30, default.periodSeconds)
    }

    @Test
    fun `input methods replace fields in place without a directory page`() {
        composeRule.setContent {
            RescueAuthTheme {
                AddTotpSheet(
                    form = AddTotpFormState(mode = AddMode.MANUAL),
                    onDismiss = {},
                    onModeChange = {},
                    onUriChange = {},
                    onProviderChange = {},
                    onAccountNameChange = {},
                    onSecretChange = {},
                    onAlgorithmChange = {},
                    onDigitsChange = {},
                    onPeriodChange = {},
                    onSubmit = {},
                )
            }
        }
        composeRule.onNodeWithText("Provider / issuer").assertExists()
        composeRule.onNodeWithText("Choose how to add a one-time password").assertDoesNotExist()
        composeRule.onNodeWithTag("totp_method_PASTE").performSemanticsAction(SemanticsActions.OnClick) { it() }
        composeRule.onNodeWithTag("add_totp_uri").assertExists()
        composeRule.onNodeWithTag("totp_method_MANUAL").performSemanticsAction(SemanticsActions.OnClick) { it() }
        composeRule.onNodeWithText("Provider / issuer").assertExists()
        composeRule.onNodeWithTag("totp_change_method").assertDoesNotExist()
        composeRule.onNodeWithTag("totp_method_SCAN").assertExists()
        composeRule.onNodeWithTag("totp_method_PASTE").assertExists()
    }

    @Test
    fun `manual form keeps submit visible without scrolling to the bottom`() {
        var submitted = 0
        composeRule.setContent { RescueAuthTheme {
            AddTotpSheet(AddTotpFormState(mode = AddMode.MANUAL), onDismiss = {}, onModeChange = {},
                onUriChange = {}, onProviderChange = {}, onAccountNameChange = {}, onSecretChange = {},
                onAlgorithmChange = {}, onDigitsChange = {}, onPeriodChange = {}, onSubmit = { submitted++ })
        } }
        composeRule.onNodeWithTag("add_totp_submit").assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        org.junit.Assert.assertEquals(1, submitted)
    }
}
