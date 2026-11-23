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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import com.rescueauth.v2.R
import com.rescueauth.v2.repository.VaultAccess
import com.rescueauth.v2.session.SecureSessionStateMachine
import com.rescueauth.v2.ui.components.FloatingAddPosition
import com.rescueauth.v2.ui.components.LocalRescueAuthSnackbarHostState
import com.rescueauth.v2.ui.components.UndoResult
import com.rescueauth.v2.ui.components.UndoSnackbarContract
import com.rescueauth.v2.ui.components.rememberFloatingAddPosition
import com.rescueauth.v2.ui.components.showInstantSnackbar
import com.rescueauth.v2.ui.screens.authenticator.MoveRecoveryDialog
import com.rescueauth.v2.ui.screens.authenticator.RecoveryCodeEditorSheet
import com.rescueauth.v2.ui.screens.authenticator.RecoveryCodesScreen
import kotlinx.coroutines.launch

/**
 * Recovery Codes route — wires the production ViewModel, Android clipboard,
 * haptic feedback, unified Snackbar host, and Add/Edit/Move dialogs.
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
    val haptics = LocalHapticFeedback.current
    val appScope = rememberCoroutineScope()
    val shellSnackbar = LocalRescueAuthSnackbarHostState.current
    val localSnackbarHostState = remember { SnackbarHostState() }
    val snackbarHostState = shellSnackbar ?: localSnackbarHostState

    var showEditor by remember { mutableStateOf(false) }
    var initialCreateConsumed by rememberSaveable(accountId) { mutableStateOf(false) }
    var editingSetId by remember { mutableStateOf<String?>(null) }
    var moveSetId by remember { mutableStateOf<String?>(null) }
    var moveDestinations by remember { mutableStateOf<List<MoveDestination>>(emptyList()) }

    val sessionState: kotlinx.coroutines.flow.StateFlow<SecureSessionStateMachine.State> = remember {
        val sm = VaultAccess.sessionManager
        if (sm != null) sm.sessionStateFlow else SecureSessionStateMachine().state
    }
    val viewModel = remember(accountId) {
        RecoveryViewModel(
            authRepositoryProvider = { VaultAccess.authenticatorRepository() },
            recoveryRepositoryProvider = { VaultAccess.recoveryRepository() },
            sessionState = sessionState,
            accountId = accountId,
            scope = appScope,
            defaultTitleProvider = { context.getString(R.string.recovery_codes_title) },
        )
    }

    val uiState by viewModel.uiState.collectAsState()
    val formState by viewModel.formState.collectAsState()
    val events by viewModel.events.collectAsState()
    val revealedIds by viewModel.revealedIds.collectAsState()

    LaunchedEffect(openCreateOnStart, initialCreateConsumed, uiState) {
        if (openCreateOnStart && !initialCreateConsumed && !uiState.loading && uiState.accountId == accountId) {
            initialCreateConsumed = true
            if (uiState.error == null && uiState.accountName.isNotBlank()) {
                viewModel.beginCreate()
                showEditor = true
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.clearRevealed() }
    }

    LaunchedEffect(events) {
        val event = events ?: return@LaunchedEffect
        viewModel.onEventShown()
        appScope.launch {
            when (event) {
                is RecoveryEvent.CopyCode -> {
                    copyToClipboard(context, event.value, "recovery-code")
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    snackbarHostState.showInstantSnackbar(context.getString(R.string.recovery_codes_copied))
                }
                is RecoveryEvent.CopyAll -> {
                    copyToClipboard(context, event.values.joinToString("\n"), "recovery-codes-all")
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    snackbarHostState.showInstantSnackbar(context.getString(R.string.recovery_codes_all_copied))
                }
                is RecoveryEvent.CopyRemaining -> {
                    copyToClipboard(context, event.values.joinToString("\n"), "recovery-codes-remaining")
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    snackbarHostState.showInstantSnackbar(context.getString(R.string.recovery_codes_remaining_copied))
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
                    snackbarHostState.showInstantSnackbar(context.getString(R.string.recovery_codes_restored_message))
                }
                is RecoveryEvent.Added -> {
                    snackbarHostState.showInstantSnackbar(context.getString(R.string.recovery_codes_added_message))
                }
                is RecoveryEvent.Edited -> {
                    snackbarHostState.showInstantSnackbar(context.getString(R.string.recovery_codes_edited_message))
                }
                is RecoveryEvent.MarkedUsed,
                is RecoveryEvent.MarkedUnused,
                -> {
                    // Inline strikethrough already communicates the toggle visually;
                    // deliver subtle tactile confirmation without queueing snackbars.
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
                is RecoveryEvent.Moved -> {
                    snackbarHostState.showInstantSnackbar(context.getString(R.string.recovery_move_moved_message))
                }
                is RecoveryEvent.Error -> {
                    val errText = when (event.message) {
                        "move_failed" -> context.getString(R.string.recovery_move_failed)
                        "move_conflict" -> context.getString(R.string.recovery_move_conflict)
                        else -> context.getString(R.string.common_error_title)
                    }
                    snackbarHostState.showInstantSnackbar(errText)
                }
            }
        }
    }

    RecoveryCodesScreen(
        uiState = uiState,
        snackbarHostState = if (shellSnackbar == null) localSnackbarHostState else null,
        revealedIds = revealedIds,
        onBack = onBack,
        onAddClick = if (uiState.accountId == accountId && uiState.accountName.isNotBlank() && uiState.error == null) {
            {
                editingSetId = null
                viewModel.beginCreate()
                showEditor = true
            }
        } else null,
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
            appScope.launch {
                val dests = viewModel.availableMoveDestinations()
                if (dests.isEmpty()) {
                    snackbarHostState.showInstantSnackbar(context.getString(R.string.recovery_move_no_other_accounts))
                } else {
                    moveDestinations = dests
                    moveSetId = setId
                }
            }
        },
        onNavigate = onNavigate,
        modifier = modifier.testTag("recovery_scope_$accountId"),
        floatingAddPosition = floatingAddPosition,
    )

    val movingSet = moveSetId?.let { id -> uiState.sets.firstOrNull { it.id == id } }
    if (movingSet != null && moveDestinations.isNotEmpty()) {
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
            onSubmit = {
                appScope.launch {
                    if (viewModel.submitForm()) {
                        showEditor = false
                        editingSetId = null
                    }
                }
            },
        )
    }
}

private fun copyToClipboard(context: Context, text: String, label: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}
