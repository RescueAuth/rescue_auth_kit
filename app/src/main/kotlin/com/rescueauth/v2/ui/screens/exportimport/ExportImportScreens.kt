package com.rescueauth.v2.ui.screens.exportimport

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.rescueauth.v2.ui.components.RescueAuthButton as Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.rescueauth.v2.ui.components.RescueAuthOutlinedButton as OutlinedButton
import com.rescueauth.v2.ui.components.RescueAuthTextField
import com.rescueauth.v2.ui.components.RescueAuthVisibilityToggle
import androidx.compose.material3.Text
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.VpnKey
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
import androidx.compose.ui.unit.dp
import com.rescueauth.v2.R
import com.rescueauth.v2.export.SelectableDeveloperEntry
import com.rescueauth.v2.export.SelectableItems
import com.rescueauth.v2.export.SelectedItemSet
import com.rescueauth.v2.export.SnapshotScope
import com.rescueauth.v2.exportimport.ExportImportViewModel
import com.rescueauth.v2.exportimport.ExportScopeSpec
import com.rescueauth.v2.exportimport.ImportScopeSpec
import com.rescueauth.v2.exportimport.PinPolicy
import com.rescueauth.v2.ui.components.RescueAuthCard
import com.rescueauth.v2.ui.components.RescueAuthFormPage
import com.rescueauth.v2.ui.components.RescueAuthActionBar
import com.rescueauth.v2.ui.components.RescueAuthBottomBar
import com.rescueauth.v2.ui.components.RescueAuthSummaryCard
import com.rescueauth.v2.ui.components.RescueAuthSummaryRow
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import com.rescueauth.v2.ui.components.RescueAuthDivider
import com.rescueauth.v2.ui.components.RescueAuthIconBadge
import com.rescueauth.v2.ui.components.RescueAuthSectionHeader
import com.rescueauth.v2.ui.components.RescueAuthRowCard
import com.rescueauth.v2.ui.theme.Spacing
import com.rescueauth.v2.ui.theme.CardTokens
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Lock

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
    onBack: (() -> Unit)? = null,
) {
    val title = stringResource(R.string.export_title)
    val back = onBack ?: onCancel
    when (state) {
        ExportImportViewModel.ExportState.AwaitingPin -> PinEntry(
            mode = PinEntryMode.EXPORT, title = title, onBack = back, modifier = modifier,
            onSubmit = { pin, confirm -> onSubmitPin(pin, confirm ?: CharArray(0)) }, onCancel = onCancel)
        is ExportImportViewModel.ExportState.SelectingItems -> SelectionContent(
            items = state.items, onConfirmSelection = onConfirmSelection, onCancel = onCancel,
            title = title, onBack = back, modifier = modifier)
        else -> RescueAuthFormPage(
            title = title, subtitle = stringResource(R.string.settings_backup_transfer_section),
            onBack = back, modifier = modifier,
            bottomBar = {
                when (state) {
                    ExportImportViewModel.ExportState.Idle -> TransferFooter(stringResource(R.string.common_cancel), onCancel, outlined = true)
                    ExportImportViewModel.ExportState.AwaitingDestination -> TransferFooter(stringResource(R.string.export_start), onChooseDestination)
                    is ExportImportViewModel.ExportState.Success -> TransferFooter(stringResource(R.string.export_done), onDismissResult)
                    is ExportImportViewModel.ExportState.Error -> TransferFooter(stringResource(R.string.common_close), onDismissResult)
                    else -> Unit
                }
            },
        ) {
            when (state) {
                ExportImportViewModel.ExportState.Idle -> {
                    RescueAuthCard {
                        RescueAuthSectionHeader(stringResource(R.string.export_title), stringResource(R.string.export_intro))
                    }
                    ExportScopeChooser(onSelectScope)
                    Text(stringResource(R.string.export_scope_notice), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ExportImportViewModel.ExportState.AwaitingReauth -> RescueAuthCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        RescueAuthIconBadge(Icons.Filled.Lock)
                        Column {
                            Text(stringResource(R.string.reauth_title), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.export_reauth_hint), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                ExportImportViewModel.ExportState.AwaitingDestination -> RescueAuthCard {
                    RescueAuthSectionHeader(stringResource(R.string.export_pin_accepted), stringResource(R.string.export_choose_destination_hint))
                }
                ExportImportViewModel.ExportState.Working -> RescueAuthCard {
                    RescueAuthSectionHeader(stringResource(R.string.common_working))
                }
                is ExportImportViewModel.ExportState.Success -> RescueAuthCard {
                    RescueAuthSectionHeader(stringResource(R.string.export_success), stringResource(R.string.export_success_detail))
                }
                is ExportImportViewModel.ExportState.Error -> RescueAuthCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
                    RescueAuthSectionHeader(stringResource(R.string.export_error_title), state.message)
                }
                else -> Unit
            }
        }
    }
}

/** Native package state, PIN policy and callbacks remain separate from the legacy adapter. */
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
    onBack: (() -> Unit)? = null,
) {
    val title = stringResource(R.string.import_title)
    val back = onBack ?: onCancel
    when (state) {
        ExportImportViewModel.ImportState.AwaitingPin -> PinEntry(
            mode = PinEntryMode.IMPORT, title = title, onBack = back, modifier = modifier,
            onSubmit = { pin, _ -> onDecode(pin); null }, onCancel = onCancelPin)
        is ExportImportViewModel.ImportState.SelectingItems -> SelectionContent(
            items = state.items, onConfirmSelection = onConfirmSelection, onCancel = onCancel,
            title = title, onBack = back, modifier = modifier)
        is ExportImportViewModel.ImportState.Preview -> ImportPreviewContent(
            preview = state.preview, onConfirm = onConfirm, onCancel = onCancel,
            title = title, onBack = back, modifier = modifier)
        else -> RescueAuthFormPage(
            title = title, subtitle = stringResource(R.string.settings_backup_transfer_section),
            onBack = back, modifier = modifier,
            bottomBar = {
                when (state) {
                    ExportImportViewModel.ImportState.Idle -> TransferFooter(stringResource(R.string.import_pick_file), onPickDocument)
                    is ExportImportViewModel.ImportState.ChoosingScope -> TransferFooter(stringResource(R.string.common_cancel), onCancel, outlined = true)
                    is ExportImportViewModel.ImportState.Result, is ExportImportViewModel.ImportState.Error ->
                        TransferFooter(stringResource(R.string.common_close), onDismissResult)
                    else -> Unit
                }
            },
        ) {
            when (state) {
                ExportImportViewModel.ImportState.Idle -> RescueAuthCard {
                    RescueAuthSectionHeader(stringResource(R.string.import_title), stringResource(R.string.import_intro))
                }
                ExportImportViewModel.ImportState.Decoding, ExportImportViewModel.ImportState.Applying -> RescueAuthCard {
                    RescueAuthSectionHeader(stringResource(R.string.common_working))
                }
                is ExportImportViewModel.ImportState.ChoosingScope -> ImportScopeChooser(state.preview, onChooseScope)
                is ExportImportViewModel.ImportState.Result -> ImportResultContent(state)
                is ExportImportViewModel.ImportState.Error -> RescueAuthCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
                    RescueAuthSectionHeader(stringResource(R.string.import_error_title), state.message)
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun TransferFooter(label: String, onClick: () -> Unit, outlined: Boolean = false) {
    RescueAuthBottomBar {
        if (outlined) OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label) }
        else Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label) }
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
    RescueAuthCard(containerColor = CardTokens.elevatedContainerColor()) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            RescueAuthSectionHeader(title = stringResource(R.string.export_scope_title))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                ScopeButton(
                    label = stringResource(R.string.export_scope_full),
                    tag = ExportImportTestTags.EXPORT_SCOPE_FULL,
                    modifier = Modifier.weight(1f),
                ) { onSelectScope(ExportScopeSpec.FullVault) }
                ScopeButton(
                    label = stringResource(R.string.export_scope_auth),
                    tag = ExportImportTestTags.EXPORT_SCOPE_AUTH,
                    modifier = Modifier.weight(1f),
                ) { onSelectScope(ExportScopeSpec.Authenticator) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                ScopeButton(
                    label = stringResource(R.string.export_scope_dev),
                    tag = ExportImportTestTags.EXPORT_SCOPE_DEV,
                    modifier = Modifier.weight(1f),
                ) { onSelectScope(ExportScopeSpec.Developer) }
                ScopeButton(
                    label = stringResource(R.string.export_scope_selected),
                    tag = ExportImportTestTags.EXPORT_SCOPE_SELECTED,
                    modifier = Modifier.weight(1f),
                ) { onSelectScope(ExportScopeSpec.SelectedItems) }
            }
        }
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
) {
    RescueAuthCard(containerColor = CardTokens.elevatedContainerColor()) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            RescueAuthSectionHeader(
                title = stringResource(R.string.import_scope_title),
                subtitle = stringResource(R.string.import_scope_notice),
            )

            RescueAuthSummaryRow(stringResource(R.string.preview_accounts), preview.accounts.toString())
            RescueAuthSummaryRow(stringResource(R.string.preview_totp), preview.totpCredentials.toString())
            RescueAuthSummaryRow(stringResource(R.string.preview_recovery_sets), preview.recoverySets.toString())
            RescueAuthSummaryRow(stringResource(R.string.preview_developer), preview.developerSummary.total.toString())

            RescueAuthDivider()

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

        }
    }
}

