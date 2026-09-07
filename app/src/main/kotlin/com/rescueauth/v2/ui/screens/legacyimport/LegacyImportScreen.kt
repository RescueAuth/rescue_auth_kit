package com.rescueauth.v2.ui.screens.legacyimport

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.rescueauth.v2.ui.components.RescueAuthButton as Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import com.rescueauth.v2.ui.components.RescueAuthOutlinedButton as OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
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
import com.rescueauth.v2.legacyimport.LegacyImportPreview
import com.rescueauth.v2.legacyimport.LegacyImportViewModel
import com.rescueauth.v2.ui.components.RescueAuthBackButton
import com.rescueauth.v2.ui.components.RescueAuthCard
import com.rescueauth.v2.ui.components.RescueAuthDivider
import com.rescueauth.v2.ui.components.RescueAuthIconBadge
import com.rescueauth.v2.ui.components.RescueAuthPageHeader
import com.rescueauth.v2.ui.components.RescueAuthSectionHeader
import com.rescueauth.v2.ui.theme.Spacing
import com.rescueauth.v2.ui.theme.ScreenTokens
import com.rescueauth.v2.ui.theme.CardTokens

object LegacyImportTestTags {
    const val SCREEN = "screen_legacy_import"
    const val PICK_FILE = "legacy_import_pick_file"
    const val PASSWORD_FIELD = "legacy_import_password_field"
    const val PASSWORD_SUBMIT = "legacy_import_password_submit"
    const val PASSWORD_CANCEL = "legacy_import_password_cancel"
    const val CONFIRM = "legacy_import_confirm"
    const val CANCEL = "legacy_import_cancel"
}

/**
 * Legacy v1 `.rakvault` import screen (Phase 5B).
 *
 * Flow: choose `.rakvault` → Enter Legacy Master Password → Decode → Safe
 * Preview → Confirm Import → Success summary. The password is held in
 * transient local state only (never SavedStateHandle / Bundle / rememberSaveable
 * / DataStore / Room / logs); the ViewModel zeroizes it after each submit.
 *
 * ## Layout contract (Issue #51)
 *
 * ```
 * Scaffold
 * └── Column (fillMaxSize, imePadding)
 *     ├── Content (weight(1f), verticalScroll)
 *     └── Actions (fixed at bottom)
 * ```
 *
 * The Content area scrolls when long and shrinks when short; the bottom
 * Actions (Cancel / Confirm) are always reachable regardless of content
 * length, screen size, font scale, or IME visibility.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegacyImportScreen(
    state: LegacyImportViewModel.State,
    onPickFile: () -> Unit,
    onSubmitPassword: (CharArray) -> Boolean,
    onRetryPassword: () -> Unit,
    onCancelPassword: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onDismissResult: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigate: (String) -> Unit = {},
) {
    Scaffold(
        modifier = modifier.fillMaxSize().testTag(LegacyImportTestTags.SCREEN),
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            RescueAuthPageHeader(
                title = stringResource(R.string.legacy_import_title),
                subtitle = stringResource(R.string.settings_backup_transfer_section),
                navigationIcon = { RescueAuthBackButton(onBack) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .padding(horizontal = ScreenTokens.horizontalPadding, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            when (state) {
                is LegacyImportViewModel.State.Idle -> {
                    // Short content — wrapped in a scrollable column so large
                    // fonts / small screens never clip the "Pick file" button.
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        RescueAuthCard(containerColor = com.rescueauth.v2.ui.theme.CardTokens.containerColor()) {
                            RescueAuthSectionHeader(
                                title = stringResource(R.string.legacy_import_title),
                                subtitle = stringResource(R.string.legacy_import_intro),
                            )
                        }
                        Text(
                            text = stringResource(R.string.legacy_import_notice),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = onPickFile,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(LegacyImportTestTags.PICK_FILE),
                        ) {
                            Text(stringResource(R.string.legacy_import_pick_file))
                        }
                    }
                }
                is LegacyImportViewModel.State.FileSelected -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        RescueAuthCard {
                            RescueAuthSectionHeader(
                                title = stringResource(R.string.legacy_import_file_selected),
                                subtitle = state.fileName ?: stringResource(R.string.common_unknown),
                            )
                            Text(
                                text = stringResource(R.string.common_working),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                is LegacyImportViewModel.State.AwaitingPassword -> {
                    // Password entry — scrollable content + fixed action row so
                    // the IME never obscures Cancel/Submit (Issue #51 §9).
                    LegacyPasswordEntry(
                        onSubmit = onSubmitPassword,
                        onCancel = onCancelPassword,
                    )
                }
                is LegacyImportViewModel.State.Decrypting -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        RescueAuthSectionHeader(title = stringResource(R.string.common_working))
                    }
                }
                is LegacyImportViewModel.State.Preview -> {
                    LegacyPreviewContent(
                        preview = state.preview,
                        onConfirm = onConfirm,
                        onCancel = onCancel,
                    )
                }
                is LegacyImportViewModel.State.Applying -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        RescueAuthSectionHeader(title = stringResource(R.string.common_working))
                    }
                }
                is LegacyImportViewModel.State.Success -> {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        RescueAuthCard(
                            containerColor = if (state.blocked) {
                                MaterialTheme.colorScheme.errorContainer
                            } else {
                                MaterialTheme.colorScheme.primaryContainer
                            },
                        ) {
                            if (state.blocked) {
                                RescueAuthSectionHeader(
                                    title = stringResource(R.string.import_result_blocked_title),
                                    subtitle = stringResource(
                                        R.string.import_result_blocked_body,
                                        state.conflicts,
                                        state.stateDivergences,
                                    ),
                                )
                            } else {
                                RescueAuthSectionHeader(
                                    title = stringResource(R.string.import_result_title),
                                    subtitle = if (state.imported == 0 && state.duplicates > 0) {
                                        stringResource(R.string.import_result_nothing_new)
                                    } else {
                                        stringResource(
                                            R.string.import_result_summary,
                                            state.imported,
                                            state.duplicates,
                                            state.developerImported,
                                        )
                                    },
                                )
                            }
                        }
                    }
                    Button(
                        onClick = onDismissResult,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.common_close))
                    }
                }
                is LegacyImportViewModel.State.Error -> {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        RescueAuthCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
                            RescueAuthSectionHeader(
                                title = stringResource(R.string.legacy_import_error_title),
                                subtitle = state.message,
                            )
                        }
                    }
                    when (state.action) {
                        LegacyImportViewModel.ErrorAction.RETRY_PASSWORD -> {
                            Button(
                                onClick = onRetryPassword,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.common_retry))
                            }
                        }
                        LegacyImportViewModel.ErrorAction.RESTART,
                        LegacyImportViewModel.ErrorAction.BLOCKED,
                        -> {
                            Button(
                                onClick = onDismissResult,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.common_close))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Legacy Master Password entry (Issue #51 §9).
 *
 * The password is held in transient local state here and zeroized by the
 * caller after each submit. The content area scrolls (for small screens /
 * large fonts / IME) while the action row stays pinned to the bottom.
 */
