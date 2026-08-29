package com.rescueauth.v2.ui.exportimport

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rescueauth.v2.export.SelectableAccount
import com.rescueauth.v2.export.SelectableDeveloperEntry
import com.rescueauth.v2.export.SelectableItem
import com.rescueauth.v2.export.SelectableItems
import com.rescueauth.v2.export.SelectableProvider
import com.rescueauth.v2.export.SelectableRecoverySet
import com.rescueauth.v2.export.SelectedItemSet
import com.rescueauth.v2.exportimport.ExportImportViewModel
import com.rescueauth.v2.exportimport.ExportScopeSpec
import com.rescueauth.v2.exportimport.ImportPreview
import com.rescueauth.v2.exportimport.ImportScopeSpec
import com.rescueauth.v2.exportimport.PinPolicy
import com.rescueauth.v2.ui.screens.exportimport.ExportImportTestTags
import com.rescueauth.v2.ui.screens.exportimport.ExportVaultScreen
import com.rescueauth.v2.ui.screens.exportimport.ImportNativePackageScreen
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Phase 4 P5 — Export/Import UI tests (Issue #20 §23).
 *
 * Export:
 * - scope picker has four scopes
 * - Authenticator-only selection
 * - Developer-only selection
 * - Selected Items hierarchy (parent selection / partial child selection)
 * - selection summary
 * - empty selection cannot continue
 * - secret never appears in semantics/contentDescription
 *
 * Import:
 * - full package preview
 * - Authenticator subset / Developer subset options (disabled when absent)
 * - selected items
 * - secret never shown
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SelectiveExportImportUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ------------------------------------------------------------------
    // Export scope picker
    // ------------------------------------------------------------------

    @Test
    fun `export scope picker has four scopes`() {
        composeRule.setContent {
            RescueAuthTheme {
                ExportVaultScreen(
                    state = ExportImportViewModel.ExportState.Idle,
                    onSelectScope = {},
                    onConfirmSelection = {},
                    onSubmitPin = { _, _ -> null },
                    onChooseDestination = {},
                    onCancel = {},
                    onDismissResult = {},
                )
            }
        }
        composeRule.onNodeWithTag(ExportImportTestTags.EXPORT_SCOPE_FULL).assertIsDisplayed()
        composeRule.onNodeWithTag(ExportImportTestTags.EXPORT_SCOPE_AUTH).assertIsDisplayed()
        composeRule.onNodeWithTag(ExportImportTestTags.EXPORT_SCOPE_DEV).assertIsDisplayed()
        composeRule.onNodeWithTag(ExportImportTestTags.EXPORT_SCOPE_SELECTED).assertIsDisplayed()
    }

    @Test
    fun `selecting a scope invokes the callback`() {
        var selected: ExportScopeSpec? = null
        composeRule.setContent {
            RescueAuthTheme {
                ExportVaultScreen(
                    state = ExportImportViewModel.ExportState.Idle,
                    onSelectScope = { selected = it },
                    onConfirmSelection = {},
                    onSubmitPin = { _, _ -> null },
                    onChooseDestination = {},
                    onCancel = {},
                    onDismissResult = {},
                )
            }
        }
        composeRule.onNodeWithTag(ExportImportTestTags.EXPORT_SCOPE_AUTH).performClick()
        assertEquals(ExportScopeSpec.Authenticator, selected)
    }

    // ------------------------------------------------------------------
    // Export Selected-Items hierarchy
    // ------------------------------------------------------------------

    private fun selectableItems(): SelectableItems = SelectableItems(
        providers = listOf(
            SelectableProvider(
                serviceName = "GitHub",
                accounts = listOf(
                    SelectableAccount(
                        stableId = "acc-a",
                        serviceName = "GitHub",
                        accountName = "alice",
                        totpCredentials = listOf(
                            SelectableItem("totp-a1", "6-digit · SHA1 · 30s"),
                            SelectableItem("totp-a2", "6-digit · SHA1 · 30s"),
                        ),
                        recoveryCodeSets = listOf(
                            SelectableRecoverySet("set-a1", "GitHub codes", 5, 2),
                        ),
                    ),
                    SelectableAccount(
                        stableId = "acc-b",
                        serviceName = "GitHub",
                        accountName = "bob",
                        totpCredentials = listOf(SelectableItem("totp-b1", "6-digit · SHA1 · 30s")),
                    ),
                ),
            ),
        ),
        developerEntries = listOf(
            SelectableDeveloperEntry("dev-api", "api_credential", "", "stripe"),
            SelectableDeveloperEntry("dev-ssh", "ssh_key", "", "work"),
        ),
    )

    @Test
    fun `selected items screen shows provider account and developer hierarchy`() {
        composeRule.setContent {
            RescueAuthTheme {
                ExportVaultScreen(
                    state = ExportImportViewModel.ExportState.SelectingItems(selectableItems()),
                    onSelectScope = {},
                    onConfirmSelection = {},
                    onSubmitPin = { _, _ -> null },
                    onChooseDestination = {},
                    onCancel = {},
                    onDismissResult = {},
                )
            }
        }
        composeRule.onNodeWithText("GitHub").assertExists()
        // The hierarchy is intentionally scrollable below the fixed summary
        // and action area; existence verifies it is rendered without requiring
        // a particular viewport height in Robolectric.
        composeRule.onNodeWithText("GitHub · alice").assertExists()
        composeRule.onNodeWithText("GitHub · bob").assertExists()
        // Recovery set label includes remaining count (safe metadata).
        composeRule.onNodeWithText("GitHub codes · 3/5 remaining").assertExists()
        // Developer section with safe labels (no secrets).
        composeRule.onNodeWithText("stripe").assertExists()
        composeRule.onNodeWithText("work").assertExists()
    }

    @Test
    fun `empty selection cannot continue`() {
        var confirmed = false
        composeRule.setContent {
            RescueAuthTheme {
                ExportVaultScreen(
                    state = ExportImportViewModel.ExportState.SelectingItems(selectableItems()),
                    onSelectScope = {},
                    onConfirmSelection = { confirmed = true },
                    onSubmitPin = { _, _ -> null },
                    onChooseDestination = {},
                    onCancel = {},
                    onDismissResult = {},
                )
            }
        }
        // Continue is disabled for an empty selection.
        composeRule.onNodeWithTag(ExportImportTestTags.EXPORT_SELECT_CONTINUE).assertIsNotEnabled()
        composeRule.onNodeWithTag(ExportImportTestTags.EXPORT_SELECT_CONTINUE).performClick()
        assertTrue(!confirmed)
    }

    @Test
    fun `select all authenticator enables continue and commits the full auth selection`() {
        var confirmed: SelectedItemSet? = null
        composeRule.setContent {
            RescueAuthTheme {
                ExportVaultScreen(
                    state = ExportImportViewModel.ExportState.SelectingItems(selectableItems()),
                    onSelectScope = {},
                    onConfirmSelection = { confirmed = it },
                    onSubmitPin = { _, _ -> null },
                    onChooseDestination = {},
                    onCancel = {},
                    onDismissResult = {},
                )
            }
        }
        composeRule.onNodeWithTag(ExportImportTestTags.EXPORT_SELECT_ALL_AUTH).performClick()
        composeRule.onNodeWithTag(ExportImportTestTags.EXPORT_SELECT_CONTINUE).assertIsEnabled()
        composeRule.onNodeWithTag(ExportImportTestTags.EXPORT_SELECT_CONTINUE).performClick()
        val sel = confirmed!!
        assertEquals(setOf("acc-a", "acc-b"), sel.selectedAccountStableIds)
        assertEquals(setOf("totp-a1", "totp-a2", "totp-b1"), sel.selectedTotpStableIds)
        assertEquals(setOf("set-a1"), sel.selectedRecoverySetStableIds)
        assertTrue(sel.selectedDeveloperStableIds.isEmpty())
    }

    @Test
    fun `select all developer enables continue and commits developer entries`() {
        var confirmed: SelectedItemSet? = null
        composeRule.setContent {
            RescueAuthTheme {
                ExportVaultScreen(
                    state = ExportImportViewModel.ExportState.SelectingItems(selectableItems()),
                    onSelectScope = {},
                    onConfirmSelection = { confirmed = it },
                    onSubmitPin = { _, _ -> null },
                    onChooseDestination = {},
                    onCancel = {},
                    onDismissResult = {},
                )
            }
        }
        composeRule.onNodeWithTag(ExportImportTestTags.EXPORT_SELECT_ALL_DEV).performClick()
        composeRule.onNodeWithTag(ExportImportTestTags.EXPORT_SELECT_CONTINUE).performClick()
        val sel = confirmed!!
        assertEquals(setOf("dev-api", "dev-ssh"), sel.selectedDeveloperStableIds)
        assertTrue(sel.selectedAccountStableIds.isEmpty())
    }

    @Test
    fun `selection summary shows counts without secrets`() {
        composeRule.setContent {
            RescueAuthTheme {
                ExportVaultScreen(
                    state = ExportImportViewModel.ExportState.SelectingItems(selectableItems()),
                    onSelectScope = {},
                    onConfirmSelection = {},
                    onSubmitPin = { _, _ -> null },
                    onChooseDestination = {},
                    onCancel = {},
                    onDismissResult = {},
                )
            }
        }
        composeRule.onNodeWithTag(ExportImportTestTags.EXPORT_SELECT_ALL_AUTH).performClick()
        // Summary text shows counts (safe) and never secret values.
        composeRule.onNodeWithText("2 account(s) · 3 TOTP · 1 recovery set(s) · 0 developer entry(ies)").assertIsDisplayed()
    }

    @Test
    fun `no secret in semantics or contentDescription`() {
        composeRule.setContent {
            RescueAuthTheme {
                ExportVaultScreen(
                    state = ExportImportViewModel.ExportState.SelectingItems(
                        selectableItems(),
                    ),
                    onSelectScope = {},
                    onConfirmSelection = {},
                    onSubmitPin = { _, _ -> null },
                    onChooseDestination = {},
                    onCancel = {},
                    onDismissResult = {},
                )
            }
        }
        // Secret-bearing strings must never appear anywhere in the tree.
        composeRule.onNodeWithText("sk_test_123").assertDoesNotExist()
        composeRule.onNodeWithText("JBSWY3DPEHPK3PXP").assertDoesNotExist()
        composeRule.onNodeWithText("SECRET-CODE").assertDoesNotExist()
    }

    // ------------------------------------------------------------------
    // Import scope chooser
    // ------------------------------------------------------------------

    private fun preview(accounts: Int = 2, developerTotal: Int = 2): ImportPreview = ImportPreview(
        packageId = "pkg",
        createdAt = "2024-01-01T00:00:00Z",
        sourceClient = "android-app",
        sourceAppVersion = "1.0.0",
        scope = com.rescueauth.v2.export.SnapshotScope.FULL_VAULT,
        accounts = accounts,
        totpCredentials = if (accounts > 0) 3 else 0,
        recoverySets = if (accounts > 0) 1 else 0,
        recoveryCodes = if (accounts > 0) 5 else 0,
        developerSummary = com.rescueauth.v2.exportimport.DeveloperPreviewSummary(
            signingKeys = if (developerTotal >= 1) 1 else 0,
            apiCredentials = if (developerTotal >= 2) 1 else 0,
            sshKeys = if (developerTotal >= 3) 1 else 0,
            envVarSets = 0,
            genericSecrets = 0,
            items = emptyList(),
        ),
        inserts = 5,
        duplicates = 0,
        conflicts = 0,
        unchanged = 0,
        stateDivergences = 0,
    )

    @Test
    fun `import scope chooser shows everything auth and dev options`() {
        composeRule.setContent {
            RescueAuthTheme {
                ImportNativePackageScreen(
                    state = ExportImportViewModel.ImportState.ChoosingScope(preview(accounts = 2, developerTotal = 2)),
                    onPickDocument = {},
                    onDecode = {},
                    onCancelPin = {},
                    onChooseScope = {},
                    onConfirmSelection = {},
                    onConfirm = {},
                    onCancel = {},
                    onDismissResult = {},
                )
            }
        }
        composeRule.onNodeWithTag(ExportImportTestTags.IMPORT_SCOPE_EVERYTHING).assertIsDisplayed()
        composeRule.onNodeWithTag(ExportImportTestTags.IMPORT_SCOPE_AUTH).assertExists()
        composeRule.onNodeWithTag(ExportImportTestTags.IMPORT_SCOPE_DEV).assertExists()
        composeRule.onNodeWithTag(ExportImportTestTags.IMPORT_SCOPE_SELECTED).assertExists()
    }

    @Test
    fun `developer option disabled when package lacks developer data`() {
        composeRule.setContent {
            RescueAuthTheme {
                ImportNativePackageScreen(
                    state = ExportImportViewModel.ImportState.ChoosingScope(preview(accounts = 2, developerTotal = 0)),
                    onPickDocument = {},
                    onDecode = {},
                    onCancelPin = {},
                    onChooseScope = {},
                    onConfirmSelection = {},
                    onConfirm = {},
                    onCancel = {},
                    onDismissResult = {},
                )
            }
        }
        composeRule.onNodeWithTag(ExportImportTestTags.IMPORT_SCOPE_DEV).assertIsNotEnabled()
        composeRule.onNodeWithTag(ExportImportTestTags.IMPORT_SCOPE_AUTH).assertIsEnabled()
    }

    @Test
    fun `authenticator option disabled when package lacks authenticator data`() {
        composeRule.setContent {
            RescueAuthTheme {
                ImportNativePackageScreen(
                    state = ExportImportViewModel.ImportState.ChoosingScope(preview(accounts = 0, developerTotal = 2)),
                    onPickDocument = {},
                    onDecode = {},
                    onCancelPin = {},
                    onChooseScope = {},
                    onConfirmSelection = {},
                    onConfirm = {},
                    onCancel = {},
                    onDismissResult = {},
                )
            }
        }
        composeRule.onNodeWithTag(ExportImportTestTags.IMPORT_SCOPE_AUTH).assertIsNotEnabled()
        composeRule.onNodeWithTag(ExportImportTestTags.IMPORT_SCOPE_DEV).assertIsEnabled()
    }

    @Test
    fun `import full preview shows safe counts only`() {
        composeRule.setContent {
            RescueAuthTheme {
                ImportNativePackageScreen(
                    state = ExportImportViewModel.ImportState.Preview(preview(accounts = 2, developerTotal = 2)),
                    onPickDocument = {},
                    onDecode = {},
                    onCancelPin = {},
                    onChooseScope = {},
                    onConfirmSelection = {},
                    onConfirm = {},
                    onCancel = {},
                    onDismissResult = {},
                )
            }
        }
        composeRule.onNodeWithText("Import preview").assertExists()
        composeRule.onNodeWithText("Confirm import").assertExists()
        // No secrets in the preview.
        composeRule.onNodeWithText("sk_test_123").assertDoesNotExist()
        composeRule.onNodeWithText("JBSWY3DPEHPK3PXP").assertDoesNotExist()
    }

    @Test
    fun `selecting an import scope invokes the callback`() {
        var chosen: ImportScopeSpec? = null
        composeRule.setContent {
            RescueAuthTheme {
                ImportNativePackageScreen(
                    state = ExportImportViewModel.ImportState.ChoosingScope(preview()),
                    onPickDocument = {},
                    onDecode = {},
                    onCancelPin = {},
                    onChooseScope = { chosen = it },
                    onConfirmSelection = {},
                    onConfirm = {},
                    onCancel = {},
                    onDismissResult = {},
                )
            }
        }
        composeRule.onNodeWithTag(ExportImportTestTags.IMPORT_SCOPE_EVERYTHING).performClick()
        assertEquals(ImportScopeSpec.Everything, chosen)
    }
}
