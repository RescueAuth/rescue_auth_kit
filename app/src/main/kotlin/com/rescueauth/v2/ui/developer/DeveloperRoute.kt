package com.rescueauth.v2.ui.developer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rescueauth.v2.R
import com.rescueauth.v2.repository.VaultAccess
import com.rescueauth.v2.security.SensitiveAction
import com.rescueauth.v2.security.SensitiveActionAccess
import com.rescueauth.v2.session.SecureSessionStateMachine
import com.rescueauth.v2.ui.components.FloatingAddPosition
import com.rescueauth.v2.ui.components.LoadingState
import com.rescueauth.v2.ui.components.LocalRescueAuthSnackbarHostState
import com.rescueauth.v2.ui.components.UndoResult
import com.rescueauth.v2.ui.components.UndoSnackbarContract
import com.rescueauth.v2.ui.components.rememberFloatingAddPosition
import com.rescueauth.v2.ui.components.showInstantSnackbar
import com.rescueauth.v2.ui.model.DeveloperEntryType
import com.rescueauth.v2.ui.model.DeveloperEntryUi
import com.rescueauth.v2.ui.screens.developer.DeveloperAddSheet
import com.rescueauth.v2.ui.screens.developer.DeveloperDeleteDialog
import com.rescueauth.v2.ui.screens.developer.DeveloperDetailScreen
import com.rescueauth.v2.ui.screens.developer.DeveloperFormScreen
import com.rescueauth.v2.ui.screens.developer.DeveloperScreen
import kotlinx.coroutines.launch

/**
 * Developer Vault list route (Phase 4 P4).
 *
 * Wires the production [DeveloperListViewModel] (metadata-only list) and the
 * Add sheet. Navigation to detail / form is delegated to the NavHost via
 * [onOpenEntry] / [onAddTypeSelected].
 */
