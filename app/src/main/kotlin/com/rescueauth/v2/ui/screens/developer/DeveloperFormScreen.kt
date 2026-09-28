package com.rescueauth.v2.ui.screens.developer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import com.rescueauth.v2.ui.components.RescueAuthButton as Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import com.rescueauth.v2.ui.components.RescueAuthOutlinedButton as OutlinedButton
import com.rescueauth.v2.ui.components.RescueAuthTextField
import com.rescueauth.v2.ui.components.RescueAuthFormPage
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import com.rescueauth.v2.R
import com.rescueauth.v2.repository.DeveloperRepository
import com.rescueauth.v2.ui.components.RescueAuthCard
import com.rescueauth.v2.ui.components.RescueAuthActionBar
import com.rescueauth.v2.ui.components.RescueAuthSectionHeader
import com.rescueauth.v2.ui.developer.DeveloperFormState
import com.rescueauth.v2.ui.developer.DeveloperFormType
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import com.rescueauth.v2.ui.theme.CardTokens
import com.rescueauth.v2.ui.theme.Spacing

/**
 * Developer entry create/edit screen (Phase 4 P4/P6).
 *
 * Handles all five types with per-type fields. Secret fields are masked by
 * default; labels are non-secret. Form state is owned by the ViewModel
 * (JVM-testable), this screen only renders and forwards input.
 *
 * ## Keystore SAF import (Phase 4 P6 §3/§16)
 *
 * Selecting a keystore launches a SAF [ActivityResultContracts.OpenDocument]
 * picker (no MIME trust — [ActivityResultContracts.OpenDocument] with a broad
 * accept-any MIME accepts any document; the bytes are read into a bounded
 * buffer that the shared [DeveloperRepository.MAX_KEYSTORE_RAW_BYTES] contract
 * enforces).
 * The read uses the ContentResolver stream, never `OpenableColumns.SIZE` as a
 * security gate. Empty / cancel / read-error / oversized / choose-another are
 * all surfaced as a neutral UX message and never leak secret bytes.
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
    // Phase 4 P6 — Android Signing Key
    onProjectNameChange: (String) -> Unit,
    onPackageNameChange: (String) -> Unit,
    onStorePasswordChange: (String) -> Unit,
    onKeyAliasChange: (String) -> Unit,
    onKeyPasswordChange: (String) -> Unit,
    onKeystoreSelected: (String, ByteArray) -> Unit,
    onClearKeystore: () -> Unit,
    // Phase 4 P6 — Environment Variable Set
    onVariableNameChange: (Int, String) -> Unit,
    onVariableValueChange: (Int, String) -> Unit,
    onAddVariable: () -> Unit,
    onRemoveVariable: (Int) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigate: (String) -> Unit = {},
) {
    val context = LocalContext.current
    var keystoreError by rememberSaveable { mutableStateOf<String?>(null) }
    var credentialsStep by rememberSaveable { mutableStateOf(false) }
    val goBack = {
        if (!form.submitting) {
            if (credentialsStep) credentialsStep = false else onBack()
        }
    }
    BackHandler(enabled = credentialsStep || form.submitting) {
        if (!form.submitting) credentialsStep = false
    }
    val keystoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            readKeystore(context, uri, onKeystoreSelected) { msg ->
                keystoreError = msg
            }
        }
        // null => user cancelled the SAF picker; nothing changes.
    }

    RescueAuthFormPage(
        title = stringResource(if (form.isEditing) R.string.developer_edit_title else R.string.developer_add_title),
        subtitle = formTypeLabel(form.type),
        onBack = goBack,
        backEnabled = !form.submitting,
        modifier = modifier.testTag("developer_form_${form.type.name}"),
        bottomBar = {
            RescueAuthActionBar(
                secondaryLabel = stringResource(if (credentialsStep) R.string.developer_form_previous else R.string.common_cancel),
                secondaryIcon = Icons.AutoMirrored.Filled.ArrowBack,
                onSecondaryClick = goBack,
                secondaryEnabled = !form.submitting,
                secondaryTestTag = "developer_form_previous",
                primaryLabel = stringResource(if (credentialsStep) R.string.developer_save else R.string.developer_form_next),
                primaryIcon = if (credentialsStep) Icons.Filled.Check else Icons.AutoMirrored.Filled.ArrowForward,
                onPrimaryClick = { if (credentialsStep) onSubmit() else credentialsStep = true },
                primaryEnabled = !form.submitting && (credentialsStep || form.title.isNotBlank()),
                primaryTestTag = if (credentialsStep) "developer_form_save" else "developer_form_next",
            )
        },
    ) {
        if (!credentialsStep) RescueAuthCard(
            containerColor = CardTokens.elevatedContainerColor(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                RescueAuthSectionHeader(
                    title = stringResource(R.string.developer_form_details),
                    subtitle = stringResource(R.string.developer_form_step, 1, 2),
                )
                RescueAuthTextField(
                    value = form.title,
                    onValueChange = onTitleChange,
                    label = { Text(stringResource(R.string.developer_field_title)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.md),
                )
                RescueAuthTextField(
                    value = form.notes,
                    onValueChange = onNotesChange,
                    label = { Text(stringResource(R.string.developer_field_notes)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (credentialsStep) RescueAuthCard(containerColor = CardTokens.containerColor()) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                RescueAuthSectionHeader(
                    title = stringResource(R.string.developer_form_credentials),
                    subtitle = stringResource(R.string.developer_form_step, 2, 2),
                )
                when (form.type) {
            DeveloperFormType.API_CREDENTIAL -> {
                RescueAuthTextField(
                    value = form.serviceName,
                    onValueChange = onServiceNameChange,
                    label = { Text(stringResource(R.string.developer_field_service)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                RescueAuthTextField(
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
                RescueAuthTextField(
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
                RescueAuthTextField(
                    value = form.publicKey,
                    onValueChange = onPublicKeyChange,
                    label = { Text(stringResource(R.string.developer_field_public_key)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            DeveloperFormType.GENERIC_SECRET -> {
                form.fields.forEachIndexed { index, (label, value) ->
                    if (index > 0) HorizontalDivider(
                        modifier = Modifier.padding(vertical = Spacing.xs), color = CardTokens.outlineColor())
                    Column(Modifier.fillMaxWidth().testTag("generic_field_group_$index"),
                        verticalArrangement = Arrangement.spacedBy(CardTokens.formFieldSpacing)) {
                        RescueAuthTextField(
                            value = label, onValueChange = { onFieldLabelChange(index, it) },
                            label = { Text(stringResource(R.string.developer_field_label)) }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("generic_field_name_$index"),
                            trailingIcon = {
                                IconButton(onClick = { onRemoveField(index) },
                                    modifier = Modifier.testTag("generic_field_remove_$index")) {
                                    Icon(Icons.Outlined.Delete, stringResource(R.string.developer_remove_field))
                                }
                            },
                        )
                        SecretField(value = value, onValueChange = { onFieldValueChange(index, it) },
                            label = stringResource(R.string.developer_field_value),
                            modifier = Modifier.testTag("generic_field_value_$index"))
                    }
                }
                OutlinedButton(onClick = onAddField,
                    modifier = Modifier.fillMaxWidth().testTag("generic_field_add")) {
                    Icon(Icons.Filled.Add, null)
                    Spacer(Modifier.width(Spacing.xs))
                    Text(stringResource(R.string.developer_add_field))
                }
            }
            DeveloperFormType.ANDROID_SIGNING_KEY -> {
                RescueAuthTextField(
                    value = form.projectName,
                    onValueChange = onProjectNameChange,
                    label = { Text(stringResource(R.string.developer_field_project_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                RescueAuthTextField(
                    value = form.packageName,
                    onValueChange = onPackageNameChange,
                    label = { Text(stringResource(R.string.developer_field_package_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                SecretField(
                    value = form.storePassword,
                    onValueChange = onStorePasswordChange,
                    label = stringResource(R.string.developer_field_store_password),
                )
                RescueAuthTextField(
                    value = form.keyAlias,
                    onValueChange = onKeyAliasChange,
                    label = { Text(stringResource(R.string.developer_field_key_alias)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                SecretField(
                    value = form.keyPassword,
                    onValueChange = onKeyPasswordChange,
                    label = stringResource(R.string.developer_field_key_password),
                )

                // Keystore file selection (opaque binary asset).
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    OutlinedButton(
                        onClick = { keystoreError = null; keystoreLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.FileUpload,
                            contentDescription = null,
                        )
                        Spacer(modifier = Modifier.height(Spacing.xs))
                        Text(
                            stringResource(
                                if (form.keystoreBytes != null || form.keystoreFileName.isNotEmpty()) {
                                    R.string.developer_replace_keystore
                                } else {
                                    R.string.developer_import_keystore
                                },
                            ),
                        )
                    }
                    if (form.keystoreBytes != null) {
                        IconButton(onClick = { onClearKeystore() }) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.developer_clear_keystore),
                            )
                        }
                    }
                }
                if (form.keystoreBytes != null) {
                    Text(
                        text = form.keystoreFileName.ifEmpty { stringResource(R.string.developer_keystore_selected) },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (keystoreError != null) {
                    Text(
                        text = stringResource(R.string.developer_keystore_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                // UX hint only — the file is always saved as opaque bytes.
                Text(
                    text = stringResource(R.string.developer_keystore_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DeveloperFormType.ENVIRONMENT_VARIABLE_SET -> {
                RescueAuthTextField(
                    value = form.projectName,
                    onValueChange = onProjectNameChange,
                    label = { Text(stringResource(R.string.developer_field_project_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                HorizontalDivider(Modifier.padding(vertical = Spacing.xs), color = CardTokens.outlineColor())
                form.variables.forEachIndexed { index, (name, value) ->
                    if (index > 0) HorizontalDivider(
                        modifier = Modifier.padding(vertical = Spacing.xs), color = CardTokens.outlineColor())
                    Column(Modifier.fillMaxWidth().testTag("env_field_group_$index"),
                        verticalArrangement = Arrangement.spacedBy(CardTokens.formFieldSpacing)) {
                        RescueAuthTextField(
                            value = name, onValueChange = { onVariableNameChange(index, it) },
                            label = { Text(stringResource(R.string.developer_field_variable_name)) }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("env_field_name_$index"),
                            trailingIcon = {
                                IconButton(onClick = { onRemoveVariable(index) },
                                    modifier = Modifier.testTag("env_field_remove_$index")) {
                                    Icon(Icons.Outlined.Delete, stringResource(R.string.developer_remove_variable))
                                }
                            },
                        )
                        SecretField(value = value, onValueChange = { onVariableValueChange(index, it) },
                            label = stringResource(R.string.developer_field_variable_value),
                            modifier = Modifier.testTag("env_field_value_$index"))
                    }
                }
                OutlinedButton(onClick = onAddVariable,
                    modifier = Modifier.fillMaxWidth().testTag("env_field_add")) {
                    Icon(Icons.Filled.Add, null)
                    Spacer(Modifier.width(Spacing.xs))
                    Text(stringResource(R.string.developer_add_variable))
                }

                }
            }
        }
        }

        // End of the type-specific fields card.

        if (form.error != null) {
            RescueAuthCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
                Text(
                    text = formErrorText(form.error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }

        Spacer(modifier = Modifier.height(Spacing.xl))

    }
}

/**
 * Reads a keystore document via SAF into a bounded byte buffer.
 *
 * - The size contract is the shared logical/package per-asset cap derived into
 *   raw bytes ([DeveloperRepository.MAX_KEYSTORE_RAW_BYTES]); we never trust
 *   `OpenableColumns.SIZE` (Issue #1 §7) — we read incrementally and stop once
 *   the cap + 1 is exceeded.
 * - Empty / cancel / read-error / oversized all map to [onError] (never
 *   echo secret bytes).
 * - On success the raw bytes + display name are delivered to [onSelected].
 */
