package com.rescueauth.v2.ui.screens.legacyimport

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.rescueauth.v2.legacyimport.LegacyImportPreview
import com.rescueauth.v2.legacyimport.LegacyImportViewModel
import com.rescueauth.v2.ui.components.RescueAuthActionBar
import com.rescueauth.v2.ui.components.RescueAuthBottomBar
import com.rescueauth.v2.ui.components.RescueAuthButton
import com.rescueauth.v2.ui.components.RescueAuthCard
import com.rescueauth.v2.ui.components.RescueAuthFormPage
import com.rescueauth.v2.ui.components.RescueAuthIconBadge
import com.rescueauth.v2.ui.components.RescueAuthSectionHeader
import com.rescueauth.v2.ui.components.RescueAuthSummaryCard
import com.rescueauth.v2.ui.components.RescueAuthTextField
import com.rescueauth.v2.ui.components.RescueAuthVisibilityToggle
import com.rescueauth.v2.ui.theme.Spacing

object LegacyImportTestTags {
    const val SCREEN = "screen_legacy_import"
    const val PICK_FILE = "legacy_import_pick_file"
    const val PASSWORD_FIELD = "legacy_import_password_field"
    const val PASSWORD_SUBMIT = "legacy_import_password_submit"
    const val PASSWORD_CANCEL = "legacy_import_password_cancel"
    const val CONFIRM = "legacy_import_confirm"
    const val CANCEL = "legacy_import_cancel"
}

