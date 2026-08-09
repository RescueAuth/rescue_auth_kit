package com.rescueauth.v2.ui.developer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import com.rescueauth.v2.R
import com.rescueauth.v2.repository.VaultAccess
import com.rescueauth.v2.security.SensitiveAction
import com.rescueauth.v2.security.SensitiveActionAccess
import com.rescueauth.v2.session.SecureSessionStateMachine
import com.rescueauth.v2.ui.model.DeveloperEntryUi
import com.rescueauth.v2.ui.screens.developer.DeveloperAddSheet
import com.rescueauth.v2.ui.screens.developer.DeveloperDeleteDialog
import com.rescueauth.v2.ui.screens.developer.DeveloperDetailScreen
import com.rescueauth.v2.ui.screens.developer.DeveloperFormScreen
import com.rescueauth.v2.ui.screens.developer.DeveloperScreen
import kotlinx.coroutines.CoroutineScope
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
) {
    val appScope = rememberCoroutineScope()
    var showAddSheet by remember { mutableStateOf(false) }

    val sessionState: kotlinx.coroutines.flow.StateFlow<SecureSessionStateMachine.State> = remember {
        val sm = VaultAccess.sessionManager
        if (sm != null) {
            sm.sessionStateFlow
        } else {
            SecureSessionStateMachine().state
        }
    }
    val listViewModel = remember {
        DeveloperListViewModel(
            developerRepositoryProvider = { VaultAccess.developerRepository() },
            sessionState = sessionState,
            scope = appScope,
        )
    }
    val listState by listViewModel.uiState.collectAsState()

    DeveloperScreen(
        uiState = listState,
        onAddClick = { showAddSheet = true },
        onEntryClick = onOpenEntry,
        modifier = modifier,
    )

    if (showAddSheet) {
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
) {
    val context = LocalContext.current
    val appScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteDialog by remember { mutableStateOf(false) }

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

    // Clear revealed state when leaving the screen (Issue #20 §14).
    DisposableEffect(Unit) {
        onDispose { viewModel.clearRevealed() }
    }

    LaunchedEffect(events) {
        val event = events ?: return@LaunchedEffect
        when (event) {
            is DeveloperDetailEvent.CopySecret -> {
                copyToClipboard(context, event.value, event.label)
                snackbarHostState.showSnackbar(context.getString(R.string.developer_copied))
            }
            is DeveloperDetailEvent.Deleted -> {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.developer_deleted_message, event.label),
                )
            }
            is DeveloperDetailEvent.AuthUnavailable -> {
                snackbarHostState.showSnackbar(context.getString(R.string.auth_unavailable))
            }
        }
        viewModel.onEventShown()
    }

    val detail = uiState.detail
    DeveloperDetailScreen(
        uiState = uiState,
        onBack = onBack,
        onRevealApiKey = { viewModel.reveal(SensitiveAction.REVEAL_API_SECRET, "apiKey") },
        onRevealApiSecret = { viewModel.reveal(SensitiveAction.REVEAL_API_SECRET, "apiSecret") },
        onCopyApiKey = { viewModel.copySecret(SensitiveAction.COPY_API_SECRET, "apiKey", "apiKey") },
        onCopyApiSecret = { viewModel.copySecret(SensitiveAction.COPY_API_SECRET, "apiSecret", "apiSecret") },
        onRevealPrivateKey = { viewModel.reveal(SensitiveAction.REVEAL_SSH_PRIVATE_KEY, "privateKey") },
        onCopyPrivateKey = { viewModel.copySecret(SensitiveAction.COPY_SSH_PRIVATE_KEY, "privateKey", "privateKey") },
        onRevealPassphrase = { viewModel.reveal(SensitiveAction.REVEAL_SSH_PASSPHRASE, "passphrase") },
        onCopyPassphrase = { viewModel.copySecret(SensitiveAction.COPY_SSH_PASSPHRASE, "passphrase", "passphrase") },
        onRevealGeneric = { key -> viewModel.reveal(SensitiveAction.REVEAL_GENERIC_SECRET, key) },
        onCopyGeneric = { key -> viewModel.copySecret(SensitiveAction.COPY_GENERIC_SECRET, key, "generic") },
        getRevealedValue = { viewModel.revealedValue(it) },
        isRevealed = { viewModel.isRevealed(it) },
        onEdit = { if (detail != null) onEdit(detail.stableId) },
        onDelete = { showDeleteDialog = true },
        modifier = modifier,
    )

    if (showDeleteDialog && detail != null) {
        DeveloperDeleteDialog(
            title = detail.title,
            onConfirm = {
                showDeleteDialog = false
                appScope.launch {
                    viewModel.delete()
                    onBack()
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
) {
    val context = LocalContext.current
    val appScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val sessionState: kotlinx.coroutines.flow.StateFlow<SecureSessionStateMachine.State> = remember {
        val sm = VaultAccess.sessionManager
        if (sm != null) {
            sm.sessionStateFlow
        } else {
            SecureSessionStateMachine().state
        }
    }
    val viewModel = remember {
        DeveloperFormViewModel(
            developerRepositoryProvider = { VaultAccess.developerRepository() },
            sessionState = sessionState,
            scope = appScope,
        )
    }
    val formState by viewModel.formState.collectAsState()
    val events by viewModel.events.collectAsState()

    LaunchedEffect(Unit) {
        if (editingStableId != null) {
            viewModel.beginEdit(editingStableId)
        } else {
            viewModel.beginCreate(initialType)
        }
    }

    LaunchedEffect(events) {
        val event = events ?: return@LaunchedEffect
        when (event) {
            is DeveloperFormEvent.Saved -> {
                snackbarHostState.showSnackbar(context.getString(R.string.developer_saved_message))
                onSaved()
            }
            is DeveloperFormEvent.Error -> {
                snackbarHostState.showSnackbar(context.getString(R.string.common_error_title))
            }
        }
        viewModel.onEventShown()
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
        onSubmit = { appScope.launch { viewModel.submit() } },
        modifier = modifier,
    )
}

private fun copyToClipboard(context: Context, value: String, label: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
}