@Composable
private fun ScopeButton(
    label: String,
    tag: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .then(modifier)
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
    title: String,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    var accounts by remember { mutableStateOf(setOf<String>()) }
    var totps by remember { mutableStateOf(setOf<String>()) }
    var sets by remember { mutableStateOf(setOf<String>()) }
    var developers by remember { mutableStateOf(setOf<String>()) }

    val selected = SelectedItemSet(
        selectedAccountStableIds = accounts,
        selectedTotpStableIds = totps,
        selectedRecoverySetStableIds = sets,
        selectedDeveloperStableIds = developers,
    )
    RescueAuthFormPage(
        title = title, subtitle = stringResource(R.string.settings_backup_transfer_section),
        onBack = onBack, modifier = modifier,
        bottomBar = {
            RescueAuthActionBar(
                secondaryLabel = stringResource(R.string.common_cancel), secondaryIcon = Icons.Filled.Close,
                onSecondaryClick = onCancel,
                primaryLabel = stringResource(R.string.select_continue), primaryIcon = Icons.AutoMirrored.Filled.ArrowForward,
                onPrimaryClick = { onConfirmSelection(selected) }, primaryEnabled = !selected.isEmpty,
                primaryTestTag = ExportImportTestTags.EXPORT_SELECT_CONTINUE,
            )
        },
    ) {
        RescueAuthCard(containerColor = CardTokens.elevatedContainerColor()) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                RescueAuthSectionHeader(
                    title = stringResource(R.string.select_summary_title),
                    subtitle = stringResource(
                        R.string.select_summary_body,
                        accounts.size,
                        totps.size,
                        sets.size,
                        developers.size,
                    ),
                )
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    OutlinedButton(
                        onClick = {
                            accounts = items.providers.flatMap { it.expandToStableIds() }.toSet()
                            totps = items.providers.flatMap { p -> p.accounts.flatMap { a -> a.totpCredentials.map { it.stableId } } }.toSet()
                            sets = items.providers.flatMap { p -> p.accounts.flatMap { a -> a.recoveryCodeSets.map { it.stableId } } }.toSet()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(ExportImportTestTags.EXPORT_SELECT_ALL_AUTH),
                    ) {
                        Text(stringResource(R.string.select_all_auth))
                    }
                    OutlinedButton(
                        onClick = {
                            developers = items.developerEntries.map { it.stableId }.toSet()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
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
                            .fillMaxWidth()
                            .testTag(ExportImportTestTags.EXPORT_SELECT_CLEAR),
                    ) {
                        Text(stringResource(R.string.select_clear))
                    }
                }
            }
        }


        if (items.providers.isNotEmpty()) {
            RescueAuthSectionHeader(title = stringResource(R.string.select_section_auth))
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
            RescueAuthSectionHeader(title = stringResource(R.string.select_section_dev))
            items.developerEntries.forEach { entry ->
                SelectionLeafRow(
                    label = developerSafeLabel(entry),
                    icon = developerSafeIcon(entry.type),
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
}

@Composable
private fun SelectionProviderHeader(
    serviceName: String,
    selected: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    RescueAuthRowCard(
        modifier = Modifier.padding(start = Spacing.md),
        containerColor = CardTokens.elevatedContainerColor(),
        verticalPadding = Spacing.xs,
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
    RescueAuthRowCard(
        modifier = Modifier.padding(start = Spacing.md),
        containerColor = CardTokens.elevatedContainerColor(),
        verticalPadding = Spacing.xs,
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
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    selected: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    RescueAuthRowCard(
        modifier = Modifier.padding(start = Spacing.xl),
        containerColor = CardTokens.containerColor(),
        verticalPadding = Spacing.xs,
    ) {
        Checkbox(checked = selected, onCheckedChange = onToggle)
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
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
    return name
}

private fun developerSafeIcon(type: String): androidx.compose.ui.graphics.vector.ImageVector = when (type) {
    "android_signing_key" -> Icons.Filled.VpnKey
    "api_credential" -> Icons.Filled.Key
    "ssh_key" -> Icons.Filled.Lock
    "environment_variable_set" -> Icons.Filled.Build
    else -> Icons.Filled.Description
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
 * PIN entry for Export (enter + confirm) and Import (enter once). Input strings
 * are local, non-saveable state removed with this step. Submission creates
 * CharArrays: rejected arrays are cleared here; accepted arrays belong to the
 * receiving ViewModel. Fields are hidden by default with a reveal toggle.
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
    title: String,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }
    var confirmRevealed by remember { mutableStateOf(false) }
    var pinError by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current

    val transformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation()

    RescueAuthFormPage(
        title = title, subtitle = stringResource(R.string.settings_backup_transfer_section),
        onBack = onBack, modifier = modifier,
        bottomBar = {
            RescueAuthActionBar(
                secondaryLabel = stringResource(R.string.common_cancel), secondaryIcon = Icons.Filled.Close,
                onSecondaryClick = onCancel,
                secondaryTestTag = if (mode == PinEntryMode.EXPORT) ExportImportTestTags.EXPORT_CANCEL else ExportImportTestTags.IMPORT_CANCEL,
                primaryLabel = stringResource(R.string.pin_submit), primaryIcon = Icons.AutoMirrored.Filled.ArrowForward,
                primaryEnabled = pin.isNotEmpty(),
                primaryTestTag = if (mode == PinEntryMode.EXPORT) ExportImportTestTags.EXPORT_SUBMIT else ExportImportTestTags.IMPORT_SUBMIT,
                onPrimaryClick = {
                    val p = pin.toCharArray()
                    val c = if (mode == PinEntryMode.EXPORT) confirm.toCharArray() else null
                    val reason = onSubmit(p, c)
                    if (reason != null) {
                        pinError = pinPolicyMessage(context, reason)
                        p.fill('\u0000')
                        c?.fill('\u0000')
                    }
                    // The receiving native ViewModel owns accepted arrays and clears them.
                },
            )
        },
    ) {
        RescueAuthCard(containerColor = com.rescueauth.v2.ui.theme.CardTokens.containerColor()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                RescueAuthIconBadge(icon = Icons.Filled.Lock, size = 40.dp, iconSize = 20.dp)
                RescueAuthSectionHeader(
                    title = if (mode == PinEntryMode.EXPORT) {
                        stringResource(R.string.export_pin_title)
                    } else {
                        stringResource(R.string.import_pin_title)
                    },
                    subtitle = if (mode == PinEntryMode.EXPORT) {
                        stringResource(R.string.export_pin_policy)
                    } else {
                        stringResource(R.string.import_pin_policy)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        RescueAuthCard(containerColor = CardTokens.elevatedContainerColor()) {
            RescueAuthTextField(
                value = pin,
                onValueChange = { pin = it; pinError = null },
                label = { Text(stringResource(R.string.pin_label)) },
                visualTransformation = transformation,
                trailingIcon = {
                    RescueAuthVisibilityToggle(revealed, { revealed = !revealed },
                        Modifier.testTag("pin_visibility"))
                },
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
                RescueAuthTextField(
                    value = confirm,
                    onValueChange = { confirm = it; pinError = null },
                    label = { Text(stringResource(R.string.pin_confirm_label)) },
                    visualTransformation = if (confirmRevealed) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        RescueAuthVisibilityToggle(confirmRevealed, { confirmRevealed = !confirmRevealed },
                            Modifier.testTag("pin_confirm_visibility"))
                    },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword,
                    ),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(ExportImportTestTags.EXPORT_CONFIRM_FIELD),
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

    }
}

@Composable
private fun ImportPreviewContent(
    preview: com.rescueauth.v2.exportimport.ImportPreview,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    title: String,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    RescueAuthFormPage(
        title = title, subtitle = stringResource(R.string.settings_backup_transfer_section), onBack = onBack,
        modifier = modifier,
        bottomBar = {
            RescueAuthActionBar(
                secondaryLabel = stringResource(R.string.common_cancel), secondaryIcon = Icons.Filled.Close,
                onSecondaryClick = onCancel, secondaryTestTag = ExportImportTestTags.IMPORT_CANCEL,
                primaryLabel = stringResource(R.string.preview_confirm_import), primaryIcon = Icons.Filled.Check,
                onPrimaryClick = onConfirm, primaryEnabled = !preview.blocked, primaryTestTag = ExportImportTestTags.IMPORT_CONFIRM,
            )
        },
    ) {
        RescueAuthSummaryCard(stringResource(R.string.preview_title), listOf(
            stringResource(R.string.preview_scope) to stringResource(when (preview.scope) {
                SnapshotScope.FULL_VAULT -> R.string.export_scope_full
                SnapshotScope.AUTHENTICATOR_ONLY -> R.string.export_scope_auth
                SnapshotScope.DEVELOPER_ONLY -> R.string.export_scope_dev
                SnapshotScope.SELECTED_ITEMS -> R.string.preview_scope_selected
            }),
            stringResource(R.string.preview_created) to preview.createdAt.take(19),
            stringResource(R.string.preview_source) to preview.sourceAppVersion,
        ), icon = Icons.Filled.FileDownload)
        RescueAuthSummaryCard(stringResource(R.string.preview_content_title), listOf(
            stringResource(R.string.preview_accounts) to preview.accounts.toString(),
            stringResource(R.string.preview_totp) to preview.totpCredentials.toString(),
            stringResource(R.string.preview_recovery_sets) to preview.recoverySets.toString(),
            stringResource(R.string.preview_recovery_codes) to preview.recoveryCodes.toString(),
            stringResource(R.string.preview_developer) to preview.developerSummary.total.toString(),
            stringResource(R.string.preview_dev_signing) to preview.developerSummary.signingKeys.toString(),
            stringResource(R.string.preview_dev_api) to preview.developerSummary.apiCredentials.toString(),
            stringResource(R.string.preview_dev_ssh) to preview.developerSummary.sshKeys.toString(),
            stringResource(R.string.preview_dev_env) to preview.developerSummary.envVarSets.toString(),
            stringResource(R.string.preview_dev_generic) to preview.developerSummary.genericSecrets.toString(),
        ))
        RescueAuthSummaryCard(stringResource(R.string.preview_merge_title), listOf(
            stringResource(R.string.preview_inserts) to preview.inserts.toString(),
            stringResource(R.string.preview_duplicates) to preview.duplicates.toString(),
            stringResource(R.string.preview_conflicts) to preview.conflicts.toString(),
            stringResource(R.string.preview_unchanged) to preview.unchanged.toString(),
            stringResource(R.string.preview_divergences) to preview.stateDivergences.toString(),
        ))
        if (preview.blocked) RescueAuthCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
            RescueAuthSectionHeader(stringResource(R.string.preview_blocked_title), stringResource(R.string.preview_blocked_body))
            Text(stringResource(R.string.preview_blocked_hint), Modifier.padding(top = Spacing.sm),
                style = MaterialTheme.typography.bodySmall)
        }
    }
}




@Composable
private fun ImportResultContent(
    result: ExportImportViewModel.ImportState.Result,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        RescueAuthCard(
            containerColor = if (result.blocked) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.primaryContainer
            },
        ) {
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
        }
    }
}