/** Legacy states and password policy stay independent; only the presentation shell is shared. */
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
    when (state) {
        LegacyImportViewModel.State.AwaitingPassword -> LegacyPasswordEntry(onSubmitPassword, onCancelPassword, onBack, modifier)
        is LegacyImportViewModel.State.Preview -> LegacyPreviewContent(state.preview, onConfirm, onCancel, onBack, modifier)
        else -> LegacyPage(onBack, modifier, bottomBar = {
            when (state) {
                LegacyImportViewModel.State.Idle -> LegacyFooterAction(
                    stringResource(R.string.legacy_import_pick_file), onPickFile, LegacyImportTestTags.PICK_FILE)
                is LegacyImportViewModel.State.Success -> LegacyFooterAction(stringResource(R.string.common_close), onDismissResult)
                is LegacyImportViewModel.State.Error -> if (state.action == LegacyImportViewModel.ErrorAction.RETRY_PASSWORD) {
                    LegacyFooterAction(stringResource(R.string.common_retry), onRetryPassword)
                } else LegacyFooterAction(stringResource(R.string.common_close), onDismissResult)
                else -> Unit
            }
        }) {
            when (state) {
                LegacyImportViewModel.State.Idle -> {
                    RescueAuthCard {
                        RescueAuthSectionHeader(stringResource(R.string.legacy_import_title),
                            stringResource(R.string.legacy_import_intro))
                    }
                    Text(stringResource(R.string.legacy_import_notice), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is LegacyImportViewModel.State.FileSelected -> RescueAuthCard {
                    RescueAuthSectionHeader(stringResource(R.string.legacy_import_file_selected),
                        state.fileName ?: stringResource(R.string.common_unknown))
                    Text(stringResource(R.string.common_working), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LegacyImportViewModel.State.Decrypting, LegacyImportViewModel.State.Applying -> RescueAuthCard {
                    RescueAuthSectionHeader(stringResource(R.string.common_working))
                }
                is LegacyImportViewModel.State.Success -> RescueAuthCard(
                    containerColor = if (state.blocked) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                ) {
                    RescueAuthSectionHeader(
                        title = stringResource(if (state.blocked) R.string.import_result_blocked_title else R.string.import_result_title),
                        subtitle = when {
                            state.blocked -> stringResource(R.string.import_result_blocked_body, state.conflicts, state.stateDivergences)
                            state.imported == 0 && state.duplicates > 0 -> stringResource(R.string.import_result_nothing_new)
                            else -> stringResource(R.string.import_result_summary, state.imported, state.duplicates, state.developerImported)
                        },
                    )
                }
                is LegacyImportViewModel.State.Error -> RescueAuthCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
                    RescueAuthSectionHeader(stringResource(R.string.legacy_import_error_title), state.message)
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun LegacyPage(onBack: () -> Unit, modifier: Modifier, bottomBar: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit) {
    RescueAuthFormPage(
        title = stringResource(R.string.legacy_import_title),
        subtitle = stringResource(R.string.settings_backup_transfer_section),
        onBack = onBack,
        modifier = modifier.testTag(LegacyImportTestTags.SCREEN),
        bottomBar = bottomBar,
        content = content,
    )
}

@Composable
private fun LegacyFooterAction(label: String, onClick: () -> Unit, tag: String = "legacy_result_close") {
    RescueAuthBottomBar {
        RescueAuthButton(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag(tag)) { Text(label) }
    }
}

/** Local-only password state; a rejected array is cleared, an accepted array belongs to the VM. */
@Composable
private fun LegacyPasswordEntry(onSubmit: (CharArray) -> Boolean, onCancel: () -> Unit,
    onBack: () -> Unit, modifier: Modifier) {
    var password by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }
    LegacyPage(onBack, modifier, bottomBar = {
        RescueAuthActionBar(
            secondaryLabel = stringResource(R.string.common_cancel), secondaryIcon = Icons.Filled.Close,
            onSecondaryClick = onCancel, secondaryTestTag = LegacyImportTestTags.PASSWORD_CANCEL,
            primaryLabel = stringResource(R.string.pin_submit), primaryIcon = Icons.AutoMirrored.Filled.ArrowForward,
            primaryEnabled = password.isNotEmpty(), primaryTestTag = LegacyImportTestTags.PASSWORD_SUBMIT,
            onPrimaryClick = {
                val array = password.toCharArray()
                if (!onSubmit(array)) array.fill('\u0000')
            },
        )
    }) {
        RescueAuthCard {
            RescueAuthIconBadge(Icons.Filled.Lock)
            RescueAuthSectionHeader(stringResource(R.string.legacy_password_title), stringResource(R.string.legacy_password_policy))
            RescueAuthTextField(
                value = password, onValueChange = { password = it },
                label = { Text(stringResource(R.string.legacy_password_label)) },
                visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                singleLine = true,
                trailingIcon = {
                    RescueAuthVisibilityToggle(revealed, { revealed = !revealed },
                        Modifier.testTag("legacy_password_visibility"))
                },
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.md).testTag(LegacyImportTestTags.PASSWORD_FIELD),
            )
        }
    }
}

@Composable
private fun LegacyPreviewContent(preview: LegacyImportPreview, onConfirm: () -> Unit, onCancel: () -> Unit,
    onBack: () -> Unit, modifier: Modifier) {
    LegacyPage(onBack, modifier, bottomBar = {
        RescueAuthActionBar(
            secondaryLabel = stringResource(R.string.common_cancel), secondaryIcon = Icons.Filled.Close,
            onSecondaryClick = onCancel, secondaryTestTag = LegacyImportTestTags.CANCEL,
            primaryLabel = stringResource(R.string.preview_confirm_import), primaryIcon = Icons.Filled.Check,
            onPrimaryClick = onConfirm, primaryEnabled = !preview.blocked, primaryTestTag = LegacyImportTestTags.CONFIRM,
        )
    }) {
        RescueAuthSummaryCard(stringResource(R.string.legacy_preview_title), listOf(
            stringResource(R.string.legacy_preview_schema) to preview.schemaVersion.toString(),
        ), icon = Icons.Filled.History)
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
            RescueAuthSectionHeader(stringResource(R.string.legacy_preview_blocked_title), stringResource(R.string.legacy_preview_blocked_body))
            Text(stringResource(R.string.legacy_preview_blocked_hint), Modifier.padding(top = Spacing.sm),
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
