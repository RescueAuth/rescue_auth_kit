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
import androidx.compose.material.icons.filled.ContentPaste
import com.rescueauth.v2.ui.components.RescueAuthButton as Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
    // The first surface is a method chooser. It keeps the sheet quiet and
    // prevents a long form from appearing before the user has chosen a path.
    var selectedMode by remember {
        // PASTE is the ViewModel's storage default; it is not a user choice.
        // Start with the quiet method directory for the normal add flow while
        // preserving explicit MANUAL state for previews and restored forms.
        mutableStateOf<AddMode?>(form.mode.takeIf { it == AddMode.MANUAL })
    }
    val mode = selectedMode
    var showAdvanced by remember { mutableStateOf(false) }
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
            if (mode == null) {
                MethodChoiceRow(
                    icon = Icons.Filled.CameraAlt,
                    title = stringResource(R.string.add_totp_mode_scan),
                    subtitle = stringResource(R.string.add_totp_scan_hint),
                    testTag = "totp_method_SCAN",
                    onClick = {
                        selectedMode = AddMode.SCAN
                        onModeChange(AddMode.SCAN)
                    },
                )
                MethodChoiceRow(
                    icon = Icons.Filled.ContentPaste,
                    title = stringResource(R.string.add_totp_mode_paste),
                    subtitle = stringResource(R.string.add_totp_paste_subtitle),
                    testTag = "totp_method_PASTE",
                    onClick = {
                        selectedMode = AddMode.PASTE
                        onModeChange(AddMode.PASTE)
                    },
                )
                MethodChoiceRow(
                    icon = Icons.Filled.Add,
                    title = stringResource(R.string.add_totp_mode_manual),
                    subtitle = stringResource(R.string.add_totp_manual_subtitle),
                    testTag = "totp_method_MANUAL",
                    onClick = {
                        selectedMode = AddMode.MANUAL
                        onModeChange(AddMode.MANUAL)
                    },
                )
            } else {
                TextButton(
                    onClick = { selectedMode = null },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.add_totp_change_method))
                }
                // Keep the primary scan action in the first visible portion of
                // the sheet; the explanatory copy can scroll below it on small phones.
                if (mode == AddMode.SCAN) {
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
                    when (mode) {
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
                            visualTransformation = PasswordVisualTransformation(),
                        )
                        TextButton(onClick = { showAdvanced = !showAdvanced }) {
                            Text(stringResource(R.string.add_totp_advanced))
                        }
                        if (showAdvanced) {
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
            }
            }
            if (mode != null) form.error?.let { error ->
                Text(
                    text = error,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                )
            }
            if (mode != null && mode != AddMode.SCAN) Row(
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

@Composable
private fun MethodChoiceRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    testTag: String,
) {
    com.rescueauth.v2.ui.components.RescueAuthRowCard(
        onClick = onClick,
        containerColor = CardTokens.containerColor(),
        modifier = Modifier.testTag(testTag),
    ) {
        RescueAuthIconBadge(icon = icon, size = 38.dp, iconSize = 20.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        com.rescueauth.v2.ui.components.RescueAuthChevron()
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
