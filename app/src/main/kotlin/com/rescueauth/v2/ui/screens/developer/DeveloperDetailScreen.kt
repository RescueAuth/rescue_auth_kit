package com.rescueauth.v2.ui.screens.developer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import com.rescueauth.v2.ui.components.RescueAuthButton as Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.rescueauth.v2.ui.components.RescueAuthOutlinedButton as OutlinedButton
import com.rescueauth.v2.ui.components.RescueAuthPageScaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.components.RescueAuthBackButton
import com.rescueauth.v2.ui.components.DestructiveConfirmationDialog
import com.rescueauth.v2.ui.components.EmptyState
import com.rescueauth.v2.ui.components.LoadingState
import com.rescueauth.v2.ui.components.RescueAuthCard
import com.rescueauth.v2.ui.components.RescueAuthActionBar
import com.rescueauth.v2.ui.components.ProtectedFieldsCard
import com.rescueauth.v2.ui.components.RescueAuthIconBadge
import com.rescueauth.v2.ui.components.icon
import com.rescueauth.v2.ui.developer.DeveloperDetailUiState
import com.rescueauth.v2.ui.model.DeveloperDetailUi
import com.rescueauth.v2.ui.model.DeveloperEntryType
import com.rescueauth.v2.ui.theme.CardTokens
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import com.rescueauth.v2.ui.theme.ScreenTokens
import com.rescueauth.v2.ui.theme.Spacing

