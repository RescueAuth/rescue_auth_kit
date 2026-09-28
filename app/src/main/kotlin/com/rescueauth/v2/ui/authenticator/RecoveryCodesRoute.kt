package com.rescueauth.v2.ui.authenticator

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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.rescueauth.v2.ui.components.FloatingAddPosition
import com.rescueauth.v2.ui.components.rememberFloatingAddPosition
import androidx.compose.ui.platform.LocalContext
import com.rescueauth.v2.R
import com.rescueauth.v2.repository.VaultAccess
import com.rescueauth.v2.session.SecureSessionStateMachine
import com.rescueauth.v2.ui.components.UndoResult
import com.rescueauth.v2.ui.components.UndoSnackbarContract
import com.rescueauth.v2.ui.screens.authenticator.MoveRecoveryDialog
import com.rescueauth.v2.ui.screens.authenticator.RecoveryCodeEditorSheet
import com.rescueauth.v2.ui.screens.authenticator.RecoveryCodesScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Recovery Codes route (Phase 4 P3) — wires the production ViewModel, the
 * Android clipboard, the Undo Snackbar, the Add/Edit sheet and session-lock
 * reveal cleanup into the presentational screen.
 *
 * Android-only side effects (clipboard write, snackbar action mapping) live
 * here — never inside the ViewModel or the screen.
 */
