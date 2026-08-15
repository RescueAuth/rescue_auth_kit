package com.rescueauth.v2.ui.screens.exportimport

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.rescueauth.v2.R
import com.rescueauth.v2.export.SelectableDeveloperEntry
import com.rescueauth.v2.export.SelectableItems
import com.rescueauth.v2.export.SelectedItemSet
import com.rescueauth.v2.exportimport.ExportImportViewModel
import com.rescueauth.v2.exportimport.ExportScopeSpec
import com.rescueauth.v2.exportimport.ImportScopeSpec
import com.rescueauth.v2.exportimport.PinPolicy
import com.rescueauth.v2.ui.components.BreadcrumbItem
import com.rescueauth.v2.ui.components.BreadcrumbTopBar
import com.rescueauth.v2.ui.navigation.RescueAuthRoutes
import com.rescueauth.v2.ui.theme.Spacing

object ExportImportTestTags {
    const val EXPORT_PIN_FIELD = "export_pin_field"
    const val EXPORT_CONFIRM_FIELD = "export_confirm_field"
    const val EXPORT_REVEAL_BUTTON = "export_reveal_button"
    const val EXPORT_SUBMIT = "export_submit"
    const val IMPORT_PIN_FIELD = "import_pin_field"
    const val IMPORT_REVEAL_BUTTON = "import_reveal_button"
    const val IMPORT_SUBMIT = "import_submit"
    const val IMPORT_CONFIRM = "import_confirm"
    const val IMPORT_CANCEL = "import_cancel"
    const val EXPORT_CANCEL = "export_cancel"

    // Phase 4 P5 scope pickers + selection
    const val EXPORT_SCOPE_FULL = "export_scope_full"
    const val EXPORT_SCOPE_AUTH = "export_scope_auth"
    const val EXPORT_SCOPE_DEV = "export_scope_dev"
    const val EXPORT_SCOPE_SELECTED = "export_scope_selected"
    const val EXPORT_SELECT_CONTINUE = "export_select_continue"
    const val EXPORT_SELECT_ALL_AUTH = "export_select_all_auth"
    const val EXPORT_SELECT_ALL_DEV = "export_select_all_dev"
    const val EXPORT_SELECT_CLEAR = "export_select_clear"
    const val IMPORT_SCOPE_EVERYTHING = "import_scope_everything"
    const val IMPORT_SCOPE_AUTH = "import_scope_auth"
    const val IMPORT_SCOPE_DEV = "import_scope_dev"
    const val IMPORT_SCOPE_SELECTED = "import_scope_selected"
    const val IMPORT_SELECT_CONTINUE = "import_select_continue"
    const val IMPORT_SELECT_ALL_AUTH = "import_select_all_auth"
    const val IMPORT_SELECT_ALL_DEV = "import_select_all_dev"
    const val IMPORT_SELECT_CLEAR = "import_select_clear"
}

