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
import com.rescueauth.v2.ui.authenticator.RecoveryFormState
import com.rescueauth.v2.ui.components.*
import com.rescueauth.v2.ui.theme.CardTokens
import com.rescueauth.v2.ui.theme.Spacing

/** Recovery add/edit retains its controller and shares the account editor's fields and footer. */
@Composable
fun RecoveryCodeEditorSheet(form: RecoveryFormState, onDismiss: () -> Unit, onTitleChange: (String) -> Unit,
    onValuesChange: (String) -> Unit, onSubmit: () -> Unit, modifier: Modifier = Modifier) {
    var namePage by remember { mutableStateOf(false) }
    val back = { namePage = false }
    RescueAuthSheet(stringResource(if (namePage) R.string.recovery_codes_custom_name else if (form.isEditing) R.string.recovery_codes_edit_title else R.string.recovery_codes_add_title),
        Icons.Filled.Shield, onDismiss, modifier, headerModifier = Modifier.testTag("recovery_editor_header"),
        dismissEnabled = !form.submitting, onBack = if (namePage) back else null,
        footer = { dismiss -> if (namePage) RescueAuthSheetPageActions(back) else RescueAuthActionBar(stringResource(R.string.common_cancel), Icons.Filled.Close, dismiss,
            stringResource(R.string.recovery_codes_save), Icons.Filled.Check, onSubmit,
            secondaryEnabled = !form.submitting, primaryEnabled = !form.submitting,
            secondaryTestTag = "recovery_editor_cancel", primaryTestTag = "recovery_editor_save") }) {
        RescueAuthCard {
            if (namePage) RecoveryNameField(form, onTitleChange, !form.submitting)
            else RecoveryCodeFields(form, onTitleChange, onValuesChange, enabled = !form.submitting, onEditName = { namePage = true })
        }
        form.error?.let { code -> Text(stringResource(when {
            code == "empty_values" -> R.string.recovery_codes_empty_error
            code.startsWith("duplicate:") -> R.string.account_add_duplicate_codes_error
            else -> R.string.common_error_title
        }), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

/** Primary recovery fields shared by add and edit; optional naming is a secondary page. */
@Composable
internal fun RecoveryCodeFields(form: RecoveryFormState, onTitleChange: (String) -> Unit,
    onValuesChange: (String) -> Unit, enabled: Boolean = true, onEditName: () -> Unit = {}) {
    val count = form.parsedValues().size
    Column(verticalArrangement = Arrangement.spacedBy(CardTokens.formFieldSpacing)) {
        RescueAuthTextField(form.valuesText, onValuesChange,
            modifier = Modifier.fillMaxWidth().testTag("recovery_values"), enabled = enabled,
            label = { Text(stringResource(R.string.recovery_codes_values_label)) }, minLines = 4,
            supportingText = { Text(stringResource(if (count == 0) R.string.recovery_codes_values_hint else R.string.recovery_codes_preview, count)) })
        TextButton(onClick = onEditName, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("recovery_custom_name")) {
            Text(stringResource(R.string.recovery_codes_custom_name))
            Spacer(Modifier.weight(1f))
            Text(form.title.ifBlank { stringResource(if (form.isEditing) R.string.recovery_codes_keep_name else R.string.recovery_codes_auto_name) },
                style = MaterialTheme.typography.bodySmall)
            Icon(Icons.Filled.ChevronRight, null)
        }
    }
}

@Composable
internal fun RecoveryNameField(form: RecoveryFormState, onTitleChange: (String) -> Unit, enabled: Boolean = true) {
    RescueAuthTextField(form.title, onTitleChange, modifier = Modifier.fillMaxWidth().testTag("recovery_name"),
        enabled = enabled, singleLine = true, label = { Text(stringResource(R.string.recovery_codes_optional_name)) },
        supportingText = { Text(stringResource(if (form.isEditing) R.string.recovery_codes_keep_name else R.string.recovery_codes_auto_name)) })
}
