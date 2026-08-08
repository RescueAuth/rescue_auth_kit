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
import com.rescueauth.v2.session.SecureSessionStateMachine
import kotlinx.coroutines.flow.StateFlow

/**
 * Phase 3D Export/Import route — wires the ViewModel, the SAF
 * (ActivityResultContracts.CreateDocument / OpenDocument) launchers and the
 * screen into the app shell.
 *
 * - Export: Settings "Export Vault" → SAF CreateDocument → package info → PIN
 *   + confirm → export → success/failure. The SAF picker opens FIRST (no PIN
 *   is held across the picker callback); the PIN is entered only after the
 *   destination document is known and is passed synchronously to the codec.
 *   Cancelling either step produces no error state (Issue #1 §28).
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
            // User cancelled the SAF destination — no error state.
            viewModel.cancelExport()
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
                onStart = {
                    exportLauncher.launch(
                        PackageFileContract.suggestedExportFileName(System.currentTimeMillis()),
                    )
                },
                onExport = { pin -> viewModel.exportToUri(pin) },
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