@Composable
fun DeveloperRoute(
    modifier: Modifier = Modifier,
    onOpenEntry: ((DeveloperEntryUi) -> Unit)? = null,
    onAddTypeSelected: ((DeveloperFormType) -> Unit)? = null,
    viewModel: DeveloperListViewModel? = null,
    categoryType: DeveloperEntryType? = null,
    showAddAction: Boolean = true,
    contentBottomPadding: Dp = 0.dp,
    onOpenCategory: ((DeveloperEntryType) -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    floatingAddPosition: FloatingAddPosition = rememberFloatingAddPosition(),
    isActive: Boolean = true,
) {
    val context = LocalContext.current
    val appScope = rememberCoroutineScope()
    val shellSnackbar = LocalRescueAuthSnackbarHostState.current
    val localSnackbarHostState = remember { SnackbarHostState() }
    val activeSnackbar = shellSnackbar ?: localSnackbarHostState
    var showAddSheet by remember { mutableStateOf(false) }

    val sessionState: kotlinx.coroutines.flow.StateFlow<SecureSessionStateMachine.State> = remember {
        val sm = VaultAccess.sessionManager
        if (sm != null) {
            sm.sessionStateFlow
        } else {
            SecureSessionStateMachine().state
        }
    }
    val listViewModel = viewModel ?: remember {
        DeveloperListViewModel(
            developerRepositoryProvider = { VaultAccess.developerRepository() },
            sessionState = sessionState,
            scope = appScope,
        )
    }
    val listState by listViewModel.uiState.collectAsState()
    val undoVersion by DeveloperUndoStore.version.collectAsState()

    // P8 §32: an ordinary Developer entry deleted from the Detail screen leaves
    // a pending Undo token in the shared store. The list route (which survives
    // the detail pop) shows the Undo Snackbar here so it lives beyond the
    // post-navigation lifecycle.
    LaunchedEffect(undoVersion, isActive) {
        if (!isActive) return@LaunchedEffect
        DeveloperUndoStore.pending ?: return@LaunchedEffect
        activeSnackbar.currentSnackbarData?.dismiss()
        val message = context.getString(R.string.developer_entry_deleted_message)
        val actionLabel = context.getString(R.string.undo_snackbar_action)
        val result = UndoSnackbarContract.showUndoSnackbar(
            activeSnackbar,
            message,
            actionLabel,
        )
        if (result == UndoResult.UNDO) {
            val snapshot = DeveloperUndoStore.consume()
            if (snapshot != null) {
                val outcome = runCatching {
                    VaultAccess.developerRepository()?.restoreFromSnapshot(snapshot)
                }.getOrNull()?.let { it is com.rescueauth.v2.domain.UndoRestoreOutcome.Restored }
                if (outcome == true) {
                    activeSnackbar.showInstantSnackbar(context.getString(R.string.developer_entry_restored_message))
                } else {
                    activeSnackbar.showInstantSnackbar(context.getString(R.string.developer_restore_blocked))
                }
            }
        } else {
            DeveloperUndoStore.clear()
        }
    }

    DeveloperScreen(
        uiState = listState,
        snackbarHostState = if (shellSnackbar == null) localSnackbarHostState else null,
        onAddClick = if (showAddAction) { {
            if (categoryType != null && onAddTypeSelected != null) onAddTypeSelected(DeveloperFormType.valueOf(categoryType.name))
            else showAddSheet = true
        } } else null,
        contentBottomPadding = contentBottomPadding,
        floatingAddPosition = floatingAddPosition,
        onEntryClick = onOpenEntry,
        onOpenCategory = onOpenCategory,
        onBack = onBack,
        categoryType = categoryType,
        modifier = modifier,
    )

    if (showAddSheet && isActive) {
        DeveloperAddSheet(
            onDismiss = { showAddSheet = false },
            onSelectType = { type ->
                showAddSheet = false
                onAddTypeSelected?.invoke(type)
            },
        )
    }
}

/**
 * Developer entry detail route (Phase 4 P4).
 *
 * Wires [DeveloperDetailViewModel] with the production
 * [SensitiveActionAccess.gate] and the clipboard. Revealed plaintext stays in
 * the ViewModel and is cleared when the route leaves composition
 * (Issue #20 §14).
 */
@Composable
fun DeveloperDetailRoute(
    stableId: String,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    modifier: Modifier = Modifier,
    onNavigate: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val view = LocalView.current
    val appScope = rememberCoroutineScope()
    val shellSnackbar = LocalRescueAuthSnackbarHostState.current
    val localSnackbarHostState = remember { SnackbarHostState() }
    val activeSnackbar = shellSnackbar ?: localSnackbarHostState
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Phase 4 P6 §7: SAF CreateDocument for the raw keystore export. The bytes
    // are held in a plain Compose state (not SavedStateHandle / rememberSaveable
    // / Bundle) and only exist between a successful fresh re-auth and the SAF
    // write — released immediately after (Issue #20 P6 §7/§16).
    var pendingExport: ByteArray? by remember { mutableStateOf(null) }
    var pendingExportFileName by remember { mutableStateOf("") }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(
            "application/octet-stream",
        ),
    ) { uri ->
        val bytes = pendingExport ?: return@rememberLauncherForActivityResult
        pendingExport = null
        if (uri != null) {
            appScope.launch {
                val ok = runCatching {
                    val resolver = context.contentResolver
                    val out = resolver.openOutputStream(uri, "w") ?: return@runCatching false
                    out.use { it.write(bytes); it.flush() }
                    true
                }.getOrDefault(false)
                if (ok) {
                    activeSnackbar.showInstantSnackbar(context.getString(R.string.developer_keystore_exported))
                } else {
                    // Best-effort cleanup of a partially-created SAF document.
                    runCatching { context.contentResolver.delete(uri, null, null) }
                    activeSnackbar.showInstantSnackbar(context.getString(R.string.common_error_title))
                }
            }
        }
    }

    val sessionState: kotlinx.coroutines.flow.StateFlow<SecureSessionStateMachine.State> = remember {
        val sm = VaultAccess.sessionManager
        if (sm != null) {
            sm.sessionStateFlow
        } else {
            SecureSessionStateMachine().state
        }
    }
    val viewModel = remember(stableId) {
        DeveloperDetailViewModel(
            developerRepositoryProvider = { VaultAccess.developerRepository() },
            sensitiveActionGate = SensitiveActionAccess.gate,
            sessionState = sessionState,
            stableId = stableId,
            scope = appScope,
        )
    }
    val uiState by viewModel.uiState.collectAsState()
    val events by viewModel.events.collectAsState()

    // Session lock may arrive between a successful fresh re-auth and the SAF
    // write. Never keep raw keystore bytes in memory across a lock: clear the
    // pending buffer immediately when the session leaves UNLOCKED.
    LaunchedEffect(sessionState) {
        sessionState.collect { state ->
            if (state != SecureSessionStateMachine.State.UNLOCKED) {
                pendingExport = null
                pendingExportFileName = ""
            }
        }
    }

    // Clear revealed state when leaving the screen (Issue #20 §14).
    DisposableEffect(Unit) {
        onDispose { viewModel.clearRevealed() }
    }

    LaunchedEffect(events) {
        val event = events ?: return@LaunchedEffect
        viewModel.onEventShown()
        when (event) {
            is DeveloperDetailEvent.CopySecret -> {
                copyToClipboard(context, event.value, event.label)
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                activeSnackbar.showInstantSnackbar(context.getString(R.string.developer_copied))
            }
            is DeveloperDetailEvent.Deleted -> {
                // Signing key deletions have no Undo token; surface confirmation on the shell snackbar.
                if (DeveloperUndoStore.pending == null && shellSnackbar != null) {
                    val msg = context.getString(R.string.developer_deleted_message, event.label)
                    appScope.launch { shellSnackbar.showInstantSnackbar(msg) }
                }
            }
            is DeveloperDetailEvent.Restored -> Unit
            is DeveloperDetailEvent.RestoreBlocked -> Unit
            is DeveloperDetailEvent.AuthUnavailable -> {
                activeSnackbar.showInstantSnackbar(context.getString(R.string.auth_unavailable))
            }
            is DeveloperDetailEvent.ExportKeystore -> {
                pendingExport = event.bytes
                pendingExportFileName = event.suggestedFileName
                exportLauncher.launch(event.suggestedFileName.ifBlank { "keystore.jks" })
            }
            is DeveloperDetailEvent.CopyKeyProperties -> {
                copyToClipboard(context, event.snippet, event.label)
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                activeSnackbar.showInstantSnackbar(context.getString(R.string.developer_copied))
            }
        }
    }

    fun toggleReveal(action: SensitiveAction, fieldKey: String) {
        if (viewModel.isRevealed(fieldKey)) {
            viewModel.hide(fieldKey)
        } else {
            viewModel.reveal(action, fieldKey)
        }
    }

    val detail = uiState.detail
    DeveloperDetailScreen(
        uiState = uiState,
        onBack = onBack,
        onRevealApiKey = { toggleReveal(SensitiveAction.REVEAL_API_SECRET, "apiKey") },
        onRevealApiSecret = { toggleReveal(SensitiveAction.REVEAL_API_SECRET, "apiSecret") },
        onCopyApiKey = { viewModel.copySecret(SensitiveAction.COPY_API_SECRET, "apiKey", "apiKey") },
        onCopyApiSecret = { viewModel.copySecret(SensitiveAction.COPY_API_SECRET, "apiSecret", "apiSecret") },
        onRevealPrivateKey = { toggleReveal(SensitiveAction.REVEAL_SSH_PRIVATE_KEY, "privateKey") },
        onCopyPrivateKey = { viewModel.copySecret(SensitiveAction.COPY_SSH_PRIVATE_KEY, "privateKey", "privateKey") },
        onRevealPassphrase = { toggleReveal(SensitiveAction.REVEAL_SSH_PASSPHRASE, "passphrase") },
        onCopyPassphrase = { viewModel.copySecret(SensitiveAction.COPY_SSH_PASSPHRASE, "passphrase", "passphrase") },
        onRevealGeneric = { key -> toggleReveal(SensitiveAction.REVEAL_GENERIC_SECRET, key) },
        onCopyGeneric = { key -> viewModel.copySecret(SensitiveAction.COPY_GENERIC_SECRET, key, "generic") },
        getRevealedValue = { viewModel.revealedValue(it) },
        isRevealed = { it in uiState.revealedFieldKeys || viewModel.isRevealed(it) },
        onEdit = { if (detail != null) onEdit(detail.stableId) },
        // P8 §14: Android Signing Key keeps destructive confirmation (no Undo);
        // ordinary Developer entries delete immediately (Undo shown at the list
        // route via the shared store, P8 §12/§32).
        onDelete = {
            if (detail is com.rescueauth.v2.ui.model.DeveloperDetailUi.AndroidSigningKey) {
                showDeleteDialog = true
            } else {
                showDeleteDialog = false
                appScope.launch {
                    if (viewModel.delete()) onBack()
                }
            }
        },
        onRevealStorePassword = { toggleReveal(SensitiveAction.REVEAL_SIGNING_STORE_PASSWORD, "storePassword") },
        onCopyStorePassword = { viewModel.copySecret(SensitiveAction.COPY_SIGNING_STORE_PASSWORD, "storePassword", "storePassword") },
        onRevealKeyPassword = { toggleReveal(SensitiveAction.REVEAL_SIGNING_KEY_PASSWORD, "keyPassword") },
        onCopyKeyPassword = { viewModel.copySecret(SensitiveAction.COPY_SIGNING_KEY_PASSWORD, "keyPassword", "keyPassword") },
        onExportKeystore = { viewModel.exportKeystore() },
        onCopyKeyProperties = { viewModel.copyKeyProperties() },
        onRevealEnvVar = { key -> toggleReveal(SensitiveAction.REVEAL_ENV_VAR_VALUE, key) },
        onCopyEnvVar = { key -> viewModel.copySecret(SensitiveAction.COPY_ENV_VAR_VALUE, key, "env") },
        snackbarHostState = if (shellSnackbar == null) localSnackbarHostState else null,
        onCopyPublicKey = { publicKey ->
            copyToClipboard(context, publicKey, "publicKey")
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            appScope.launch {
                activeSnackbar.showInstantSnackbar(context.getString(R.string.developer_public_key_copied))
            }
        },
        modifier = modifier,
        onNavigate = onNavigate,
    )

    if (showDeleteDialog && detail != null) {
        DeveloperDeleteDialog(
            title = detail.title,
            message = when (detail) {
                is com.rescueauth.v2.ui.model.DeveloperDetailUi.AndroidSigningKey ->
                    context.getString(R.string.developer_delete_signing_confirm_message)
                is com.rescueauth.v2.ui.model.DeveloperDetailUi.EnvironmentVariableSet ->
                    context.getString(R.string.developer_delete_env_confirm_message)
                else -> context.getString(R.string.developer_delete_confirm_message)
            },
            onConfirm = {
                showDeleteDialog = false
                appScope.launch {
                    if (viewModel.delete()) onBack()
                }
            },
            onDismiss = { showDeleteDialog = false },
        )
    }
}