/**
 * Export Vault screen (Phase 3D §28).
 *
 * Flow: Export Vault → PIN + confirm → SAF destination →
 * export → success/failure. The PIN is held in transient local state only;
 * it is never written to SavedStateHandle / Bundle / DataStore / logs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportVaultScreen(
    state: ExportImportViewModel.ExportState,
    onSelectScope: (ExportScopeSpec) -> Unit,
    onConfirmSelection: (SelectedItemSet) -> Unit,
    onSubmitPin: (pin: CharArray, confirm: CharArray) -> PinPolicy.Reason?,
    onChooseDestination: () -> Unit,
    onCancel: () -> Unit,
    onDismissResult: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigate: (String) -> Unit = {},
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    BreadcrumbTopBar(
                        items = listOf(
                            BreadcrumbItem(
                                label = stringResource(R.string.settings_title),
                                destination = RescueAuthRoutes.SETTINGS,
                            ),
                            BreadcrumbItem(
                                label = stringResource(R.string.settings_backup_transfer_section),
                            ),
                            BreadcrumbItem(
                                label = stringResource(R.string.export_title),
                                isCurrent = true,
                            ),
                        ),
                        onNavigate = onNavigate,
                        ellipsisContentDescription = stringResource(R.string.breadcrumb_ellipsis),
                        moreMenuContentDescription = stringResource(R.string.breadcrumb_more_ancestors),
                    )
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            when (state) {
                is ExportImportViewModel.ExportState.Idle -> {
                    Text(
                        text = stringResource(R.string.export_intro),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.export_scope_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ExportScopeChooser(onSelectScope = onSelectScope)
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
                is ExportImportViewModel.ExportState.SelectingItems -> {
                    SelectionContent(
                        items = state.items,
                        onConfirmSelection = onConfirmSelection,
                        onCancel = onCancel,
                    )
                }
                is ExportImportViewModel.ExportState.AwaitingReauth -> {
                    // Fresh Biometric / Device Credential re-auth is in progress
                    // (the system prompt is showing). This screen only renders a
                    // safe waiting hint; no PIN is collected here yet.
                    Text(
                        text = stringResource(R.string.reauth_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.export_reauth_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is ExportImportViewModel.ExportState.AwaitingPin -> {
                    PinEntry(
                        mode = PinEntryMode.EXPORT,
                        onSubmit = { pin, confirm ->
                            // Export policy check is delegated to the ViewModel
                            // (single source of truth); the returned Reason (if any)
                            // is surfaced by PinEntry. null → proceed.
                            onSubmitPin(pin, confirm ?: CharArray(0))
                        },
                        onCancel = onCancel,
                    )
                }
                is ExportImportViewModel.ExportState.AwaitingDestination -> {
                    Text(
                        text = stringResource(R.string.export_pin_accepted),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.export_choose_destination_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onChooseDestination, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.export_start))
                    }
                }
                is ExportImportViewModel.ExportState.Working -> {
                    Text(stringResource(R.string.common_working))
                }
                is ExportImportViewModel.ExportState.Success -> {
                    Text(
                        text = stringResource(R.string.export_success),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.export_success_detail),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onDismissResult, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.export_done))
                    }
                }
                is ExportImportViewModel.ExportState.Error -> {
                    Text(
                        text = stringResource(R.string.export_error_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onDismissResult, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.common_close))
                    }
                }
            }
        }
    }
}

/**
 * Import Native Package screen (Phase 3D §28).
 *
 * Flow: Import Native Package → SAF file → PIN → decode → Preview → Confirm
 * Import → result. The PIN is transient local state only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportNativePackageScreen(
    state: ExportImportViewModel.ImportState,
    onPickDocument: () -> Unit,
    onDecode: (pin: CharArray) -> Unit,
    onCancelPin: () -> Unit,
    onChooseScope: (ImportScopeSpec) -> Unit,
    onConfirmSelection: (SelectedItemSet) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onDismissResult: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigate: (String) -> Unit = {},
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    BreadcrumbTopBar(
                        items = listOf(
                            BreadcrumbItem(
                                label = stringResource(R.string.settings_title),
                                destination = RescueAuthRoutes.SETTINGS,
                            ),
                            BreadcrumbItem(
                                label = stringResource(R.string.settings_backup_transfer_section),
                            ),
                            BreadcrumbItem(
                                label = stringResource(R.string.import_title),
                                isCurrent = true,
                            ),
                        ),
                        onNavigate = onNavigate,
                        ellipsisContentDescription = stringResource(R.string.breadcrumb_ellipsis),
                        moreMenuContentDescription = stringResource(R.string.breadcrumb_more_ancestors),
                    )
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            when (state) {
                is ExportImportViewModel.ImportState.Idle -> {
                    Text(
                        text = stringResource(R.string.import_intro),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onPickDocument, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.import_pick_file))
                    }
                }
                is ExportImportViewModel.ImportState.AwaitingPin -> {
                    PinEntry(
                        mode = PinEntryMode.IMPORT,
                        onSubmit = { pin, _ ->
                            // Import only requires a non-empty PIN (any codec-legal
                            // package PIN must be decodable; Export's length/charset
                            // rules are not package-format requirements).
                            onDecode(pin)
                            null
                        },
                        onCancel = onCancelPin,
                    )
                }
                is ExportImportViewModel.ImportState.Decoding -> {
                    Text(stringResource(R.string.common_working))
                }
                is ExportImportViewModel.ImportState.ChoosingScope -> {
                    ImportScopeChooser(
                        preview = state.preview,
                        onChooseScope = onChooseScope,
                        onCancel = onCancel,
                    )
                }
                is ExportImportViewModel.ImportState.SelectingItems -> {
                    SelectionContent(
                        items = state.items,
                        onConfirmSelection = onConfirmSelection,
                        onCancel = onCancel,
                    )
                }
                is ExportImportViewModel.ImportState.Preview -> {
                    ImportPreviewContent(
                        preview = state.preview,
                        onConfirm = onConfirm,
                        onCancel = onCancel,
                    )
                }
                is ExportImportViewModel.ImportState.Applying -> {
                    Text(stringResource(R.string.common_working))
                }
                is ExportImportViewModel.ImportState.Result -> {
                    ImportResultContent(
                        result = state,
                        onDismiss = onDismissResult,
                    )
                }
                is ExportImportViewModel.ImportState.Error -> {
                    Text(
                        text = stringResource(R.string.import_error_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onDismissResult, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.common_close))
                    }
                }
            }
        }
    }
}

enum class PinEntryMode { EXPORT, IMPORT }

// ---------------------------------------------------------------------------
// Phase 4 P5 — scope pickers + item selection
// ---------------------------------------------------------------------------

/**
 * Export scope chooser (Issue #20 §3). Four scopes; Selected Items opens the
 * item picker.
 */