private fun readKeystore(
    context: Context,
    uri: Uri,
    onSelected: (String, ByteArray) -> Unit,
    onError: (String) -> Unit,
) {
    val resolver = context.contentResolver
    val fileName = queryDisplayName(resolver, uri) ?: "keystore"
    val stream = try {
        resolver.openInputStream(uri) ?: run { onError("read_failed"); return }
    } catch (e: Exception) {
        onError("read_failed")
        return
    }
    try {
        stream.use { input ->
            val buf = ByteArray(64 * 1024)
            val out = java.io.ByteArrayOutputStream()
            var total = 0L
            val max = DeveloperRepository.MAX_KEYSTORE_RAW_BYTES.toLong()
            while (true) {
                val n = try {
                    input.read(buf)
                } catch (e: Exception) {
                    onError("read_failed")
                    return
                }
                if (n == -1) break
                if (n == 0) continue
                total += n
                if (total > max) {
                    onError("file_too_large")
                    return
                }
                out.write(buf, 0, n)
            }
            val bytes = out.toByteArray()
            if (bytes.isEmpty()) {
                onError("file_empty")
                return
            }
            onSelected(fileName, bytes)
        }
    } finally {
        // Release the underlying stream reference (no persistent plaintext
        // temp copy is ever created — Issue #20 P6 §16).
    }
}

