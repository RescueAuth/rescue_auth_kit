package com.rescueauth.v2.ui.screens.authenticator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.authenticator.RecoveryFormState
import com.rescueauth.v2.ui.components.RescueAuthCard
import com.rescueauth.v2.ui.theme.CardTokens
import com.rescueauth.v2.ui.theme.Spacing

/**
 * Add / Edit Recovery Codes bottom sheet (Phase 4 P3).
 *
 * - Title field + multiline code input (one code per line).
 * - Live parsed count preview (never echoes the codes as a secret list).
 * - Duplicate / empty validation errors are surfaced through [formState.error]
 *   (error code string, mapped by the caller to a user-facing message).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoveryCodeEditorSheet(
    form: RecoveryFormState,
    onDismiss: () -> Unit,
    onTitleChange: (String) -> Unit,
    onValuesChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                text = stringResource(
                    if (form.isEditing) R.string.recovery_codes_edit_title
                    else R.string.recovery_codes_add_title,
                ),
                style = MaterialTheme.typography.titleLarge,
            )

            // Title + codes fields are grouped inside one card (project-wide
            // card UI constraint).
            RescueAuthCard(
                containerColor = CardTokens.containerColor(),
                contentPadding = Spacing.md,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedTextField(
                        value = form.title,
                        onValueChange = onTitleChange,
                        label = { Text(stringResource(R.string.recovery_codes_set_title_label)) },
                        placeholder = { Text("Backup codes") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )

                    OutlinedTextField(
                        value = form.valuesText,
                        onValueChange = onValuesChange,
                        label = { Text(stringResource(R.string.recovery_codes_values_label)) },
                        placeholder = { Text("ABCD-EFGH-1234\nIJKL-MNOP-5678") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        minLines = 6,
                    )

                    Text(
                        text = stringResource(R.string.recovery_codes_values_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    val parsed = form.parsedValues().size
                    if (parsed > 0) {
                        Text(
                            text = stringResource(R.string.recovery_codes_preview, parsed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            val errorText = form.error?.let { errorCode ->
                when {
                    errorCode == "title_required" -> stringResource(R.string.recovery_codes_title_error)
                    errorCode == "empty_values" -> stringResource(R.string.recovery_codes_empty_error)
                    errorCode.startsWith("duplicate:") ->
                        stringResource(R.string.recovery_codes_duplicate_error, errorCode.removePrefix("duplicate:"))
                    else -> stringResource(R.string.common_error_title)
                }
            }
            if (errorText != null) {
                Text(
                    text = errorText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_cancel))
                }
                Button(onClick = onSubmit, enabled = !form.submitting) {
                    Text(stringResource(R.string.recovery_codes_save))
                }
            }
        }
    }
}