@Composable
fun RecoveryCodesRoute(
    accountId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigate: (String) -> Unit = {},
    openCreateOnStart: Boolean = false,
    floatingAddPosition: FloatingAddPosition = rememberFloatingAddPosition(),
) {
    val context = LocalContext.current
    val appScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showEditor by remember { mutableStateOf(false) }
    var initialCreateConsumed by rememberSaveable(accountId) { mutableStateOf(false) }
    var editingSetId by remember { mutableStateOf<String?>(null) }
    var moveSetId by remember { mutableStateOf<String?>(null) }
    var moveDestinations by remember { mutableStateOf<List<MoveDestination>>(emptyList()) }

    val sessionState: kotlinx.coroutines.flow.StateFlow<SecureSessionStateMachine.State> = remember {
        val sm = VaultAccess.sessionManager
        if (sm != null) {
            sm.sessionStateFlow
        } else {
            SecureSessionStateMachine().state
        }
    }
    val viewModel = remember(accountId) {
        RecoveryViewModel(
            authRepositoryProvider = { VaultAccess.authenticatorRepository() },
            recoveryRepositoryProvider = { VaultAccess.recoveryRepository() },
            sessionState = sessionState,
            accountId = accountId,
            scope = appScope,
        )
    }

    val uiState by viewModel.uiState.collectAsState()
    val formState by viewModel.formState.collectAsState()
    val events by viewModel.events.collectAsState()
    val revealedIds by viewModel.revealedIds.collectAsState()

    // The account-specific add entry opens a blank form once the owning account
    // has loaded. Recomposition or restored state must not reopen a dismissed editor.
    LaunchedEffect(openCreateOnStart, initialCreateConsumed, uiState) {
        if (openCreateOnStart && !initialCreateConsumed && !uiState.loading && uiState.accountId == accountId) {
            initialCreateConsumed = true
            if (uiState.error == null && uiState.accountName.isNotBlank()) {
                viewModel.beginCreate()
                showEditor = true
            }
        }
    }

    // When the user leaves the screen, clear all revealed secret state.
    DisposableEffect(Unit) {
        onDispose { viewModel.clearRevealed() }
    }

    // One-shot event handling (clipboard + snackbar).
    LaunchedEffect(events) {
        val event = events ?: return@LaunchedEffect
        when (event) {
            is RecoveryEvent.CopyCode -> {
                copyToClipboard(context, event.value, "recovery-code")
                snackbarHostState.showSnackbar(context.getString(R.string.recovery_codes_copied))
            }
            is RecoveryEvent.CopyAll -> {
                copyToClipboard(context, event.values.joinToString("\n"), "recovery-codes-all")
                snackbarHostState.showSnackbar(context.getString(R.string.recovery_codes_all_copied))
            }
            is RecoveryEvent.CopyRemaining -> {
                copyToClipboard(context, event.values.joinToString("\n"), "recovery-codes-remaining")
                snackbarHostState.showSnackbar(context.getString(R.string.recovery_codes_remaining_copied))
            }
            is RecoveryEvent.Deleted -> {
                val message = context.getString(R.string.recovery_codes_deleted_message, event.label)
                val actionLabel = context.getString(R.string.undo_snackbar_action)
                val result = UndoSnackbarContract.showUndoSnackbar(
                    snackbarHostState,
                    message,
                    actionLabel,
                )
                if (result == UndoResult.UNDO) {
                    appScope.launch { viewModel.undoDelete() }
                }
            }
            is RecoveryEvent.Restored -> {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.recovery_codes_restored_message),
                )
            }
            is RecoveryEvent.Added -> {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.recovery_codes_added_message),
                )
            }
            is RecoveryEvent.Edited -> {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.recovery_codes_edited_message),
                )
            }
            is RecoveryEvent.MarkedUsed -> {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.recovery_codes_marked_used),
                )
            }
            is RecoveryEvent.MarkedUnused -> {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.recovery_codes_marked_unused),
                )
            }
            is RecoveryEvent.Moved -> {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.recovery_move_moved_message),
                )
            }
            is RecoveryEvent.Error -> {
                snackbarHostState.showSnackbar(context.getString(R.string.common_error_title))
            }
        }
        viewModel.onEventShown()
    }

    RecoveryCodesScreen(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        revealedIds = revealedIds,
        onBack = onBack,
        onAddClick = if (uiState.accountId == accountId && uiState.accountName.isNotBlank() && uiState.error == null) { {
            editingSetId = null
            viewModel.beginCreate()
            showEditor = true
        } } else null,
        onRevealToggle = { viewModel.toggleReveal(it) },
        onCopyCode = { viewModel.copyCode(it) },
        onCopyAll = { viewModel.copyAll(it) },
        onCopyRemaining = { viewModel.copyRemaining(it) },
        onMarkUsed = { appScope.launch { viewModel.markUsed(it, "recovery-code") } },
        onMarkUnused = { appScope.launch { viewModel.markUnused(it, "recovery-code") } },
        onEdit = { setId ->
            editingSetId = setId
            viewModel.beginEdit(setId)
            showEditor = true
        },
        onDelete = { setId -> appScope.launch { viewModel.deleteSet(setId) } },
        onMove = { setId ->
            moveSetId = setId
            appScope.launch { moveDestinations = viewModel.availableMoveDestinations() }
        },
        onNavigate = onNavigate,
        modifier = modifier.testTag("recovery_scope_$accountId"),
        floatingAddPosition = floatingAddPosition,
    )

    val movingSet = moveSetId?.let { id -> uiState.sets.firstOrNull { it.id == id } }
    if (movingSet != null) {
        MoveRecoveryDialog(
            setTitle = movingSet.title,
            destinations = moveDestinations,
            onConfirm = { dest ->
                val id = moveSetId
                moveSetId = null
                moveDestinations = emptyList()
                if (id != null) appScope.launch { viewModel.moveSet(id, dest.accountId) }
            },
            onDismiss = {
                moveSetId = null
                moveDestinations = emptyList()
            },
        )
    }

    if (showEditor) {
        RecoveryCodeEditorSheet(
            modifier = Modifier.testTag("recovery_editor_sheet"),
            form = formState,
            onDismiss = {
                showEditor = false
                editingSetId = null
                viewModel.dismissForm()
            },
            onTitleChange = { viewModel.onTitleChange(it) },
            onValuesChange = { viewModel.onValuesChange(it) },
            onSubmit = { appScope.launch { viewModel.submitForm() } },
        )
    }
}

private fun copyToClipboard(context: Context, text: String, label: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}
