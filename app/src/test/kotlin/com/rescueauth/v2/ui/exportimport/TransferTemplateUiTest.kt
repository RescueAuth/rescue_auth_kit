package com.rescueauth.v2.ui.exportimport

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rescueauth.v2.export.SnapshotScope
import com.rescueauth.v2.exportimport.DeveloperPreviewSummary
import com.rescueauth.v2.exportimport.ExportImportViewModel
import com.rescueauth.v2.exportimport.ImportPreview
import com.rescueauth.v2.exportimport.PinPolicy
import com.rescueauth.v2.ui.screens.exportimport.ExportImportTestTags
import com.rescueauth.v2.ui.screens.exportimport.ExportVaultScreen
import com.rescueauth.v2.ui.screens.exportimport.ImportNativePackageScreen
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TransferTemplateUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun importPinDoesNotInheritExportLengthRules() {
        var received: CharArray? = null
        rule.setContent {
            RescueAuthTheme {
                ImportNativePackageScreen(ExportImportViewModel.ImportState.AwaitingPin,
                    {}, { received = it }, {}, {}, {}, {}, {}, {})
            }
        }
        rule.onNodeWithTag("page_action_bar").assertIsDisplayed()
        rule.onNodeWithTag(ExportImportTestTags.IMPORT_SUBMIT).assertIsNotEnabled()
        rule.onNodeWithTag(ExportImportTestTags.IMPORT_PIN_FIELD).performTextInput("7")
        rule.onNodeWithTag(ExportImportTestTags.IMPORT_SUBMIT).performClick()
        assertEquals(1, received!!.size)
        received!!.fill('\u0000') // The receiving import VM owns successful submissions.
    }

    @Test fun rejectedExportPinArraysAreStillClearedByTheUi() {
        var received: Pair<CharArray, CharArray>? = null
        rule.setContent {
            RescueAuthTheme {
                ExportVaultScreen(ExportImportViewModel.ExportState.AwaitingPin, {}, {},
                    { p, c -> received = p to c; PinPolicy.Reason.TOO_SHORT }, {}, {}, {})
            }
        }
        rule.onNodeWithTag(ExportImportTestTags.EXPORT_PIN_FIELD).performTextInput("7")
        rule.onNodeWithTag(ExportImportTestTags.EXPORT_CONFIRM_FIELD).performTextInput("7")
        rule.onNodeWithTag(ExportImportTestTags.EXPORT_SUBMIT).assertIsDisplayed().performClick()
        assertTrue(received!!.first.all { it == '\u0000' })
        assertTrue(received!!.second.all { it == '\u0000' })
    }

    @Test fun nativePreviewConflictsAndDivergencesKeepConfirmationBlocked() {
        val preview = ImportPreview("demo", "2026-09-27T00:00:00Z", "Android", "1.0.0", SnapshotScope.FULL_VAULT,
            2, 2, 1, 4, DeveloperPreviewSummary.from(emptyList()), 5, 0, 1, 0, 0)
        val current = mutableStateOf(preview)
        var confirmed = 0
        var cancelled = 0
        rule.setContent {
            RescueAuthTheme {
                ImportNativePackageScreen(ExportImportViewModel.ImportState.Preview(current.value),
                    {}, {}, {}, {}, {}, { confirmed++ }, { cancelled++ }, {})
            }
        }
        rule.onNodeWithTag("page_action_bar").assertIsDisplayed()
        rule.onNodeWithTag("import_blocked_reason").assertIsDisplayed()
        rule.onNodeWithTag("import_details_toggle").performScrollTo().performClick()
        rule.onNodeWithText("Entire Vault").assertExists()
        rule.onNodeWithText("FULL_VAULT").assertDoesNotExist()
        rule.onNodeWithTag(ExportImportTestTags.IMPORT_CONFIRM).assertIsNotEnabled().performClick()
        rule.runOnIdle { current.value = preview.copy(conflicts = 0, stateDivergences = 1) }
        rule.onNodeWithTag(ExportImportTestTags.IMPORT_CONFIRM).assertIsNotEnabled().performClick()
        assertEquals(0, confirmed)
        rule.runOnIdle { current.value = preview.copy(conflicts = 0) }
        rule.onNodeWithTag(ExportImportTestTags.IMPORT_CONFIRM).assertIsEnabled().performClick()
        rule.onNodeWithTag(ExportImportTestTags.IMPORT_CANCEL).performClick()
        assertEquals(1, confirmed)
        assertEquals(1, cancelled)
    }
}
