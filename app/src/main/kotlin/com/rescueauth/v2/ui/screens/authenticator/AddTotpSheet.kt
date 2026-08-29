package com.rescueauth.v2.ui.screens.authenticator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rescueauth.v2.R
import com.rescueauth.v2.export.TotpParameters
import com.rescueauth.v2.ui.authenticator.AddMode
import com.rescueauth.v2.ui.authenticator.AddTotpFormState
import com.rescueauth.v2.ui.components.RescueAuthCard
import com.rescueauth.v2.ui.components.RescueAuthIconBadge
import com.rescueauth.v2.ui.components.RescueAuthSectionHeader
import com.rescueauth.v2.ui.theme.CardTokens
import com.rescueauth.v2.ui.theme.Spacing

/** Modern, compact Add TOTP flow. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddTotpSheet(
    form: AddTotpFormState,
    onDismiss: () -> Unit,
    onModeChange: (AddMode) -> Unit,
    onStartScan: () -> Unit = {},
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                RescueAuthIconBadge(icon = Icons.Filled.Add, size = 40.dp, iconSize = 20.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.add_totp_title),
                        style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.add_totp_sheet_subtitle),
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf(
                    AddMode.SCAN to R.string.add_totp_mode_scan,
                    AddMode.PASTE to R.string.add_totp_mode_paste,
                    AddMode.MANUAL to R.string.add_totp_mode_manual,
                ).forEachIndexed { index, (mode, labelRes) ->
                    SegmentedButton(
                        selected = form.mode == mode,
                        onClick = { onModeChange(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 3),
                    ) {
                        Text(stringResource(labelRes))
                    }
                }
            }

            // Keep the primary scan action in the first visible portion of the
            // sheet; the explanatory copy can scroll below it on small phones.
            if (form.mode == AddMode.SCAN) {
                Button(
                    onClick = onStartScan,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.CameraAlt, contentDescription = null)
                    Spacer(modifier = Modifier.width(Spacing.xxs))
                    Text(stringResource(R.string.add_totp_scan_action))
                }
            }

            RescueAuthCard(
                containerColor = CardTokens.elevatedContainerColor(),
                contentPadding = Spacing.md,
            ) {
                when (form.mode) {
                    AddMode.SCAN -> {
                        Text(
                            text = stringResource(R.string.add_totp_scan_hint),
                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        )
                    }
                    AddMode.PASTE -> {
                        RescueAuthSectionHeader(
                            title = stringResource(R.string.add_totp_uri_label),
                            subtitle = stringResource(R.string.add_totp_paste_subtitle),
                        )
                        OutlinedTextField(
                            value = form.uri,
                            onValueChange = onUriChange,
                            label = { Text(stringResource(R.string.add_totp_uri_label)) },
                            placeholder = { Text(stringResource(R.string.add_totp_uri_hint)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = Spacing.sm),
                            minLines = 2,
                        )
                    }
                    AddMode.MANUAL -> {
                        RescueAuthSectionHeader(
                            title = stringResource(R.string.add_totp_mode_manual),
                            subtitle = stringResource(R.string.add_totp_manual_subtitle),
                        )
                        OutlinedTextField(
                            value = form.provider,
                            onValueChange = onProviderChange,
                            label = { Text(stringResource(R.string.add_totp_provider_label)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = Spacing.sm),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = form.accountName,
                            onValueChange = onAccountNameChange,
                            label = { Text(stringResource(R.string.add_totp_account_label)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = form.secret,
                            onValueChange = onSecretChange,
                            label = { Text(stringResource(R.string.add_totp_secret_label)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        AlgorithmSelector(form.algorithm, onAlgorithmChange)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        ) {
                            DigitSelector(form.digits, onDigitsChange, Modifier.weight(1f))
                            PeriodSelector(form.periodSeconds, onPeriodChange, Modifier.weight(1f))
                        }
                    }
                }
            }
            form.error?.let { error ->
                Text(
                    text = error,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.common_cancel))
                }
                Button(
                    onClick = onSubmit,
                    enabled = !form.submitting,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.add_totp_confirm))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlgorithmSelector(selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        Text(stringResource(R.string.add_totp_algorithm_label), style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            listOf("SHA1", "SHA256", "SHA512").forEach { algo ->
                FilterChip(selected = selected == algo, onClick = { onSelect(algo) }, label = { Text(algo) })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DigitSelector(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        Text(stringResource(R.string.add_totp_digits_label), style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            (TotpParameters.MIN_DIGITS..TotpParameters.MAX_DIGITS).forEach { digit ->
                FilterChip(selected = selected == digit, onClick = { onSelect(digit) }, label = { Text(digit.toString()) })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PeriodSelector(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        Text(stringResource(R.string.add_totp_period_label), style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            listOf(1, 30, 60, 120).forEach { period ->
                FilterChip(selected = selected == period, onClick = { onSelect(period) }, label = { Text("${period}s") })
            }
        }
    }
}
