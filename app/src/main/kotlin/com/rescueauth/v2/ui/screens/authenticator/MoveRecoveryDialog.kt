package com.rescueauth.v2.ui.screens.authenticator

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.authenticator.MoveDestination

/**
 * Recovery Set Move destination picker (Phase 4 P8 §16).
 *
 * Lists every other Account (Provider + Account context, read by a screen
 * reader) EXCLUDING the current owning Account. The dialog is kept minimal:
 * it never offers to create an Account/Provider, and Move is disabled when no
 * other Account exists (the caller shows the safe empty state instead).
 * No secret value is ever shown or placed in content descriptions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveRecoveryDialog(
    setTitle: String,
    destinations: List<MoveDestination>,
    onConfirm: (MoveDestination) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf<MoveDestination?>(destinations.firstOrNull()) }
    var expanded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.recovery_move_codes_title)) },
        text = {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                Text(
                    text = stringResource(R.string.recovery_move_to_account) + ": $setTitle",
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                )
                if (destinations.isEmpty()) {
                    Text(
                        text = stringResource(R.string.recovery_move_no_other_accounts),
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                } else {
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    ) {
                        OutlinedTextField(
                            value = selected?.label.orEmpty(),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.recovery_move_destination_label)) },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false },
                        ) {
                            destinations.forEach { d ->
                                DropdownMenuItem(
                                    text = { Text(d.label) },
                                    onClick = {
                                        selected = d
                                        expanded = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selected?.let(onConfirm) },
                enabled = selected != null,
            ) {
                Text(stringResource(R.string.management_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}