@Composable
private fun ExportScopeChooser(onSelectScope: (ExportScopeSpec) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(
            text = stringResource(R.string.export_scope_title),
            style = MaterialTheme.typography.titleSmall,
        )
        ScopeButton(
            label = stringResource(R.string.export_scope_full),
            tag = ExportImportTestTags.EXPORT_SCOPE_FULL,
        ) { onSelectScope(ExportScopeSpec.FullVault) }
        ScopeButton(
            label = stringResource(R.string.export_scope_auth),
            tag = ExportImportTestTags.EXPORT_SCOPE_AUTH,
        ) { onSelectScope(ExportScopeSpec.Authenticator) }
        ScopeButton(
            label = stringResource(R.string.export_scope_dev),
            tag = ExportImportTestTags.EXPORT_SCOPE_DEV,
        ) { onSelectScope(ExportScopeSpec.Developer) }
        ScopeButton(
            label = stringResource(R.string.export_scope_selected),
            tag = ExportImportTestTags.EXPORT_SCOPE_SELECTED,
        ) { onSelectScope(ExportScopeSpec.SelectedItems) }
    }
}

/**
 * Import scope chooser (Issue #20 §11). Section options are enabled only when
 * the package actually carries that section (safe preview counts).
 */
@Composable
private fun ImportScopeChooser(
    preview: com.rescueauth.v2.exportimport.ImportPreview,
    onChooseScope: (ImportScopeSpec) -> Unit,
    onCancel: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(
            text = stringResource(R.string.import_scope_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.import_scope_notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Safe content summary (no secrets).
        PreviewRow(stringResource(R.string.preview_accounts), preview.accounts.toString())
        PreviewRow(stringResource(R.string.preview_totp), preview.totpCredentials.toString())
        PreviewRow(stringResource(R.string.preview_recovery_sets), preview.recoverySets.toString())
        PreviewRow(stringResource(R.string.preview_developer), preview.developerSummary.total.toString())

        HorizontalDivider()

        val hasAuthenticator = preview.accounts > 0 || preview.totpCredentials > 0 || preview.recoverySets > 0
        val hasDeveloper = preview.developerSummary.total > 0

        ScopeButton(
            label = stringResource(R.string.import_scope_everything),
            tag = ExportImportTestTags.IMPORT_SCOPE_EVERYTHING,
        ) { onChooseScope(ImportScopeSpec.Everything) }
        ScopeButton(
            label = stringResource(R.string.import_scope_auth),
            tag = ExportImportTestTags.IMPORT_SCOPE_AUTH,
            enabled = hasAuthenticator,
        ) { onChooseScope(ImportScopeSpec.Authenticator) }
        ScopeButton(
            label = stringResource(R.string.import_scope_dev),
            tag = ExportImportTestTags.IMPORT_SCOPE_DEV,
            enabled = hasDeveloper,
        ) { onChooseScope(ImportScopeSpec.Developer) }
        ScopeButton(
            label = stringResource(R.string.import_scope_selected),
            tag = ExportImportTestTags.IMPORT_SCOPE_SELECTED,
        ) { onChooseScope(ImportScopeSpec.SelectedItems) }

        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.common_cancel))
        }
    }
}

@Composable
private fun ScopeButton(
    label: String,
    tag: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag),
    ) {
        Text(label)
    }
}