private fun queryDisplayName(
    resolver: android.content.ContentResolver,
    uri: Uri,
): String? {
    return try {
        resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else null
        }
    } catch (_: Exception) {
        null
    }
}

@Composable
private fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    multiLine: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // Key visibility by the field identity so a removed/reordered dynamic row
    // can never inherit the previous row's reveal state.
    var visible by remember(label) { mutableStateOf(false) }
    val transformation = if (visible) VisualTransformation.None else PasswordVisualTransformation()
    RescueAuthTextField(
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
        modifier = modifier.fillMaxWidth(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }, modifier = Modifier.testTag("secret_visibility")) {
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
private fun formTypeLabel(type: DeveloperFormType): String = when (type) {
    DeveloperFormType.API_CREDENTIAL -> stringResource(R.string.developer_type_api_credential)
    DeveloperFormType.SSH_KEY -> stringResource(R.string.developer_type_ssh_key)
    DeveloperFormType.GENERIC_SECRET -> stringResource(R.string.developer_type_generic)
    DeveloperFormType.ANDROID_SIGNING_KEY -> stringResource(R.string.developer_type_signing_key)
    DeveloperFormType.ENVIRONMENT_VARIABLE_SET -> stringResource(R.string.developer_type_env_var)
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
    "keystore_required" -> stringResource(R.string.developer_error_keystore)
    "store_password_required" -> stringResource(R.string.developer_error_store_password)
    "key_alias_required" -> stringResource(R.string.developer_error_key_alias)
    "key_password_required" -> stringResource(R.string.developer_error_key_password)
    "variable_required" -> stringResource(R.string.developer_error_variable)
    "variable_name_required" -> stringResource(R.string.developer_error_variable_name)
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
            onProjectNameChange = {},
            onPackageNameChange = {},
            onStorePasswordChange = {},
            onKeyAliasChange = {},
            onKeyPasswordChange = {},
            onKeystoreSelected = { _, _ -> },
            onClearKeystore = {},
            onVariableNameChange = { _, _ -> },
            onVariableValueChange = { _, _ -> },
            onAddVariable = {},
            onRemoveVariable = {},
            onSubmit = {},
        )
    }
}
