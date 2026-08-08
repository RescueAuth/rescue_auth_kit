package com.rescueauth.v2.ui.screens.authenticator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rescueauth.v2.R
import com.rescueauth.v2.export.TotpParameters
import com.rescueauth.v2.ui.authenticator.AddMode
import com.rescueauth.v2.ui.authenticator.AddTotpFormState
import com.rescueauth.v2.ui.theme.Spacing

/**
 * "Add TOTP" bottom sheet (Phase 4 P1).
 *
 * Two entry modes: Paste `otpauth://` URI and Manual TOTP Entry. Validation
 * errors are surfaced through [formState.error]; submitting disables the
 * confirm button. The sheet is presentation-only — state flows through
 * callbacks so it can be unit-tested without a repository.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddTotpSheet(
    form: AddTotpFormState,
    onDismiss: () -> Unit,
    onModeChange: (AddMode) -> Unit,
    onUriChange: (String) -> Unit,
    onProviderChange: (String) -> Unit,
    onAccountNameChange: (String) -> Unit,
    onSecretChange: (String) -> Unit,
    onAlgorithmChange: (String) -> Unit,
    onDigitsChange: (Int) -> Unit,
    onPeriodChange: (Int) -> Unit,
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
                text = stringResource(R.string.add_totp_title),
                style = MaterialTheme.typography.titleLarge,
            )

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = form.mode == AddMode.PASTE,
                    onClick = { onModeChange(AddMode.PASTE) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) {
                    Text(stringResource(R.string.add_totp_mode_paste))
                }
                SegmentedButton(
                    selected = form.mode == AddMode.MANUAL,
                    onClick = { onModeChange(AddMode.MANUAL) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) {
                    Text(stringResource(R.string.add_totp_mode_manual))
                }
            }

            if (form.mode == AddMode.PASTE) {
                OutlinedTextField(
                    value = form.uri,
                    onValueChange = onUriChange,
                    label = { Text(stringResource(R.string.add_totp_uri_label)) },
                    placeholder = { Text("otpauth://totp/…") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    minLines = 2,
                )
            } else {
                OutlinedTextField(
                    value = form.provider,
                    onValueChange = onProviderChange,
                    label = { Text(stringResource(R.string.add_totp_provider_label)) },
                    placeholder = { Text("GitHub") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = form.accountName,
                    onValueChange = onAccountNameChange,
                    label = { Text(stringResource(R.string.add_totp_account_label)) },
                    placeholder = { Text("alice@example.com") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = form.secret,
                    onValueChange = onSecretChange,
                    label = { Text(stringResource(R.string.add_totp_secret_label)) },
                    placeholder = { Text("JBSWY3DPEHPK3PXP") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    AlgorithmSelector(
                        selected = form.algorithm,
                        onSelect = onAlgorithmChange,
                        modifier = Modifier.weight(1f),
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
                    ) {
                        DigitSelector(selected = form.digits, onSelect = onDigitsChange)
                        PeriodSelector(selected = form.periodSeconds, onSelect = onPeriodChange)
                    }
                }
            }

            if (form.error != null) {
                Text(
                    text = form.error,
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
                    Text(stringResource(R.string.add_totp_confirm))
                }
            }
        }
    }
}

@Composable
private fun AlgorithmSelector(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        Text(
            text = stringResource(R.string.add_totp_algorithm_label),
            style = MaterialTheme.typography.labelMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            listOf("SHA1", "SHA256", "SHA512").forEach { algo ->
                FilterChip(
                    selected = selected == algo,
                    onClick = { onSelect(algo) },
                    label = { Text(algo) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DigitSelector(
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        Text(
            text = stringResource(R.string.add_totp_digits_label),
            style = MaterialTheme.typography.labelMedium,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            (TotpParameters.MIN_DIGITS..TotpParameters.MAX_DIGITS).forEach { d ->
                FilterChip(
                    selected = selected == d,
                    onClick = { onSelect(d) },
                    label = { Text("$d") },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PeriodSelector(
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        Text(
            text = stringResource(R.string.add_totp_period_label),
            style = MaterialTheme.typography.labelMedium,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            // Preset steps covering the full frozen contract range (1..120).
            listOf(1, 30, 60, 120).forEach { p ->
                FilterChip(
                    selected = selected == p,
                    onClick = { onSelect(p) },
                    label = { Text("${p}s") },
                )
            }
        }
    }
}
