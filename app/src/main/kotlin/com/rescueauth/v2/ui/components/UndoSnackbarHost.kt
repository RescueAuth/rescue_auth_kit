package com.rescueauth.v2.ui.components

import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import com.rescueauth.v2.ui.theme.CardTokens

/**
 * Shell-scoped [SnackbarHostState] shared across routes inside [com.rescueauth.v2.ui.RescueAuthApp]
 * so feedback and Undo snackbars survive back-navigation and clear the floating `+` button.
 * Standalone screen/route unit tests receive `null` and fall back to their local host.
 */
val LocalRescueAuthSnackbarHostState = compositionLocalOf<SnackbarHostState?> { null }

/**
 * Dismisses any currently visible non-essential snackbar before showing [message],
 * preventing multi-second queues on rapid Copy / Save taps.
 */
suspend fun SnackbarHostState.showInstantSnackbar(
    message: String,
    duration: SnackbarDuration = SnackbarDuration.Short,
) {
    currentSnackbarData?.dismiss()
    showSnackbar(message = message, duration = duration)
}

/**
 * Result of showing an Undo snackbar.
 */
enum class UndoResult {
    UNDO,
    DISMISSED,
}

/**
 * Contract for the Undo Snackbar used by ordinary delete flows.
 */
object UndoSnackbarContract {
    suspend fun showUndoSnackbar(
        snackbarHostState: SnackbarHostState,
        message: String,
        actionLabel: String,
    ): UndoResult {
        snackbarHostState.currentSnackbarData?.dismiss()
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = actionLabel,
            withDismissAction = true,
            duration = SnackbarDuration.Short,
        )
        return when (result) {
            SnackbarResult.ActionPerformed -> UndoResult.UNDO
            SnackbarResult.Dismissed -> UndoResult.DISMISSED
        }
    }
}

/**
 * Card-First Snackbar host shared across the RescueAuth app shell and standalone routes.
 */
@Composable
fun UndoSnackbarHost(
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    SnackbarHost(
        hostState = snackbarHostState,
        modifier = modifier,
    ) { data ->
        Snackbar(
            snackbarData = data,
            shape = CardTokens.rowShape,
        )
    }
}
