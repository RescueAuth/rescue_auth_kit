package com.rescueauth.v2.ui.exportimport

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rescueauth.v2.export.SnapshotScope
import com.rescueauth.v2.exportimport.*
import com.rescueauth.v2.legacyimport.*
import com.rescueauth.v2.ui.screens.exportimport.*
import com.rescueauth.v2.ui.screens.legacyimport.*
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ImportPresentationTest {
    @get:Rule val rule = createComposeRule()
    private val native = ImportPreview("review", "2026-09-29T08:00:00Z", "Android", "1.0.0",
        SnapshotScope.FULL_VAULT, 2, 2, 1, 4, DeveloperPreviewSummary.from(emptyList()), 5, 3, 0, 0, 0)
    private fun showNative(preview: ImportPreview, onConfirm: () -> Unit = {}) = rule.setContent {
        RescueAuthTheme { ImportNativePackageScreen(ExportImportViewModel.ImportState.Preview(preview),
            {}, {}, {}, {}, {}, onConfirm, {}, {}) }
    }
    private fun showLegacy(conflicts: Int = 0, divergences: Int = 0) = rule.setContent {
        RescueAuthTheme { LegacyImportScreen(LegacyImportViewModel.State.Preview(LegacyImportPreview(
            1, "review", 2, 2, 1, 4, LegacyDeveloperPreviewSummary(0, 0, 0, 0, 0, emptyList()),
            5, 0, conflicts, 0, divergences)), {}, { false }, {}, {}, {}, {}, {}, {}) }
    }
    @Test fun nativeConflictReasonIsVisibleBeforeAnyScrollingAndCannotConfirm() {
        var confirmations = 0
        showNative(native.copy(conflicts = 2), { confirmations++ })
        rule.onNodeWithTag("import_blocked_reason").assertIsDisplayed()
        rule.onNodeWithTag(ExportImportTestTags.IMPORT_CONFIRM).assertIsNotEnabled().performClick()
        assertEquals(0, confirmations)
    }
    @Test fun recoveryStateDifferenceAlsoBlocksLegacyWithoutHidingTheReason() {
        showLegacy(divergences = 2)
        rule.onNodeWithTag("import_blocked_reason").assertIsDisplayed()
        rule.onNodeWithTag(LegacyImportTestTags.CONFIRM).assertIsNotEnabled()
    }
    @Test fun normalPreviewEmphasizesThePlanAndKeepsTechnicalDetailsCollapsed() {
        showNative(native)
        rule.onNodeWithTag("import_plan").assertIsDisplayed()
        rule.onNodeWithText("Android signing keys").assertDoesNotExist()
        rule.onNodeWithTag("import_details_toggle").performScrollTo().performClick()
        rule.onNodeWithTag("import_details_content").assertExists()
        rule.onNodeWithTag(ExportImportTestTags.IMPORT_CONFIRM).assertIsEnabled()
    }
    @Test fun legacySchemaIsStillAvailableInDetails() {
        showLegacy()
        rule.onNodeWithText("Schema version").assertDoesNotExist()
        rule.onNodeWithTag("import_details_toggle").performScrollTo().performClick()
        rule.onNodeWithText("Schema version").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(LegacyImportTestTags.CONFIRM).assertIsDisplayed()
    }
    @Test fun previewDoesNotImportUntilConfirmed() {
        var confirmations = 0
        showNative(native, { confirmations++ })
        rule.onNodeWithTag("import_details_toggle").performScrollTo().performClick()
        assertEquals(0, confirmations)
        rule.onNodeWithTag(ExportImportTestTags.IMPORT_CONFIRM).performClick()
        assertEquals(1, confirmations)
    }
}
