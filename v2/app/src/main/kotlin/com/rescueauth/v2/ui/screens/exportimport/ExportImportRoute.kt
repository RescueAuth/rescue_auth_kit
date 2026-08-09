package com.rescueauth.v2.ui.screens.exportimport

import android.net.Uri
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
import com.rescueauth.v2.exportimport.ExportImportViewModel
import com.rescueauth.v2.exportimport.PackageFileContract
import com.rescueauth.v2.exportimport.SafPackageFileIo
import com.rescueauth.v2.repository.VaultAccess
import com.rescueauth.v2.security.SensitiveActionAccess
import com.rescueauth.v2.session.SecureSessionStateMachine
import kotlinx.coroutines.flow.StateFlow

/**
 * Phase 3D Export/Import route — wires the ViewModel, the SAF
 * (ActivityResultContracts.CreateDocument / OpenDocument) launchers and the
 * screen into the app shell.
 *
 * - Export: Settings "Export Vault" → PIN + confirm → SAF CreateDocument →
 *   encode → write → success/failure. **The PIN is entered FIRST and the SAF
 *   destination is chosen second**, so cancelling the PIN never creates a
 *   document (no empty `.rakpkg` left behind). Cancelling either step produces
 *   no error state (Issue #1 §28).
 * - Import: Settings "Import Native Package" → SAF OpenDocument → then PIN →
 *   decode → preview → confirm.
 *
 * Android-only side effects (SAF, Uri) live here, never inside the ViewModel.
 */
@Composable
fun ExportImportRoute(
    mode: ExportImportMode,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appScope = rememberCoroutineScope()

    val sessionState: StateFlow<SecureSessionStateMachine.State> = remember {
        val sm = VaultAccess.sessionManager
        if (sm != null) sm.sessionStateFlow else SecureSessionStateMachine().state
    }
    val viewModel = remember {
        ExportImportViewModel(
            context = context.applicationContext,
            serviceProvider = { VaultAccess.exportImportService() },
            fileIo = SafPackageFileIo(),
            sessionState = sessionState,
            scope = appScope,
            sensitiveActionGate = SensitiveActionAccess.gate,
        )
    }

    DisposableEffect(viewModel) {
        onDispose { viewModel.dispose() }
    }

    val exportState by viewModel.exportState.collectAsState()
    val importState by viewModel.importState.collectAsState()

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(
            PackageFileContract.PACKAGE_MIME,
        ),
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.onExportDestinationPicked(uri)
        } else {
            // User cancelled the SAF destination after PIN confirmation — no
            // document was created, nothing to clean up.
            viewModel.cancelExportDestination()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.handlePickedDocument(uri)
        } else {
            // User cancelled the picker — no error state.
            viewModel.cancelImport()
        }
    }

    when (mode) {
        ExportImportMode.EXPORT -> {
            ExportVaultScreen(
                state = exportState,
                onStart = { viewModel.beginExport() },
                onSubmitPin = { pin, confirm -> viewModel.submitExportPin(pin, confirm) },
                onChooseDestination = {
                    exportLauncher.launch(
                        PackageFileContract.suggestedExportFileName(System.currentTimeMillis()),
                    )
                },
                onCancel = { viewModel.cancelExport() },
                onDismissResult = {
                    viewModel.resetExport()
                    onBack()
                },
                modifier = modifier,
            )
        }
        ExportImportMode.IMPORT -> {
            ImportNativePackageScreen(
                state = importState,
                onPickDocument = { importLauncher.launch(PackageFileContract.IMPORT_MIME_TYPES) },
                onDecode = { pin -> viewModel.decodeImportWithPin(pin) },
                onCancelPin = { viewModel.cancelImport() },
                onConfirm = { viewModel.confirmImport() },
                onCancel = { viewModel.cancelImport() },
                onDismissResult = {
                    viewModel.resetImport()
                    onBack()
                },
                modifier = modifier,
            )
        }
    }
}

enum class ExportImportMode { EXPORT, IMPORT }
