package com.rescueauth.v2.ui.components

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Result of showing an Undo snackbar.
 *
 * [Undo] means the user tapped the Undo action; [Dismissed] covers timeout,
 * swipe-away and any other dismissal.
 */
enum class UndoResult {
    UNDO,
    DISMISSED,
}

/**
 * Contract for the Undo Snackbar used by the delete flow.
 *
 * Ordinary deletes (TOTP, recovery set, ordinary developer entry, account)
 * should remove the item immediately, then show a snackbar via
 * [showUndoSnackbar] and, on [UndoResult.UNDO], restore it within the short
 * window. High-damage operations use [DestructiveConfirmationDialog] instead.
 *
 * Note: Undo persistence / domain rollback is deliberately NOT implemented in
 * this foundation PR — only the presentation contract is established.
 */
object UndoSnackbarContract {
    /**
     * Shows an Undo snackbar and maps the user action to [UndoResult].
     *
     * [actionLabel] must be resolved by the caller (e.g. via
     * `stringResource(R.string.undo_snackbar_action)` inside a Composable)
     * because this function is not itself composable.
     */
    suspend fun showUndoSnackbar(
        snackbarHostState: SnackbarHostState,
        message: String,
        actionLabel: String,
    ): UndoResult {
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = actionLabel,
            withDismissAction = true,
        )
        return when (result) {
            SnackbarResult.ActionPerformed -> UndoResult.UNDO
            SnackbarResult.Dismissed -> UndoResult.DISMISSED
        }
    }
}

/**
 * Snackbar host preconfigured for the RescueAuth app shell.
 *
 * Screens are expected to create their own [SnackbarHostState] and pass it
 * here (or use [Scaffold]'s default) — this composable exists so the whole app
 * shares one host presentation and the Undo contract is a single import point.
 */
@Composable
fun UndoSnackbarHost(
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    SnackbarHost(hostState = snackbarHostState, modifier = modifier)
}
