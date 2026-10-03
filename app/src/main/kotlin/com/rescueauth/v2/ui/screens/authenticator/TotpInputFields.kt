package com.rescueauth.v2.ui.screens.authenticator

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.rescueauth.v2.R
import com.rescueauth.v2.export.TotpParameters
import com.rescueauth.v2.ui.authenticator.*
import com.rescueauth.v2.ui.components.RescueAuthTextField
import com.rescueauth.v2.ui.components.RescueAuthVisibilityToggle
import com.rescueauth.v2.ui.theme.CardTokens
import com.rescueauth.v2.ui.theme.Spacing

/** Shared credential fields. Method changes replace fields in place; scan opens the camera directly. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TotpInputFields(form: AddTotpFormState, onModeChange: (AddMode) -> Unit, onStartScan: () -> Unit,
    onUriChange: (String) -> Unit, onSecretChange: (String) -> Unit, onAlgorithmChange: (String) -> Unit,
    onDigitsChange: (Int) -> Unit, onPeriodChange: (Int) -> Unit, enabled: Boolean = true,
    tagPrefix: String = "account_add", methodTagPrefix: String = "account_add_method",
    visibilityTag: String = "account_add_secret_visibility", identityFields: (@Composable () -> Unit)? = null,
    onAdvanced: () -> Unit = {}) {
    val mode = if (form.mode == AddMode.SCAN) AddMode.MANUAL else form.mode
    var revealed by remember(mode) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(CardTokens.formFieldSpacing)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            listOf(AddMode.MANUAL, AddMode.PASTE, AddMode.SCAN).forEach { option ->
                FilterChip(selected = option == mode, enabled = enabled,
                    onClick = { if (option == AddMode.SCAN) onStartScan() else onModeChange(option) },
                    modifier = Modifier.testTag("${methodTagPrefix}_${option.name}"),
                    label = { Text(stringResource(when (option) {
                        AddMode.MANUAL -> R.string.add_totp_mode_manual
                        AddMode.PASTE -> R.string.add_totp_mode_paste
                        AddMode.SCAN -> R.string.add_totp_mode_scan
                    })) })
            }
        }
        if (mode == AddMode.PASTE) RescueAuthTextField(form.uri, onUriChange, enabled = enabled,
            minLines = 2, label = { Text(stringResource(R.string.add_totp_uri_label)) },
            modifier = Modifier.fillMaxWidth().testTag("${tagPrefix}_uri"))
        else {
            identityFields?.invoke()
            RescueAuthTextField(form.secret, onSecretChange, enabled = enabled, singleLine = true,
                label = { Text(stringResource(R.string.add_totp_secret_label)) },
                visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { RescueAuthVisibilityToggle(revealed, { revealed = !revealed }, Modifier.testTag(visibilityTag)) },
                modifier = Modifier.fillMaxWidth().testTag("${tagPrefix}_secret"))
            TextButton(onClick = onAdvanced, enabled = enabled, modifier = Modifier.testTag("${tagPrefix}_advanced")) {
                Text(stringResource(R.string.add_totp_advanced))
                Icon(Icons.Filled.ExpandMore, null)
            }
        }
    }
}

@Composable
internal fun TotpAdvancedFields(form: AddTotpFormState, onAlgorithmChange: (String) -> Unit,
    onDigitsChange: (Int) -> Unit, onPeriodChange: (Int) -> Unit, enabled: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(CardTokens.formFieldSpacing)) {
        AlgorithmSelector(form.algorithm, onAlgorithmChange, enabled = enabled)
        DigitSelector(form.digits, onDigitsChange, enabled = enabled)
        PeriodSelector(form.periodSeconds, onPeriodChange, enabled = enabled)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AlgorithmSelector(selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    ParameterChoices(stringResource(R.string.add_totp_algorithm_label), listOf("SHA1", "SHA256", "SHA512"), selected, onSelect, modifier, enabled)
}
@Composable
internal fun DigitSelector(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    ParameterChoices(stringResource(R.string.add_totp_digits_label), TotpParameters.SUPPORTED_DIGITS.map { it.toString() },
        selected.toString(), { onSelect(it.toInt()) }, modifier, enabled)
}
@Composable
internal fun PeriodSelector(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    ParameterChoices(stringResource(R.string.add_totp_period_label), listOf(1,30,60,120).map { "${it}s" },
        "${selected}s", { onSelect(it.removeSuffix("s").toInt()) }, modifier, enabled)
}
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParameterChoices(label: String, values: List<String>, selected: String, onSelect: (String) -> Unit,
    modifier: Modifier, enabled: Boolean) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            values.forEach { value -> FilterChip(selected = selected == value, enabled = enabled,
                onClick = { onSelect(value) }, label = { Text(value) }) }
        }
    }
}