/** Modern Developer entry detail surface. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperDetailScreen(
    uiState: DeveloperDetailUiState,
    onBack: () -> Unit,
    onRevealApiKey: () -> Unit,
    onRevealApiSecret: () -> Unit,
    onCopyApiKey: () -> Unit,
    onCopyApiSecret: () -> Unit,
    onRevealPrivateKey: () -> Unit,
    onCopyPrivateKey: () -> Unit,
    onRevealPassphrase: () -> Unit,
    onCopyPassphrase: () -> Unit,
    onRevealGeneric: (String) -> Unit,
    onCopyGeneric: (String) -> Unit,
    getRevealedValue: (String) -> String?,
    isRevealed: (String) -> Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRevealStorePassword: () -> Unit,
    onCopyStorePassword: () -> Unit,
    onRevealKeyPassword: () -> Unit,
    onCopyKeyPassword: () -> Unit,
    onExportKeystore: () -> Unit,
    onCopyKeyProperties: () -> Unit,
    onRevealEnvVar: (String) -> Unit,
    onCopyEnvVar: (String) -> Unit,
    modifier: Modifier = Modifier,
    onNavigate: (String) -> Unit = {},
) {
    RescueAuthPageScaffold(
        modifier = modifier.fillMaxSize(),
        title = uiState.detail?.title
            ?: stringResource(R.string.developer_detail_title),
        subtitle = uiState.detail?.let { developerTypeLabel(it) },
        navigationIcon = { RescueAuthBackButton(onBack) },
        bottomBar = {
            if (!uiState.loading && uiState.detail != null) {
                RescueAuthActionBar(
                    secondaryLabel = stringResource(R.string.developer_delete),
                    secondaryIcon = Icons.Outlined.Delete,
                    onSecondaryClick = onDelete,
                    secondaryDestructive = true,
                    secondaryTestTag = "developer_detail_delete",
                    primaryLabel = stringResource(R.string.developer_edit),
                    primaryIcon = Icons.Outlined.Edit,
                    onPrimaryClick = onEdit,
                    primaryTestTag = "developer_detail_edit",
                )
            }
        },
    ) { padding ->
        when {
            uiState.loading -> LoadingState(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                label = stringResource(R.string.developer_loading),
            )
            uiState.detail == null -> EmptyState(
                title = stringResource(R.string.developer_entry_missing),
                body = "",
                modifier = Modifier.padding(padding),
            )
            else -> DeveloperDetailContent(
                detail = uiState.detail,
                uiState = uiState,
                getRevealedValue = getRevealedValue,
                isRevealed = isRevealed,
                onRevealApiKey = onRevealApiKey,
                onRevealApiSecret = onRevealApiSecret,
                onCopyApiKey = onCopyApiKey,
                onCopyApiSecret = onCopyApiSecret,
                onRevealPrivateKey = onRevealPrivateKey,
                onCopyPrivateKey = onCopyPrivateKey,
                onRevealPassphrase = onRevealPassphrase,
                onCopyPassphrase = onCopyPassphrase,
                onRevealGeneric = onRevealGeneric,
                onCopyGeneric = onCopyGeneric,
                onRevealStorePassword = onRevealStorePassword,
                onCopyStorePassword = onCopyStorePassword,
                onRevealKeyPassword = onRevealKeyPassword,
                onCopyKeyPassword = onCopyKeyPassword,
                onExportKeystore = onExportKeystore,
                onCopyKeyProperties = onCopyKeyProperties,
                onRevealEnvVar = onRevealEnvVar,
                onCopyEnvVar = onCopyEnvVar,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun DeveloperDetailContent(
    detail: DeveloperDetailUi,
    uiState: DeveloperDetailUiState,
    getRevealedValue: (String) -> String?,
    isRevealed: (String) -> Boolean,
    onRevealApiKey: () -> Unit,
    onRevealApiSecret: () -> Unit,
    onCopyApiKey: () -> Unit,
    onCopyApiSecret: () -> Unit,
    onRevealPrivateKey: () -> Unit,
    onCopyPrivateKey: () -> Unit,
    onRevealPassphrase: () -> Unit,
    onCopyPassphrase: () -> Unit,
    onRevealGeneric: (String) -> Unit,
    onCopyGeneric: (String) -> Unit,
    onRevealStorePassword: () -> Unit,
    onCopyStorePassword: () -> Unit,
    onRevealKeyPassword: () -> Unit,
    onCopyKeyPassword: () -> Unit,
    onExportKeystore: () -> Unit,
    onCopyKeyProperties: () -> Unit,
    onRevealEnvVar: (String) -> Unit,
    onCopyEnvVar: (String) -> Unit,
    modifier: Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = ScreenTokens.horizontalPadding,
            vertical = Spacing.md,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        if (detail !is DeveloperDetailUi.GenericSecret) item(key = "metadata") {
            RescueAuthCard(containerColor = CardTokens.elevatedContainerColor()) {
                when (detail) {
                    is DeveloperDetailUi.ApiCredential -> {
                        MetadataText(stringResource(R.string.developer_field_service), detail.serviceName)
                        MetadataText(stringResource(R.string.developer_field_account), detail.accountName)
                    }
                    is DeveloperDetailUi.SshKey -> {
                        MetadataText(stringResource(R.string.developer_field_key_name), detail.keyName)
                        if (detail.publicKeyPresent) {
                            Text(
                                text = stringResource(R.string.developer_public_key_present),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = Spacing.xs),
                            )
                        }
                    }
                    is DeveloperDetailUi.GenericSecret -> Unit
                    is DeveloperDetailUi.AndroidSigningKey -> {
                        MetadataText(stringResource(R.string.developer_field_project_name), detail.projectName)
                        MetadataText(stringResource(R.string.developer_field_package_name), detail.packageName)
                        MetadataText(stringResource(R.string.developer_field_keystore_file), detail.keystoreFileName)
                        MetadataText(stringResource(R.string.developer_field_key_alias), detail.keyAlias)
                    }
                    is DeveloperDetailUi.EnvironmentVariableSet -> {
                        MetadataText(stringResource(R.string.developer_field_project_name), detail.projectName)
                    }
                }
            }
        }
        item(key = "secrets") {
            ProtectedFieldsCard(entryId = detail.stableId) {
                when (detail) {
                    is DeveloperDetailUi.ApiCredential -> {
                        SensitiveActionRow(stringResource(R.string.developer_field_api_key), isRevealed("apiKey"), getRevealedValue("apiKey"), onRevealApiKey, onCopyApiKey)
                        SensitiveActionRow(stringResource(R.string.developer_field_api_secret), isRevealed("apiSecret"), getRevealedValue("apiSecret"), onRevealApiSecret, onCopyApiSecret)
                    }
                    is DeveloperDetailUi.SshKey -> {
                        SensitiveActionRow(stringResource(R.string.developer_field_private_key), isRevealed("privateKey"), getRevealedValue("privateKey"), onRevealPrivateKey, onCopyPrivateKey)
                        SensitiveActionRow(stringResource(R.string.developer_field_passphrase), isRevealed("passphrase"), getRevealedValue("passphrase"), onRevealPassphrase, onCopyPassphrase)
                    }
                    is DeveloperDetailUi.GenericSecret -> detail.fieldLabels.forEach { label ->
                        val key = "field:$label"
                        SensitiveActionRow(label, isRevealed(key), getRevealedValue(key), { onRevealGeneric(key) }, { onCopyGeneric(key) })
                    }
                    is DeveloperDetailUi.AndroidSigningKey -> {
                        SensitiveActionRow(stringResource(R.string.developer_field_store_password), isRevealed("storePassword"), getRevealedValue("storePassword"), onRevealStorePassword, onCopyStorePassword)
                        SensitiveActionRow(stringResource(R.string.developer_field_key_password), isRevealed("keyPassword"), getRevealedValue("keyPassword"), onRevealKeyPassword, onCopyKeyPassword)
                    }
                    is DeveloperDetailUi.EnvironmentVariableSet -> detail.variableNames.forEach { name ->
                        val key = "var:$name"
                        SensitiveActionRow(name, isRevealed(key), getRevealedValue(key), { onRevealEnvVar(key) }, { onCopyEnvVar(key) })
                    }
                }
            }
        }
        if (detail is DeveloperDetailUi.AndroidSigningKey) {
            item(key = "keystore-actions") {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Button(onClick = onExportKeystore, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.developer_export_keystore))
                    }
                    OutlinedButton(onClick = onCopyKeyProperties, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.developer_copy_key_properties))
                    }
                }
            }
        }
        if (uiState.authUnavailable) {
            item(key = "auth-unavailable") {
                Text(
                    text = stringResource(R.string.auth_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (uiState.authCancelled) {
            item(key = "auth-cancelled") {
                Text(
                    text = stringResource(R.string.auth_cancelled),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MetadataText(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = Spacing.xxs)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun SensitiveActionRow(
    label: String,
    revealed: Boolean,
    revealedValue: String?,
    onReveal: () -> Unit,
    onCopy: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = if (revealed && revealedValue != null) revealedValue else "••••••••",
                style = if (revealed && revealedValue != null) {
                    MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                } else MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        TextButton(onClick = onReveal) {
            Text(stringResource(if (revealed) R.string.developer_hide else R.string.developer_reveal))
        }
        IconButton(onClick = onCopy) {
            Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.developer_copy))
        }
    }
}

@Composable
private fun developerTypeLabel(detail: DeveloperDetailUi): String = when (detail) {
    is DeveloperDetailUi.ApiCredential -> stringResource(R.string.developer_type_api_credential)
    is DeveloperDetailUi.SshKey -> stringResource(R.string.developer_type_ssh_key)
    is DeveloperDetailUi.GenericSecret -> stringResource(R.string.developer_type_generic)
    is DeveloperDetailUi.AndroidSigningKey -> stringResource(R.string.developer_type_signing_key)
    is DeveloperDetailUi.EnvironmentVariableSet -> stringResource(R.string.developer_type_env_var)
}

private fun detailTypeIcon(detail: DeveloperDetailUi) = when (detail) {
    is DeveloperDetailUi.ApiCredential -> DeveloperEntryType.API_CREDENTIAL.icon()
    is DeveloperDetailUi.SshKey -> DeveloperEntryType.SSH_KEY.icon()
    is DeveloperDetailUi.GenericSecret -> DeveloperEntryType.GENERIC_SECRET.icon()
    is DeveloperDetailUi.AndroidSigningKey -> DeveloperEntryType.ANDROID_SIGNING_KEY.icon()
    is DeveloperDetailUi.EnvironmentVariableSet -> DeveloperEntryType.ENVIRONMENT_VARIABLE_SET.icon()
}

@Composable
fun DeveloperDeleteDialog(
    title: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    message: String = stringResource(R.string.developer_delete_confirm_message),
) {
    DestructiveConfirmationDialog(
        title = stringResource(R.string.developer_delete_confirm_title, title),
        message = message,
        confirmLabel = stringResource(R.string.developer_delete),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

@Preview(showBackground = true)
@Composable
private fun DeveloperDetailPreview() {
    RescueAuthTheme {
        DeveloperDetailScreen(
            uiState = DeveloperDetailUiState(
                loading = false,
                detail = DeveloperDetailUi.ApiCredential("1", "Stripe", "stripe", "alice"),
            ),
            onBack = {},
            onRevealApiKey = {}, onRevealApiSecret = {}, onCopyApiKey = {}, onCopyApiSecret = {},
            onRevealPrivateKey = {}, onCopyPrivateKey = {}, onRevealPassphrase = {}, onCopyPassphrase = {},
            onRevealGeneric = {}, onCopyGeneric = {}, getRevealedValue = { null }, isRevealed = { false },
            onEdit = {}, onDelete = {}, onRevealStorePassword = {}, onCopyStorePassword = {},
            onRevealKeyPassword = {}, onCopyKeyPassword = {}, onExportKeystore = {}, onCopyKeyProperties = {},
            onRevealEnvVar = {}, onCopyEnvVar = {},
        )
    }
}
