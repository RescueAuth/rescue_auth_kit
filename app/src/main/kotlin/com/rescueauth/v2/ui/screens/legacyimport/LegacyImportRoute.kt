package com.rescueauth.v2.ui.screens.legacyimport

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.rescueauth.v2.legacyimport.LegacyImportViewModel
import com.rescueauth.v2.legacyimport.SafLegacyFileIo
import com.rescueauth.v2.repository.VaultAccess
import com.rescueauth.v2.session.SecureSessionStateMachine
import kotlinx.coroutines.flow.StateFlow

/**
 * Phase 5B — Legacy v1 `.rakvault` import route (Issue #1 Phase 5B).
 *
 * Wires the [LegacyImportViewModel], the SAF OpenDocument launcher and the
 * [LegacyImportScreen] into the app shell. This is a **separate** entry point
 * from the Native Package Import (ROADMAP §9 — the two flows stay fully
 * isolated: separate importer, password/PIN policy, error taxonomy and state
 * machine). They only share the underlying logical MergeEngine via
 * [com.rescueauth.v2.legacyimport.LegacyImportService] → [com.rescueauth.v2.repository.VaultRepository].
 */
@Composable
fun LegacyImportRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigate: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val appScope = rememberCoroutineScope()

    val sessionState: StateFlow<SecureSessionStateMachine.State> = remember {
        val sm = VaultAccess.sessionManager
        if (sm != null) sm.sessionStateFlow else SecureSessionStateMachine().state
    }
    val viewModel = remember(context) {
        LegacyImportViewModel(
            context = context,
            serviceProvider = { VaultAccess.legacyImportService() },
            fileIo = SafLegacyFileIo(),
            sessionState = sessionState,
            scope = appScope,
        )
    }

    DisposableEffect(viewModel) {
        onDispose { viewModel.dispose() }
    }

    val state by viewModel.state.collectAsState()

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            val fileName = SafLegacyFileIo().displayName(context, uri)
            val sizeBytes = SafLegacyFileIo().sizeHint(context, uri)
            viewModel.handlePickedDocument(uri, fileName, sizeBytes)
        } else {
            // User cancelled the picker — no error state.
            viewModel.cancelFilePick()
        }
    }

    val handleBack = {
        when (state) {
            LegacyImportViewModel.State.Decrypting,
            LegacyImportViewModel.State.Applying,
            -> Unit
            is LegacyImportViewModel.State.FileSelected,
            LegacyImportViewModel.State.AwaitingPassword,
            is LegacyImportViewModel.State.Preview,
            -> viewModel.cancelFilePick()
            is LegacyImportViewModel.State.Error -> {
                if ((state as LegacyImportViewModel.State.Error).action == LegacyImportViewModel.ErrorAction.RETRY_PASSWORD) {
                    viewModel.retryPassword()
                } else {
                    viewModel.reset()
                    onBack()
                }
            }
            is LegacyImportViewModel.State.Success -> {
                viewModel.reset()
                onBack()
            }
            LegacyImportViewModel.State.Idle -> onBack()
        }
    }

    BackHandler(enabled = state !is LegacyImportViewModel.State.Idle) {
        handleBack()
    }

    LegacyImportScreen(
        state = state,
        onPickFile = {
            launcher.launch(arrayOf("*/*"))
        },
        onSubmitPassword = { password -> viewModel.submitPassword(password) },
        onRetryPassword = { viewModel.retryPassword() },
        onCancelPassword = { viewModel.cancelPassword() },
        onConfirm = { viewModel.confirmImport() },
        onCancel = { viewModel.cancelFilePick() },
        onDismissResult = {
            viewModel.reset()
            onBack()
        },
        onBack = handleBack,
        modifier = modifier,
        onNavigate = onNavigate,
    )
}
