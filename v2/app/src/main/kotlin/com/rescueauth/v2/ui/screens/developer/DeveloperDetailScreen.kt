package com.rescueauth.v2.ui.screens.developer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import com.rescueauth.v2.R
import com.rescueauth.v2.security.SensitiveAction
import com.rescueauth.v2.ui.components.BreadcrumbItem
import com.rescueauth.v2.ui.components.BreadcrumbTopBar
import com.rescueauth.v2.ui.components.DestructiveConfirmationDialog
import com.rescueauth.v2.ui.components.EmptyState
import com.rescueauth.v2.ui.components.LoadingState
import com.rescueauth.v2.ui.developer.DeveloperDetailUiState
import com.rescueauth.v2.ui.model.DeveloperDetailUi
import com.rescueauth.v2.ui.navigation.RescueAuthRoutes
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import com.rescueauth.v2.ui.theme.Spacing

/**
 * Developer entry detail screen (Phase 4 P4).
 *
 * Renders the non-secret metadata plus per-type sensitive rows. Reveal / copy
 * callbacks are wired to the [SensitiveActionGate] by the route; this screen
 * never holds plaintext secrets unless [getRevealedValue] returns an
 * explicitly-authorized value.
 */
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
    // Phase 4 P6 — Android Signing Key
    onRevealStorePassword: () -> Unit,
    onCopyStorePassword: () -> Unit,
    onRevealKeyPassword: () -> Unit,
    onCopyKeyPassword: () -> Unit,
    onExportKeystore: () -> Unit,
    onCopyKeyProperties: () -> Unit,
    // Phase 4 P6 — Environment Variable Set
    onRevealEnvVar: (String) -> Unit,
    onCopyEnvVar: (String) -> Unit,
    modifier: Modifier = Modifier,
    onNavigate: (String) -> Unit = {},
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    val detail = uiState.detail
                    BreadcrumbTopBar(
                        items = listOf(
                            BreadcrumbItem(
                                label = stringResource(R.string.developer_title),
                                destination = RescueAuthRoutes.DEVELOPER,
                            ),
                            if (detail != null) {
                                BreadcrumbItem(label = developerTypeLabel(detail))
                            } else {
                                BreadcrumbItem(label = stringResource(R.string.developer_title))
                            },
                            BreadcrumbItem(
                                label = detail?.title
                                    ?: stringResource(R.string.developer_detail_title),
                                isCurrent = true,
                            ),
                        ),
                        onNavigate = onNavigate,
                        ellipsisContentDescription = stringResource(R.string.breadcrumb_ellipsis),
                        moreMenuContentDescription = stringResource(R.string.breadcrumb_more_ancestors),
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
        when {
            uiState.loading -> {
                LoadingState(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                )
            }
            uiState.detail == null -> {
                EmptyState(
                    title = stringResource(R.string.developer_entry_missing),
                    body = "",
                    modifier = Modifier.padding(padding),
                )
            }
            else -> {
                val detail = uiState.detail!!
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    when (detail) {
                        is DeveloperDetailUi.ApiCredential -> {
                            item { MetadataText(stringResource(R.string.developer_field_service), detail.serviceName) }
                            item { MetadataText(stringResource(R.string.developer_field_account), detail.accountName) }
                            item {
                                SensitiveActionRow(
                                    label = stringResource(R.string.developer_field_api_key),
                                    revealed = isRevealed("apiKey"),
                                    revealedValue = getRevealedValue("apiKey"),
                                    onReveal = onRevealApiKey,
                                    onCopy = onCopyApiKey,
                                )
                            }
                            item {
                                SensitiveActionRow(
                                    label = stringResource(R.string.developer_field_api_secret),
                                    revealed = isRevealed("apiSecret"),
                                    revealedValue = getRevealedValue("apiSecret"),
                                    onReveal = onRevealApiSecret,
                                    onCopy = onCopyApiSecret,
                                )
                            }
                        }
                        is DeveloperDetailUi.SshKey -> {
                            item { MetadataText(stringResource(R.string.developer_field_key_name), detail.keyName) }
                            if (detail.publicKeyPresent) {
                                item {
                                    Text(
                                        text = stringResource(R.string.developer_public_key_present),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            item {
                                SensitiveActionRow(
                                    label = stringResource(R.string.developer_field_private_key),
                                    revealed = isRevealed("privateKey"),
                                    revealedValue = getRevealedValue("privateKey"),
                                    onReveal = onRevealPrivateKey,
                                    onCopy = onCopyPrivateKey,
                                )
                            }
                            item {
                                SensitiveActionRow(
                                    label = stringResource(R.string.developer_field_passphrase),
                                    revealed = isRevealed("passphrase"),
                                    revealedValue = getRevealedValue("passphrase"),
                                    onReveal = onRevealPassphrase,
                                    onCopy = onCopyPassphrase,
                                )
                            }
                        }
                        is DeveloperDetailUi.GenericSecret -> {
                            detail.fieldLabels.forEach { label ->
                                val key = "field:$label"
                                item(key = key) {
                                    SensitiveActionRow(
                                        label = label,
                                        revealed = isRevealed(key),
                                        revealedValue = getRevealedValue(key),
                                        onReveal = { onRevealGeneric(key) },
                                        onCopy = { onCopyGeneric(key) },
                                    )
                                }
                            }
                        }
                        is DeveloperDetailUi.AndroidSigningKey -> {
                            item { MetadataText(stringResource(R.string.developer_field_project_name), detail.projectName) }
                            item { MetadataText(stringResource(R.string.developer_field_package_name), detail.packageName) }
                            item { MetadataText(stringResource(R.string.developer_field_keystore_file), detail.keystoreFileName) }
                            item { MetadataText(stringResource(R.string.developer_field_key_alias), detail.keyAlias) }
                            item {
                                SensitiveActionRow(
                                    label = stringResource(R.string.developer_field_store_password),
                                    revealed = isRevealed("storePassword"),
                                    revealedValue = getRevealedValue("storePassword"),
                                    onReveal = onRevealStorePassword,
                                    onCopy = onCopyStorePassword,
                                )
                            }
                            item {
                                SensitiveActionRow(
                                    label = stringResource(R.string.developer_field_key_password),
                                    revealed = isRevealed("keyPassword"),
                                    revealedValue = getRevealedValue("keyPassword"),
                                    onReveal = onRevealKeyPassword,
                                    onCopy = onCopyKeyPassword,
                                )
                            }
                            item {
                                OutlinedButton(
                                    onClick = onExportKeystore,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(stringResource(R.string.developer_export_keystore))
                                }
                            }
                            item {
                                OutlinedButton(
                                    onClick = onCopyKeyProperties,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(stringResource(R.string.developer_copy_key_properties))
                                }
                            }
                        }
                        is DeveloperDetailUi.EnvironmentVariableSet -> {
                            item { MetadataText(stringResource(R.string.developer_field_project_name), detail.projectName) }
                            detail.variableNames.forEach { name ->
                                val key = "var:$name"
                                item(key = key) {
                                    SensitiveActionRow(
                                        label = name,
                                        revealed = isRevealed(key),
                                        revealedValue = getRevealedValue(key),
                                        onReveal = { onRevealEnvVar(key) },
                                        onCopy = { onCopyEnvVar(key) },
                                    )
                                }
                            }
                        }
                    }
                    item {
                        if (detail is DeveloperDetailUi.GenericSecret) {
                            // No per-field public key display for generic — the
                            // labels ARE the field metadata.
                        }
                    }
                    item {
                        OutlinedButton(
                            onClick = onEdit,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.developer_edit))
                        }
                    }
                    item {
                        TextButton(
                            onClick = onDelete,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = stringResource(R.string.developer_delete),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    if (uiState.authUnavailable) {
                        item {
                            Text(
                                text = stringResource(R.string.auth_unavailable),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    if (uiState.authCancelled) {
                        item {
                            Text(
                                text = stringResource(R.string.auth_cancelled),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
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

@Composable
private fun MetadataText(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
        )
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
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                text = if (revealed && revealedValue != null) {
                    revealedValue
                } else {
                    "••••••••"
                },
                style = if (revealed && revealedValue != null) {
                    MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                    )
                } else {
                    MaterialTheme.typography.bodyLarge
                },
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onReveal) {
                Text(
                    stringResource(
                        if (revealed) R.string.developer_hide else R.string.developer_reveal,
                    ),
                )
            }
            IconButton(onClick = onCopy) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = stringResource(R.string.developer_copy),
                )
            }
        }
    }
}

/** Delete confirmation wrapper (destructive; P4 no Undo — Issue #20 §17). */
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
private fun DeveloperDetailApiPreview() {
    RescueAuthTheme {
        DeveloperDetailScreen(
            uiState = DeveloperDetailUiState(
                loading = false,
                detail = DeveloperDetailUi.ApiCredential(
                    stableId = "1",
                    title = "Stripe",
                    serviceName = "stripe",
                    accountName = "alice",
                ),
            ),
            onBack = {},
            onRevealApiKey = {},
            onRevealApiSecret = {},
            onCopyApiKey = {},
            onCopyApiSecret = {},
            onRevealPrivateKey = {},
            onCopyPrivateKey = {},
            onRevealPassphrase = {},
            onCopyPassphrase = {},
            onRevealGeneric = {},
            onCopyGeneric = {},
            getRevealedValue = { null },
            isRevealed = { false },
            onEdit = {},
            onDelete = {},
            onRevealStorePassword = {},
            onCopyStorePassword = {},
            onRevealKeyPassword = {},
            onCopyKeyPassword = {},
            onExportKeystore = {},
            onCopyKeyProperties = {},
            onRevealEnvVar = {},
            onCopyEnvVar = {},
        )
    }
}
