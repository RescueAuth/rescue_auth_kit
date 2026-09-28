package com.rescueauth.v2.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rescueauth.v2.exportimport.ExportImportViewModel
import com.rescueauth.v2.legacyimport.LegacyImportViewModel
import com.rescueauth.v2.ui.components.RescueAuthExplainedCard
import com.rescueauth.v2.ui.screens.exportimport.ExportImportTestTags
import com.rescueauth.v2.ui.screens.exportimport.ExportVaultScreen
import com.rescueauth.v2.ui.screens.legacyimport.LegacyImportScreen
import com.rescueauth.v2.ui.screens.legacyimport.LegacyImportTestTags
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en")
class ExplainedInputCardTest {
    @get:Rule val rule = createComposeRule()
    private fun click(tag: String) = rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick) { it() }

    @Test fun legacyHelpDoesNotSubmitOrLoseTheDraftPassword() {
        var submits = 0
        var submitted = ""
        rule.setContent { RescueAuthTheme { LegacyImportScreen(
            LegacyImportViewModel.State.AwaitingPassword, {}, { submits++; submitted = String(it); false }, {}, {}, {}, {}, {}, {}) } }
        rule.onNodeWithTag("legacy_password_help_explanation").assertDoesNotExist()
        rule.onNodeWithTag(LegacyImportTestTags.PASSWORD_FIELD).performTextInput("synthetic draft")
        click("legacy_password_help_info")
        rule.onNodeWithTag("legacy_password_help_explanation").assertIsDisplayed()
        assertEquals(0, submits)
        click("legacy_password_help_close")
        rule.onNodeWithTag("legacy_password_help_explanation").assertDoesNotExist()
        rule.onNodeWithTag(LegacyImportTestTags.PASSWORD_SUBMIT).performClick()
        assertEquals("synthetic draft", submitted)
    }

    @Test fun nativePinHelpDoesNotSubmitOrChangeEitherPin() {
        var submits = 0
        var matches = false
        rule.setContent { RescueAuthTheme { ExportVaultScreen(
            ExportImportViewModel.ExportState.AwaitingPin, {}, {}, { p, c -> submits++; matches = String(p) == "24681357" && p.contentEquals(c); null }, {}, {}, {}) } }
        rule.onNodeWithTag(ExportImportTestTags.EXPORT_PIN_FIELD).performTextInput("24681357")
        rule.onNodeWithTag(ExportImportTestTags.EXPORT_CONFIRM_FIELD).performTextInput("24681357")
        click("export_pin_help_info")
        rule.onNodeWithTag("export_pin_help_explanation").assertIsDisplayed()
        assertEquals(0, submits)
        click("export_pin_help_close")
        rule.onNodeWithTag(ExportImportTestTags.EXPORT_SUBMIT).performClick()
        assertTrue(matches)
    }

    @Test fun changingTheOwnerClosesThePreviousExplanation() {
        val owner = mutableStateOf("first")
        rule.setContent { RescueAuthTheme { RescueAuthExplainedCard(
            identityKey = owner.value, title = "Information", explanation = "Explanation", icon = Icons.Filled.Lock,
            testTagPrefix = "test_help") { Text("Content") } } }
        click("test_help_info")
        rule.onNodeWithTag("test_help_explanation").assertIsDisplayed()
        rule.runOnIdle { owner.value = "second" }
        rule.onNodeWithTag("test_help_explanation").assertDoesNotExist()
        rule.onNodeWithText("Content").assertIsDisplayed()
    }
}
