package com.rescueauth.v2.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rescueauth.v2.exportimport.ExportImportViewModel
import com.rescueauth.v2.legacyimport.LegacyImportViewModel
import com.rescueauth.v2.ui.authenticator.AddMode
import com.rescueauth.v2.ui.authenticator.AddTotpFormState
import com.rescueauth.v2.ui.screens.authenticator.AddTotpSheet
import com.rescueauth.v2.ui.screens.exportimport.*
import com.rescueauth.v2.ui.screens.legacyimport.*
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Synthetic inputs only; never connected to a vault or written to screenshots. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en")
class PasswordVisibilityTest {
    @get:Rule val rule = createComposeRule()
    private val masked = SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)
    private fun toggle(tag: String) = rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick) { it() }
    private fun inside(field: String, toggle: String) {
        val box = rule.onNodeWithTag(field).getUnclippedBoundsInRoot()
        val button = rule.onNodeWithTag(toggle).getUnclippedBoundsInRoot()
        assertTrue(button.left >= box.left && button.right <= box.right)
        assertTrue(button.top >= box.top && button.bottom <= box.bottom)
    }

    @Test fun legacyEyeIsInsideTheFieldAndRetainsPasswordSubmission() {
        var submitted = ""
        rule.setContent { RescueAuthTheme { LegacyImportScreen(
            state = LegacyImportViewModel.State.AwaitingPassword, onPickFile = {},
            onSubmitPassword = { submitted = String(it); false }, onRetryPassword = {}, onCancelPassword = {},
            onConfirm = {}, onCancel = {}, onDismissResult = {}, onBack = {}) } }
        val field = rule.onNodeWithTag(LegacyImportTestTags.PASSWORD_FIELD)
        field.performTextInput("synthetic input")
        field.assert(masked)
        inside(LegacyImportTestTags.PASSWORD_FIELD, "legacy_password_visibility")
        rule.onNodeWithText("Show").assertDoesNotExist()
        toggle("legacy_password_visibility")
        field.assert(masked.not())
        rule.onNodeWithContentDescription("Hide").assertExists()
        toggle("legacy_password_visibility")
        field.assert(masked)
        rule.onNodeWithTag(LegacyImportTestTags.PASSWORD_SUBMIT).performClick()
        assertEquals("synthetic input", submitted)
    }

    @Test fun exportPinAndConfirmationHaveIndependentVisibility() {
        var submitted = false
        rule.setContent { RescueAuthTheme { ExportVaultScreen(
            state = ExportImportViewModel.ExportState.AwaitingPin, onSelectScope = {}, onConfirmSelection = {},
            onSubmitPin = { pin, confirm -> submitted = String(pin) == "24681357" && pin.contentEquals(confirm); null },
            onChooseDestination = {}, onCancel = {}, onDismissResult = {}) } }
        val pin = rule.onNodeWithTag(ExportImportTestTags.EXPORT_PIN_FIELD)
        val confirm = rule.onNodeWithTag(ExportImportTestTags.EXPORT_CONFIRM_FIELD)
        pin.performTextInput("24681357"); confirm.performTextInput("24681357")
        toggle("pin_visibility")
        pin.assert(masked.not()); confirm.assert(masked)
        toggle("pin_confirm_visibility")
        confirm.assert(masked.not())
        toggle("pin_visibility")
        pin.assert(masked); confirm.assert(masked.not())
        inside(ExportImportTestTags.EXPORT_PIN_FIELD, "pin_visibility")
        inside(ExportImportTestTags.EXPORT_CONFIRM_FIELD, "pin_confirm_visibility")
        rule.onNodeWithText("Show").assertDoesNotExist(); rule.onNodeWithText("Hide").assertDoesNotExist()
        rule.onNodeWithTag(ExportImportTestTags.EXPORT_SUBMIT).performClick()
        assertTrue(submitted)
    }

    @Test fun nativeImportPinCanRevealAndHideWithoutChangingTheValue() {
        var submitted = ""
        rule.setContent { RescueAuthTheme { ImportNativePackageScreen(
            state = ExportImportViewModel.ImportState.AwaitingPin, onPickDocument = {}, onDecode = { submitted = String(it) },
            onCancelPin = {}, onChooseScope = {}, onConfirmSelection = {}, onConfirm = {}, onCancel = {}, onDismissResult = {}) } }
        val field = rule.onNodeWithTag(ExportImportTestTags.IMPORT_PIN_FIELD)
        field.performTextInput("24681357")
        field.assert(masked); toggle("pin_visibility"); field.assert(masked.not())
        toggle("pin_visibility"); field.assert(masked)
        rule.onNodeWithTag(ExportImportTestTags.IMPORT_SUBMIT).performClick()
        assertEquals("24681357", submitted)
    }

    @Test fun manualSecretStartsMaskedAndResetsWhenChangingInputMethods() {
        val form = mutableStateOf(AddTotpFormState(mode = AddMode.MANUAL))
        rule.setContent { RescueAuthTheme { AddTotpSheet(form.value, {}, {}, onUriChange = {}, onProviderChange = {},
            onAccountNameChange = {}, onSecretChange = { form.value = form.value.copy(secret = it) },
            onAlgorithmChange = {}, onDigitsChange = {}, onPeriodChange = {}, onSubmit = {}) } }
        val field = rule.onNodeWithTag("add_totp_secret")
        field.performTextInput("SYNTHETIC")
        field.assert(masked); toggle("totp_secret_visibility"); field.assert(masked.not())
        assertEquals("SYNTHETIC", form.value.secret)
        rule.onNodeWithTag("totp_method_PASTE").performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.onNodeWithTag("totp_method_MANUAL").performSemanticsAction(SemanticsActions.OnClick) { it() }
        field.assert(masked)
        assertEquals("SYNTHETIC", form.value.secret)
    }
}