/**
 * Shared item-selection screen (export + import). Shows Authenticator
 * hierarchy (Provider → Account → TOTP / Recovery Set) and Developer entries
 * with safe metadata only. Secret values are NEVER displayed (Issue #20 §6,
 * §13, test 42).
 *
 * The selection is local Compose state (non-secret stableIds); the user
 * commits it via [onConfirmSelection] which moves the flow forward.
 */
@Composable
private fun SelectionContent(
    items: SelectableItems,
    onConfirmSelection: (SelectedItemSet) -> Unit,
    onCancel: () -> Unit,
) {
    var accounts by remember { mutableStateOf(setOf<String>()) }
    var totps by remember { mutableStateOf(setOf<String>()) }
    var sets by remember { mutableStateOf(setOf<String>()) }
    var developers by remember { mutableStateOf(setOf<String>()) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            text = stringResource(R.string.select_summary_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(
                R.string.select_summary_body,
                accounts.size,
                totps.size,
                sets.size,
                developers.size,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            OutlinedButton(
                onClick = {
                    accounts = items.providers.flatMap { it.expandToStableIds() }.toSet()
                    totps = items.providers.flatMap { p -> p.accounts.flatMap { a -> a.totpCredentials.map { it.stableId } } }.toSet()
                    sets = items.providers.flatMap { p -> p.accounts.flatMap { a -> a.recoveryCodeSets.map { it.stableId } } }.toSet()
                },
                modifier = Modifier
                    .weight(1f)
                    .testTag(ExportImportTestTags.EXPORT_SELECT_ALL_AUTH),
            ) {
                Text(stringResource(R.string.select_all_auth))
            }
            OutlinedButton(
                onClick = {
                    developers = items.developerEntries.map { it.stableId }.toSet()
                },
                modifier = Modifier
                    .weight(1f)
                    .testTag(ExportImportTestTags.EXPORT_SELECT_ALL_DEV),
            ) {
                Text(stringResource(R.string.select_all_dev))
            }
            OutlinedButton(
                onClick = {
                    accounts = emptySet()
                    totps = emptySet()
                    sets = emptySet()
                    developers = emptySet()
                },
                modifier = Modifier
                    .weight(1f)
                    .testTag(ExportImportTestTags.EXPORT_SELECT_CLEAR),
            ) {
                Text(stringResource(R.string.select_clear))
            }
        }

        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            if (items.providers.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.select_section_auth),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                items.providers.forEach { provider ->
                    val providerIds = provider.expandToStableIds()
                    SelectionProviderHeader(
                        serviceName = provider.serviceName,
                        selected = providerIds.all { it in accounts },
                        onToggle = { checked ->
                            accounts = if (checked) accounts + providerIds else accounts - providerIds
                        },
                    )
                    provider.accounts.forEach { account ->
                        SelectionAccountRow(
                            serviceName = account.serviceName,
                            accountName = account.accountName,
                            selected = account.stableId in accounts,
                            onToggle = { checked ->
                                accounts = if (checked) accounts + account.stableId else accounts - account.stableId
                            },
                        )
                        account.totpCredentials.forEach { totp ->
                            SelectionLeafRow(
                                label = totp.label,
                                selected = totp.stableId in totps,
                                onToggle = { checked ->
                                    totps = if (checked) totps + totp.stableId else totps - totp.stableId
                                },
                            )
                        }
                        account.recoveryCodeSets.forEach { set ->
                            SelectionLeafRow(
                                label = stringResource(
                                    R.string.select_recovery_set_label,
                                    set.title,
                                    set.remainingCount,
                                    set.codeCount,
                                ),
                                selected = set.stableId in sets,
                                onToggle = { checked ->
                                    sets = if (checked) sets + set.stableId else sets - set.stableId
                                },
                            )
                        }
                    }
                }
            }

            if (items.developerEntries.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.select_section_dev),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                items.developerEntries.forEach { entry ->
                    SelectionLeafRow(
                        label = developerSafeLabel(entry),
                        selected = entry.stableId in developers,
                        onToggle = { checked ->
                            developers = if (checked) developers + entry.stableId else developers - entry.stableId
                        },
                    )
                }
            }

            if (items.isEmpty) {
                Text(
                    text = stringResource(R.string.select_nothing_available),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val selected = SelectedItemSet(
            selectedAccountStableIds = accounts,
            selectedTotpStableIds = totps,
            selectedRecoverySetStableIds = sets,
            selectedDeveloperStableIds = developers,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.common_cancel))
            }
            Button(
                onClick = { onConfirmSelection(selected) },
                enabled = !selected.isEmpty,
                modifier = Modifier
                    .weight(1f)
                    .testTag(ExportImportTestTags.EXPORT_SELECT_CONTINUE),
            ) {
                Text(stringResource(R.string.select_continue))
            }
        }
    }
}

