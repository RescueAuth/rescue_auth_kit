package com.rescueauth.v2.ui.screens.authenticator

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.authenticator.*
import com.rescueauth.v2.ui.components.*
import com.rescueauth.v2.ui.theme.CardTokens
import com.rescueauth.v2.ui.theme.Spacing

/** Adapter for standalone callers; uses the same sheet, fields and action bar as the account editor. */
@Composable
fun AddTotpSheet(form: AddTotpFormState, onDismiss: () -> Unit, onModeChange: (AddMode) -> Unit,
    onStartScan: () -> Unit = {}, onUriChange: (String) -> Unit, onProviderChange: (String) -> Unit,
    onAccountNameChange: (String) -> Unit, onSecretChange: (String) -> Unit, onAlgorithmChange: (String) -> Unit,
    onDigitsChange: (Int) -> Unit, onPeriodChange: (Int) -> Unit, onSubmit: () -> Unit, modifier: Modifier = Modifier) {
    var advanced by remember { mutableStateOf(false) }
    val back = { advanced = false }
    var mode by remember { mutableStateOf(if (form.mode == AddMode.SCAN) AddMode.MANUAL else form.mode) }
    LaunchedEffect(form.mode) { if (form.mode != AddMode.SCAN) mode = form.mode }
    RescueAuthSheet(stringResource(if (advanced) R.string.add_totp_advanced else R.string.add_totp_title), Icons.Filled.Timer, onDismiss,
        modifier = modifier.testTag("add_totp_sheet"), bodyModifier = Modifier.testTag("add_totp_fields"),
        dismissEnabled = !form.submitting, onBack = if (advanced) back else null,
        footer = { dismiss -> if (advanced) RescueAuthSheetPageActions(back) else RescueAuthActionBar(stringResource(R.string.common_cancel), Icons.Filled.Close, dismiss,
            stringResource(R.string.add_totp_confirm), Icons.Filled.Add, onSubmit,
            secondaryEnabled = !form.submitting, primaryEnabled = !form.submitting,
            secondaryTestTag = "add_totp_cancel", primaryTestTag = "add_totp_submit", modifier = Modifier.testTag("add_totp_actions")) }) {
        RescueAuthCard(containerColor = CardTokens.containerColor()) {
            if (advanced) TotpAdvancedFields(form, onAlgorithmChange, onDigitsChange, onPeriodChange, !form.submitting)
            else TotpInputFields(form.copy(mode = mode), onModeChange = { mode = it; onModeChange(it) }, onStartScan = onStartScan,
                onUriChange = onUriChange, onSecretChange = onSecretChange, onAlgorithmChange = onAlgorithmChange,
                onDigitsChange = onDigitsChange, onPeriodChange = onPeriodChange, enabled = !form.submitting,
                onAdvanced = { advanced = true }, tagPrefix = "add_totp", methodTagPrefix = "totp_method", visibilityTag = "totp_secret_visibility",
                identityFields = {
                    RescueAuthTextField(form.provider, onProviderChange, enabled = !form.submitting, singleLine = true,
                        label = { Text(stringResource(R.string.add_totp_provider_label)) }, modifier = Modifier.fillMaxWidth().testTag("add_totp_provider"))
                    RescueAuthTextField(form.accountName, onAccountNameChange, enabled = !form.submitting, singleLine = true,
                        label = { Text(stringResource(R.string.add_totp_account_label)) }, modifier = Modifier.fillMaxWidth().testTag("add_totp_account"))
                })
        }
        form.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}
