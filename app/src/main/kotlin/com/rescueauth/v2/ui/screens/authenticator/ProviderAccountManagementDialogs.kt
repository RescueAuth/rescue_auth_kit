package com.rescueauth.v2.ui.screens.authenticator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.rescueauth.v2.ui.components.RescueAuthTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.components.RescueAuthCard
import com.rescueauth.v2.ui.components.RescueAuthIconBadge
import com.rescueauth.v2.ui.model.AccountUi
import com.rescueauth.v2.ui.theme.CardTokens
import com.rescueauth.v2.ui.theme.Spacing

@Composable
private fun DialogTitle(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    androidx.compose.foundation.layout.Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        RescueAuthIconBadge(icon = icon, size = 36.dp, iconSize = 18.dp)
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
fun ManagementTextDialog(
    title: String,
    fieldLabel: String,
    initialValue: String = "",
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { DialogTitle(title, Icons.Filled.Edit) },
        text = {
            RescueAuthCard(containerColor = CardTokens.containerColor()) {
                RescueAuthTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(fieldLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.trim()) }) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
fun CreateProviderDialog(
    onConfirm: (provider: String, accountName: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var provider by remember { mutableStateOf("") }
    var accountName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { DialogTitle(stringResource(R.string.provider_create_title), Icons.Filled.Add) },
        text = {
            RescueAuthCard(containerColor = CardTokens.containerColor()) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    RescueAuthTextField(
                        value = provider,
                        onValueChange = { provider = it },
                        label = { Text(stringResource(R.string.provider_name_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    RescueAuthTextField(
                        value = accountName,
                        onValueChange = { accountName = it },
                        label = { Text(stringResource(R.string.account_name_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(provider.trim(), accountName.trim()) },
                enabled = provider.isNotBlank() && accountName.isNotBlank(),
            ) { Text(stringResource(R.string.management_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderPickerDialog(
    title: String,
    providers: List<String>,
    initialProvider: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember(initialProvider) { mutableStateOf(initialProvider) }
    var expanded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { DialogTitle(title, Icons.Filled.Edit) },
        text = {
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
            ) {
                RescueAuthTextField(
                    value = selected,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.account_move_destination)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    providers.forEach { provider ->
                        DropdownMenuItem(
                            text = { Text(provider) },
                            onClick = { selected = provider; expanded = false },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }, enabled = selected.isNotBlank()) {
                Text(stringResource(R.string.management_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
fun ManagementDestructiveDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { DialogTitle(title, Icons.Filled.Delete) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
fun MergeAccountDialog(
    source: AccountUi,
    destination: AccountUi,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { DialogTitle(stringResource(R.string.account_merge_title), Icons.Filled.MergeType) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                RescueAuthCard(containerColor = CardTokens.containerColor()) {
                    Text(
                        stringResource(
                            R.string.account_merge_source_summary,
                            source.providerName,
                            source.accountName,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(
                            R.string.account_merge_source_counts,
                            source.totpCredentials.size,
                            source.recoverySets.size,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                RescueAuthCard(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                    Text(
                        stringResource(
                            R.string.account_merge_destination_summary,
                            destination.providerName,
                            destination.accountName,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                RescueAuthCard(containerColor = CardTokens.elevatedContainerColor()) {
                    Text(
                        stringResource(R.string.account_merge_explanation),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.account_merge_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