@Composable
private fun SelectionProviderHeader(
    serviceName: String,
    selected: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = onToggle)
        Text(
            text = serviceName,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        )
    }
}

@Composable
private fun SelectionAccountRow(
    serviceName: String,
    accountName: String,
    selected: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.md),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = onToggle)
        Text(
            text = "$serviceName · $accountName",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun SelectionLeafRow(
    label: String,
    selected: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.xl),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = onToggle)
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Safe display label for a Developer entry — never a secret value. */
private fun developerSafeLabel(entry: SelectableDeveloperEntry): String {
    val name = entry.displayName.ifBlank { entry.title }
    return when (entry.type) {
        "android_signing_key" -> "🔑 $name"
        "api_credential" -> "🔐 $name"
        "ssh_key" -> "🔒 $name"
        "environment_variable_set" -> "🌐 $name"
        else -> "📄 $name"
    }
}

/** Formats a [PinPolicy.Reason] message with the policy constants (UI presentation only). */
private fun pinPolicyMessage(context: Context, reason: PinPolicy.Reason): String =
    when (reason) {
        PinPolicy.Reason.EMPTY -> context.getString(R.string.pin_error_empty)
        PinPolicy.Reason.TOO_SHORT -> context.getString(R.string.pin_policy_error_short, PinPolicy.MIN_PIN_LENGTH)
        PinPolicy.Reason.TOO_LONG -> context.getString(R.string.pin_policy_error_long, PinPolicy.MAX_PIN_LENGTH)
        PinPolicy.Reason.NON_DIGIT -> context.getString(R.string.pin_policy_error_charset)
        PinPolicy.Reason.MISMATCH -> context.getString(R.string.pin_mismatch_error)
    }

/**
 * PIN entry for Export (enter + confirm) and Import (enter once). The PIN is
 * a local CharArray, cleared after submit/cancel; fields are hidden by default
 * with a reveal toggle (Issue #1 §9).
 *
 * All validation rules are evaluated by [PinPolicy] (single source of truth);
 * this composable only maps a returned [PinPolicy.Reason] to a string resource
 * and never embeds policy constants (charset / length) itself.
 */
@Composable
private fun PinEntry(
    mode: PinEntryMode,
    onSubmit: (CharArray, CharArray?) -> PinPolicy.Reason?,
    onCancel: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }
    var pinError by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current

    val transformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation()

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(
            text = if (mode == PinEntryMode.EXPORT) {
                stringResource(R.string.export_pin_title)
            } else {
                stringResource(R.string.import_pin_title)
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = if (mode == PinEntryMode.EXPORT) {
                stringResource(R.string.export_pin_policy)
            } else {
                stringResource(R.string.import_pin_policy)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it; pinError = null },
            label = { Text(stringResource(R.string.pin_label)) },
            visualTransformation = transformation,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword,
            ),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(
                    if (mode == PinEntryMode.EXPORT) {
                        ExportImportTestTags.EXPORT_PIN_FIELD
                    } else {
                        ExportImportTestTags.IMPORT_PIN_FIELD
                    },
                ),
        )
        if (mode == PinEntryMode.EXPORT) {
            OutlinedTextField(
                value = confirm,
                onValueChange = { confirm = it; pinError = null },
                label = { Text(stringResource(R.string.pin_confirm_label)) },
                visualTransformation = transformation,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword,
                ),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ExportImportTestTags.EXPORT_CONFIRM_FIELD),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            OutlinedButton(onClick = { revealed = !revealed }) {
                Text(
                    stringResource(
                        if (revealed) R.string.pin_hide else R.string.pin_show,
                    ),
                )
            }
        }

        if (pinError != null) {
            Text(
                text = pinError ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.common_cancel))
            }
            Button(
                onClick = {
                    val p = pin.toCharArray()
                    val c = if (mode == PinEntryMode.EXPORT) confirm.toCharArray() else null
                    val reason = onSubmit(p, c)
                    if (reason != null) {
                        pinError = pinPolicyMessage(context, reason)
                        p.fill('\u0000')
                        c?.fill('\u0000')
                    }
                    // On success the ViewModel owns the arrays and zeroizes them.
                },
                enabled = pin.isNotEmpty(),
                modifier = Modifier
                    .weight(1f)
                    .testTag(
                        if (mode == PinEntryMode.EXPORT) {
                            ExportImportTestTags.EXPORT_SUBMIT
                        } else {
                            ExportImportTestTags.IMPORT_SUBMIT
                        },
                    ),
            ) {
                Text(stringResource(R.string.pin_submit))
            }
        }
    }
}

