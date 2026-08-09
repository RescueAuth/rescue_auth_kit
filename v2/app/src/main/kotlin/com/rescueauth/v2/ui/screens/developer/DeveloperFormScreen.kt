package com.rescueauth.v2.ui.screens.developer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.text.KeyboardOptions
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.developer.DeveloperFormState
import com.rescueauth.v2.ui.developer.DeveloperFormType
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import com.rescueauth.v2.ui.theme.Spacing

/**
 * Developer entry create/edit screen (Phase 4 P4).
 *
 * Handles the three P4 types with per-type fields. Secret fields are masked
 * by default; labels are non-secret. Form state is owned by the ViewModel
 * (JVM-testable), this screen only renders and forwards input.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperFormScreen(
    form: DeveloperFormState,
    onBack: () -> Unit,
    onTitleChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onServiceNameChange: (String) -> Unit,
    onAccountNameChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onApiSecretChange: (String) -> Unit,
    onKeyNameChange: (String) -> Unit,
    onPublicKeyChange: (String) -> Unit,
    onPrivateKeyChange: (String) -> Unit,
    onPassphraseChange: (String) -> Unit,
    onFieldLabelChange: (Int, String) -> Unit,
    onFieldValueChange: (Int, String) -> Unit,
    onAddField: () -> Unit,
    onRemoveField: (Int) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (form.isEditing) R.string.developer_edit_title
                            else R.string.developer_add_title,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.a11y_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            OutlinedTextField(
                value = form.title,
                onValueChange = onTitleChange,
                label = { Text(stringResource(R.string.developer_field_title)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.notes,
                onValueChange = onNotesChange,
                label = { Text(stringResource(R.string.developer_field_notes)) },
                modifier = Modifier.fillMaxWidth(),
            )

            when (form.type) {
                DeveloperFormType.API_CREDENTIAL -> {
                    OutlinedTextField(
                        value = form.serviceName,
                        onValueChange = onServiceNameChange,
                        label = { Text(stringResource(R.string.developer_field_service)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = form.accountName,
                        onValueChange = onAccountNameChange,
                        label = { Text(stringResource(R.string.developer_field_account)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SecretField(
                        value = form.apiKey,
                        onValueChange = onApiKeyChange,
                        label = stringResource(R.string.developer_field_api_key),
                    )
                    SecretField(
                        value = form.apiSecret,
                        onValueChange = onApiSecretChange,
                        label = stringResource(R.string.developer_field_api_secret),
                    )
                }
                DeveloperFormType.SSH_KEY -> {
                    OutlinedTextField(
                        value = form.keyName,
                        onValueChange = onKeyNameChange,
                        label = { Text(stringResource(R.string.developer_field_key_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SecretField(
                        value = form.privateKey,
                        onValueChange = onPrivateKeyChange,
                        label = stringResource(R.string.developer_field_private_key),
                        multiLine = true,
                    )
                    SecretField(
                        value = form.passphrase,
                        onValueChange = onPassphraseChange,
                        label = stringResource(R.string.developer_field_passphrase),
                    )
                    OutlinedTextField(
                        value = form.publicKey,
                        onValueChange = onPublicKeyChange,
                        label = { Text(stringResource(R.string.developer_field_public_key)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                DeveloperFormType.GENERIC_SECRET -> {
                    form.fields.forEachIndexed { index, (label, value) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                        ) {
                            OutlinedTextField(
                                value = label,
                                onValueChange = { onFieldLabelChange(index, it) },
                                label = { Text(stringResource(R.string.developer_field_label)) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { onRemoveField(index) }) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.developer_remove_field),
                                )
                            }
                        }
                        SecretField(
                            value = value,
                            onValueChange = { onFieldValueChange(index, it) },
                            label = stringResource(R.string.developer_field_value),
                        )
                    }
                    Button(
                        onClick = onAddField,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = null,
                        )
                        Spacer(modifier = Modifier.height(Spacing.xs))
                        Text(stringResource(R.string.developer_add_field))
                    }
                }
            }

            if (form.error != null) {
                Text(
                    text = formErrorText(form.error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(modifier = Modifier.height(Spacing.sm))
            Button(
                onClick = onSubmit,
                enabled = !form.submitting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.developer_save))
            }
            Spacer(modifier = Modifier.height(Spacing.lg))
        }
    }
}

@Composable
private fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    multiLine: Boolean = false,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    val transformation = if (visible) VisualTransformation.None else PasswordVisualTransformation()
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        visualTransformation = transformation,
        keyboardOptions = if (!multiLine) {
            KeyboardOptions(keyboardType = KeyboardType.Password)
        } else {
            KeyboardOptions(keyboardType = KeyboardType.Text)
        },
        singleLine = !multiLine,
        minLines = if (multiLine) 3 else 1,
        modifier = Modifier.fillMaxWidth(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) {
                        Icons.Filled.VisibilityOff
                    } else {
                        Icons.Filled.Visibility
                    },
                    contentDescription = stringResource(
                        if (visible) R.string.developer_hide else R.string.developer_reveal,
                    ),
                )
            }
        },
    )
}

@Composable
private fun formErrorText(key: String): String = when (key) {
    "title_required" -> stringResource(R.string.developer_error_title)
    "service_required" -> stringResource(R.string.developer_error_service)
    "account_required" -> stringResource(R.string.developer_error_account)
    "api_key_required" -> stringResource(R.string.developer_error_api_key)
    "api_secret_required" -> stringResource(R.string.developer_error_api_secret)
    "private_key_required" -> stringResource(R.string.developer_error_private_key)
    "field_required" -> stringResource(R.string.developer_error_field)
    "field_label_required" -> stringResource(R.string.developer_error_field_label)
    "not_found" -> stringResource(R.string.developer_entry_missing)
    "unexpected" -> stringResource(R.string.common_error_title)
    else -> key
}

@Preview(showBackground = true)
@Composable
private fun DeveloperFormApiPreview() {
    RescueAuthTheme {
        DeveloperFormScreen(
            form = DeveloperFormState(type = DeveloperFormType.API_CREDENTIAL),
            onBack = {},
            onTitleChange = {},
            onNotesChange = {},
            onServiceNameChange = {},
            onAccountNameChange = {},
            onApiKeyChange = {},
            onApiSecretChange = {},
            onKeyNameChange = {},
            onPublicKeyChange = {},
            onPrivateKeyChange = {},
            onPassphraseChange = {},
            onFieldLabelChange = { _, _ -> },
            onFieldValueChange = { _, _ -> },
            onAddField = {},
            onRemoveField = {},
            onSubmit = {},
        )
    }
}
