package com.rescueauth.v2.ui.screens.authenticator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import com.rescueauth.v2.ui.components.RescueAuthButton as Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import com.rescueauth.v2.ui.components.RescueAuthTextField
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
import com.rescueauth.v2.ui.components.RescueAuthActionBar
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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState,
        modifier = modifier.testTag("add_totp_sheet")) {
        Column(Modifier.fillMaxWidth().imePadding()) {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()).testTag("add_totp_fields")
                    .padding(horizontal = Spacing.lg).padding(bottom = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(CardTokens.formFieldSpacing),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.add_totp_title),
                            style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold)
                        if (mode == null) Text(stringResource(R.string.add_totp_sheet_subtitle),
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (mode != null) TextButton(onClick = { selectedMode = null },
                        modifier = Modifier.testTag("totp_change_method")) {
                        Text(stringResource(R.string.add_totp_change_method))
                    }
                }
                if (mode == null) {
                    MethodChoiceRow(Icons.Filled.CameraAlt, stringResource(R.string.add_totp_mode_scan),
                        stringResource(R.string.add_totp_scan_hint), {
                            selectedMode = AddMode.SCAN; onModeChange(AddMode.SCAN)
                        }, "totp_method_SCAN")
                    MethodChoiceRow(Icons.Filled.ContentPaste, stringResource(R.string.add_totp_mode_paste),
                        stringResource(R.string.add_totp_paste_subtitle), {
                            selectedMode = AddMode.PASTE; onModeChange(AddMode.PASTE)
                        }, "totp_method_PASTE")
                    MethodChoiceRow(Icons.Filled.Add, stringResource(R.string.add_totp_mode_manual),
                        stringResource(R.string.add_totp_manual_subtitle), {
                            selectedMode = AddMode.MANUAL; onModeChange(AddMode.MANUAL)
                        }, "totp_method_MANUAL")
                } else {
                    RescueAuthCard(containerColor = CardTokens.elevatedContainerColor()) {
                        Column(verticalArrangement = Arrangement.spacedBy(CardTokens.formFieldSpacing)) {
                            when (mode) {
                                AddMode.SCAN -> {
                                    Text(stringResource(R.string.add_totp_scan_hint),
                                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
                                    Button(onClick = onStartScan, modifier = Modifier.fillMaxWidth()) {
                                        Icon(Icons.Filled.CameraAlt, null)
                                        Spacer(Modifier.width(Spacing.xs))
                                        Text(stringResource(R.string.add_totp_scan_action))
                                    }
                                }
                                AddMode.PASTE -> RescueAuthTextField(
                                    value = form.uri, onValueChange = onUriChange,
                                    label = { Text(stringResource(R.string.add_totp_uri_label)) },
                                    placeholder = { Text(stringResource(R.string.add_totp_uri_hint)) },
                                    modifier = Modifier.fillMaxWidth(), minLines = 2)
                                AddMode.MANUAL -> {
                                    RescueAuthTextField(form.provider, onProviderChange,
                                        label = { Text(stringResource(R.string.add_totp_provider_label)) },
                                        singleLine = true, modifier = Modifier.fillMaxWidth().testTag("add_totp_provider"))
                                    RescueAuthTextField(form.accountName, onAccountNameChange,
                                        label = { Text(stringResource(R.string.add_totp_account_label)) },
                                        singleLine = true, modifier = Modifier.fillMaxWidth().testTag("add_totp_account"))
                                    RescueAuthTextField(form.secret, onSecretChange,
                                        label = { Text(stringResource(R.string.add_totp_secret_label)) },
                                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                                        modifier = Modifier.fillMaxWidth().testTag("add_totp_secret"))
                                    TextButton(onClick = { showAdvanced = !showAdvanced }) {
                                        Text(stringResource(R.string.add_totp_advanced))
                                        Spacer(Modifier.width(Spacing.xxs))
                                        Icon(if (showAdvanced) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
                                    }
                                    if (showAdvanced) {
                                        AlgorithmSelector(form.algorithm, onAlgorithmChange)
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                            DigitSelector(form.digits, onDigitsChange, Modifier.weight(1f))
                                            PeriodSelector(form.periodSeconds, onPeriodChange, Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (mode != null) form.error?.let { error ->
                    Text(error, style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                }
            }
            if (mode != null && mode != AddMode.SCAN) RescueAuthActionBar(
                secondaryLabel = stringResource(R.string.common_cancel), secondaryIcon = Icons.Filled.Close,
                onSecondaryClick = onDismiss,
                primaryLabel = stringResource(R.string.add_totp_confirm), primaryIcon = Icons.Filled.Add,
                onPrimaryClick = onSubmit, primaryEnabled = !form.submitting,
                primaryTestTag = "add_totp_submit", secondaryTestTag = "add_totp_cancel",
                modifier = Modifier.testTag("add_totp_actions"))
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