@Composable
private fun ImportPreviewContent(
    preview: com.rescueauth.v2.exportimport.ImportPreview,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(
            text = stringResource(R.string.preview_title),
            style = MaterialTheme.typography.titleMedium,
        )

        // Package metadata
        Text(
            text = stringResource(R.string.preview_package_meta),
            style = MaterialTheme.typography.titleSmall,
        )
        PreviewRow(stringResource(R.string.preview_scope), preview.scope.name)
        PreviewRow(stringResource(R.string.preview_created), preview.createdAt.take(19))
        PreviewRow(stringResource(R.string.preview_source), preview.sourceAppVersion)

        HorizontalDivider()

        // Content summary
        Text(
            text = stringResource(R.string.preview_content_title),
            style = MaterialTheme.typography.titleSmall,
        )
        PreviewRow(stringResource(R.string.preview_accounts), preview.accounts.toString())
        PreviewRow(stringResource(R.string.preview_totp), preview.totpCredentials.toString())
        PreviewRow(stringResource(R.string.preview_recovery_sets), preview.recoverySets.toString())
        PreviewRow(stringResource(R.string.preview_recovery_codes), preview.recoveryCodes.toString())
        PreviewRow(stringResource(R.string.preview_developer), preview.developerSummary.total.toString())
        PreviewRow(stringResource(R.string.preview_dev_signing), preview.developerSummary.signingKeys.toString())
        PreviewRow(stringResource(R.string.preview_dev_api), preview.developerSummary.apiCredentials.toString())
        PreviewRow(stringResource(R.string.preview_dev_ssh), preview.developerSummary.sshKeys.toString())
        PreviewRow(stringResource(R.string.preview_dev_env), preview.developerSummary.envVarSets.toString())
        PreviewRow(stringResource(R.string.preview_dev_generic), preview.developerSummary.genericSecrets.toString())

        HorizontalDivider()

        // Merge summary
        Text(
            text = stringResource(R.string.preview_merge_title),
            style = MaterialTheme.typography.titleSmall,
        )
        PreviewRow(stringResource(R.string.preview_inserts), preview.inserts.toString())
        PreviewRow(stringResource(R.string.preview_duplicates), preview.duplicates.toString())
        PreviewRow(stringResource(R.string.preview_conflicts), preview.conflicts.toString())
        PreviewRow(stringResource(R.string.preview_unchanged), preview.unchanged.toString())
        PreviewRow(stringResource(R.string.preview_divergences), preview.stateDivergences.toString())

        if (preview.blocked) {
            Spacer(modifier = Modifier.height(Spacing.sm))
            Text(
                text = stringResource(R.string.preview_blocked_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = stringResource(R.string.preview_blocked_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(modifier = Modifier.height(Spacing.sm))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.common_cancel))
            }
            Button(
                onClick = onConfirm,
                enabled = !preview.blocked,
                modifier = Modifier
                    .weight(1f)
                    .testTag(ExportImportTestTags.IMPORT_CONFIRM),
            ) {
                Text(stringResource(R.string.preview_confirm_import))
            }
        }
        if (preview.blocked) {
            Text(
                text = stringResource(R.string.preview_blocked_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PreviewRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ImportResultContent(
    result: ExportImportViewModel.ImportState.Result,
    onDismiss: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        if (result.blocked) {
            Text(
                text = stringResource(R.string.import_result_blocked_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = stringResource(
                    R.string.import_result_blocked_body,
                    result.conflicts,
                    result.stateDivergences,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Text(
                text = stringResource(R.string.import_result_title),
                style = MaterialTheme.typography.titleMedium,
            )
            if (result.imported == 0 && result.duplicates > 0) {
                Text(
                    text = stringResource(R.string.import_result_nothing_new),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    text = stringResource(
                        R.string.import_result_summary,
                        result.imported,
                        result.duplicates,
                        result.developerImported,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.common_close))
        }
    }
}