@Composable
private fun ColumnScope.LegacyPasswordEntry(
    onSubmit: (CharArray) -> Boolean,
    onCancel: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }
    val transformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation()

    Column(
        modifier = Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        RescueAuthCard(containerColor = CardTokens.containerColor()) {
            RescueAuthIconBadge(
                icon = Icons.Filled.Lock,
                size = 40.dp,
                iconSize = 20.dp,
            )
            RescueAuthSectionHeader(
                title = stringResource(R.string.legacy_password_title),
                subtitle = stringResource(R.string.legacy_password_policy),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.legacy_password_label)) },
                visualTransformation = transformation,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                ),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.md)
                    .testTag(LegacyImportTestTags.PASSWORD_FIELD),
            )
            TextButton(onClick = { revealed = !revealed }) {
                Text(stringResource(if (revealed) R.string.pin_hide else R.string.pin_show))
            }
        }
    }

    // Fixed bottom action row — always reachable even with IME open.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier
                .weight(1f)
                .testTag(LegacyImportTestTags.PASSWORD_CANCEL),
        ) {
            Text(stringResource(R.string.common_cancel))
        }
        Button(
            onClick = {
                val p = password.toCharArray()
                if (!onSubmit(p)) {
                    p.fill('\u0000')
                }
                // On success the ViewModel owns the array and zeroizes it.
            },
            enabled = password.isNotEmpty(),
            modifier = Modifier
                .weight(1f)
                .testTag(LegacyImportTestTags.PASSWORD_SUBMIT),
        ) {
            Text(stringResource(R.string.pin_submit))
        }
    }
}

/**
 * Safe Legacy preview — scrollable content + pinned bottom actions.
 *
 * The content area (metadata + merge summary) scrolls independently when the
 * preview is long; the Cancel / Import row is always visible at the bottom so
 * a real-device user can always complete the import (Issue #51 §3/§5/§6).
 */
@Composable
private fun ColumnScope.LegacyPreviewContent(
    preview: LegacyImportPreview,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    // Scrollable content area (weight 1f so actions stay pinned at bottom).
    Column(
        modifier = Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        RescueAuthCard(containerColor = CardTokens.containerColor()) {
            RescueAuthIconBadge(
                icon = Icons.Filled.History,
                size = 40.dp,
                iconSize = 20.dp,
            )
            RescueAuthSectionHeader(
                title = stringResource(R.string.legacy_preview_title),
                subtitle = stringResource(R.string.preview_content_title),
            )

        // Source metadata (non-secret)
        PreviewRow(stringResource(R.string.legacy_preview_schema), preview.schemaVersion.toString())
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

        RescueAuthDivider()

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
                text = stringResource(R.string.legacy_preview_blocked_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = stringResource(R.string.legacy_preview_blocked_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (preview.blocked) {
            Spacer(modifier = Modifier.height(Spacing.sm))
            Text(
                text = stringResource(R.string.legacy_preview_blocked_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Bottom spacer so the last row never rubs against the action bar.
        Spacer(modifier = Modifier.height(Spacing.sm))
        }
    }

    // Fixed bottom action row — always visible, never scrolled out of view.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier
                .weight(1f)
                .testTag(LegacyImportTestTags.CANCEL),
        ) {
            Text(stringResource(R.string.common_cancel))
        }
        Button(
            onClick = onConfirm,
            enabled = !preview.blocked,
            modifier = Modifier
                .weight(1f)
                .testTag(LegacyImportTestTags.CONFIRM),
        ) {
            Text(stringResource(R.string.preview_confirm_import))
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
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(Spacing.sm))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}
