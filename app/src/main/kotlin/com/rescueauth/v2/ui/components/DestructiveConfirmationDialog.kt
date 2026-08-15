package com.rescueauth.v2.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.theme.RescueAuthTheme

/**
 * Destructive confirmation dialog.
 *
 * Used by high-damage operations that must NOT go through Undo (provider
 * cascading delete, signing key with keystore, large merge). Ordinary deletes
 * should use the [UndoSnackbarHost] contract instead.
 */
@Composable
fun DestructiveConfirmationDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = { Text(text = message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmLabel,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.destructive_dialog_cancel))
            }
        },
    )
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun DestructiveConfirmationDialogPreview() {
    RescueAuthTheme {
        DestructiveConfirmationDialog(
            title = "Delete this entry?",
            message = "This action cannot be undone.",
            confirmLabel = "Delete",
            onConfirm = {},
            onDismiss = {},
        )
    }
}