/**
 * Developer entry create/edit form route (Phase 4 P4).
 */
@Composable
fun DeveloperFormRoute(
    editingStableId: String?,
    initialType: DeveloperFormType,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigate: (String) -> Unit = {},
    formViewModel: DeveloperFormViewModel? = null,
) {
    val context = LocalContext.current
    val appScope = rememberCoroutineScope()
    val shellSnackbar = LocalRescueAuthSnackbarHostState.current
    val localSnackbarHostState = remember { SnackbarHostState() }
    val activeSnackbar = shellSnackbar ?: localSnackbarHostState

    val sessionState: kotlinx.coroutines.flow.StateFlow<SecureSessionStateMachine.State> = remember {
        val sm = VaultAccess.sessionManager
        if (sm != null) {
            sm.sessionStateFlow
        } else {
            SecureSessionStateMachine().state
        }
    }
    val viewModel = formViewModel ?: remember(editingStableId, initialType) {
        DeveloperFormViewModel(
            developerRepositoryProvider = { VaultAccess.developerRepository() },
            sessionState = sessionState,
            scope = appScope,
            sensitiveActionGate = SensitiveActionAccess.gate,
        )
    }
    val formState by viewModel.formState.collectAsState()
    val events by viewModel.events.collectAsState()

    DisposableEffect(viewModel) {
        onDispose { viewModel.dismiss() }
    }

    LaunchedEffect(viewModel) {
        if (editingStableId != null) {
            if (!viewModel.beginEdit(editingStableId)) onBack()
        } else {
            viewModel.beginCreate(initialType)
        }
    }

    LaunchedEffect(events) {
        val event = events ?: return@LaunchedEffect
        viewModel.onEventShown()
        when (event) {
            is DeveloperFormEvent.Saved -> {
                if (shellSnackbar != null) {
                    val savedMessage = context.getString(R.string.developer_saved_message)
                    appScope.launch { shellSnackbar.showInstantSnackbar(savedMessage) }
                }
                // Completion must reach navigation immediately without suspending on a snackbar.
                onSaved()
            }
            is DeveloperFormEvent.Error -> {
                activeSnackbar.showInstantSnackbar(context.getString(R.string.common_error_title))
            }
        }
    }

    // Route restoration and direct navigation use the same guarded loader as a normal Edit tap.
    // No editable control or plaintext form state is rendered while verification is pending.
    if (editingStableId != null && formState.editingStableId != editingStableId) {
        BackHandler { viewModel.dismiss(); onBack() }
        LoadingState(modifier = modifier.fillMaxSize())
        return
    }

    DeveloperFormScreen(
        form = formState,
        onBack = onBack,
        onTitleChange = viewModel::onTitleChange,
        onNotesChange = viewModel::onNotesChange,
        onServiceNameChange = viewModel::onServiceNameChange,
        onAccountNameChange = viewModel::onAccountNameChange,
        onApiKeyChange = viewModel::onApiKeyChange,
        onApiSecretChange = viewModel::onApiSecretChange,
        onKeyNameChange = viewModel::onKeyNameChange,
        onPublicKeyChange = viewModel::onPublicKeyChange,
        onPrivateKeyChange = viewModel::onPrivateKeyChange,
        onPassphraseChange = viewModel::onPassphraseChange,
        onFieldLabelChange = viewModel::onFieldLabelChange,
        onFieldValueChange = viewModel::onFieldValueChange,
        onAddField = viewModel::addField,
        onRemoveField = viewModel::removeField,
        onProjectNameChange = viewModel::onProjectNameChange,
        onPackageNameChange = viewModel::onPackageNameChange,
        onStorePasswordChange = viewModel::onStorePasswordChange,
        onKeyAliasChange = viewModel::onKeyAliasChange,
        onKeyPasswordChange = viewModel::onKeyPasswordChange,
        onKeystoreSelected = viewModel::onKeystoreSelected,
        onClearKeystore = viewModel::clearKeystoreBuffer,
        onVariableNameChange = viewModel::onVariableNameChange,
        onVariableValueChange = viewModel::onVariableValueChange,
        onAddVariable = viewModel::addVariable,
        onRemoveVariable = viewModel::removeVariable,
        onSubmit = { appScope.launch { viewModel.submit() } },
        snackbarHostState = if (shellSnackbar == null) localSnackbarHostState else null,
        modifier = modifier,
        onNavigate = onNavigate,
    )
}

private fun copyToClipboard(context: Context, value: String, label: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
}
