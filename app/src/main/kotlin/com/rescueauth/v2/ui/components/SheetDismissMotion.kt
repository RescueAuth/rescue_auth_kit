package com.rescueauth.v2.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch

/** Match scrim/back dismissal for explicit Cancel buttons; disposal cancels the pending callback. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberSheetDismiss(sheetState: SheetState, onDismiss: () -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    val latestDismiss by rememberUpdatedState(onDismiss)
    var dismissing by remember(sheetState) { mutableStateOf(false) }
    return remember(sheetState, scope) {
        {
            if (!dismissing) {
                dismissing = true
                scope.launch {
                    try {
                        sheetState.hide()
                        if (!sheetState.isVisible) latestDismiss()
                    } finally {
                        // A gesture may interrupt hiding while the same sheet remains mounted.
                        if (sheetState.isVisible) dismissing = false
                    }
                }
            }
        }
    }
}
