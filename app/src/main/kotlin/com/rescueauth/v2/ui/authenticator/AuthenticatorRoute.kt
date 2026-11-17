package com.rescueauth.v2.ui.authenticator

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.rescueauth.v2.R
import com.rescueauth.v2.repository.VaultAccess
import com.rescueauth.v2.session.SecureSessionStateMachine
import com.rescueauth.v2.ui.components.FloatingAddPosition
import com.rescueauth.v2.ui.components.LocalRescueAuthSnackbarHostState
import com.rescueauth.v2.ui.components.UndoResult
import com.rescueauth.v2.ui.components.UndoSnackbarContract
import com.rescueauth.v2.ui.components.rememberFloatingAddPosition
import com.rescueauth.v2.ui.components.showInstantSnackbar
import com.rescueauth.v2.ui.model.AccountUi
import com.rescueauth.v2.ui.screens.authenticator.AccountAddKind
import com.rescueauth.v2.ui.screens.authenticator.AccountAddSheet
import com.rescueauth.v2.ui.screens.authenticator.AuthenticatorScreen
import com.rescueauth.v2.ui.screens.authenticator.ManagementDestructiveDialog
import com.rescueauth.v2.ui.screens.authenticator.ManagementTextDialog
import com.rescueauth.v2.ui.screens.authenticator.MergeAccountDialog
import com.rescueauth.v2.ui.screens.authenticator.MigrationImportSheet
import com.rescueauth.v2.ui.screens.authenticator.ProviderPickerDialog
import com.rescueauth.v2.ui.screens.authenticator.QrScannerScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Non-sensitive presentation state can be owned by the shell without moving form/domain logic. */
@Stable
class AuthenticatorAddState {
    var credentialSheetVisible by mutableStateOf(false)
    var providerDialogVisible by mutableStateOf(false)
}

/**
 * Authenticator route — wires the production ViewModel, the shared 1s countdown
 * tick, the Android clipboard, haptic feedback, and the unified Snackbar host.
 */
@Composable
fun AuthenticatorRoute(
    modifier: Modifier = Modifier,
    onOpenAccount: ((String) -> Unit)? = null,
    onOpenProvider: ((String) -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    onOpenRecovery: ((String) -> Unit)? = null,
    onAddRecovery: ((String) -> Unit)? = null,
    providerName: String? = null,
    accountId: String? = null,
    onOpenSearch: (() -> Unit)? = null,
    searchFieldModifier: Modifier = Modifier,
    viewModel: AuthenticatorViewModel? = null,
    addState: AuthenticatorAddState = remember { AuthenticatorAddState() },
    showAddAction: Boolean = true,
    contentBottomPadding: Dp = 0.dp,
    floatingAddPosition: FloatingAddPosition = rememberFloatingAddPosition(),
    isActive: Boolean = true,
    addViewModel: AccountAddViewModel? = null,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val appScope = rememberCoroutineScope()
    val shellSnackbar = LocalRescueAuthSnackbarHostState.current
    val localSnackbarHostState = remember { SnackbarHostState() }
    val snackbarHostState = shellSnackbar ?: localSnackbarHostState

    // ---- Provider & Account management dialog state -----------
    var providerToRename by remember { mutableStateOf<String?>(null) }
    var providerToDelete by remember { mutableStateOf<String?>(null) }
    var accountToRename by remember { mutableStateOf<AccountUi?>(null) }
    var moveState by remember { mutableStateOf<Pair<AccountUi, List<String>>?>(null) }
    var accountToMerge by remember { mutableStateOf<AccountUi?>(null) }
    var mergeDestination by remember { mutableStateOf<AccountUi?>(null) }
    var cameraScanError by remember { mutableStateOf<String?>(null) }

    val sessionState: kotlinx.coroutines.flow.StateFlow<SecureSessionStateMachine.State> = remember {
        val sm = VaultAccess.sessionManager
        if (sm != null) sm.sessionStateFlow else SecureSessionStateMachine().state
    }
    val viewModel = viewModel ?: remember {
        AuthenticatorViewModel(
            repositoryProvider = { VaultAccess.authenticatorRepository() },
            recoveryRepositoryProvider = { VaultAccess.recoveryRepository() },
            managementRepositoryProvider = { VaultAccess.providerAccountRepository() },
            sessionState = sessionState,
            clock = AuthenticatorViewModel.Clock { System.currentTimeMillis() / 1000L },
            scope = appScope,
        )
    }

    val uiState by viewModel.uiState.collectAsState()
    val events by viewModel.events.collectAsState()
    val migrationState by viewModel.migrationState.collectAsState()
    val accountAddViewModel = addViewModel ?: remember(sessionState) {
        AccountAddViewModel(
            { VaultAccess.providerAccountRepository() },
            sessionState,
            appScope,
            defaultRecoveryTitleProvider = { context.getString(R.string.recovery_codes_title) },
        )
    }
    val accountAddForm by accountAddViewModel.formState.collectAsState()
    var accountScannerVisible by remember { mutableStateOf(false) }

    val targets = uiState.accounts.map { it.copy(totpCredentials = emptyList(), recoverySets = emptyList()) }
    LaunchedEffect(targets) { accountAddViewModel.setAccounts(targets) }
    DisposableEffect(isActive) {
        if (!isActive) {
            accountAddViewModel.dismiss()
            accountScannerVisible = false
            cameraScanError = null
        }
        onDispose { accountAddViewModel.dismiss() }
    }
    LaunchedEffect(isActive, addState.credentialSheetVisible, addState.providerDialogVisible) {
        if (isActive) {
            if (addState.providerDialogVisible) accountAddViewModel.beginAtHome(AccountAddContentKind.NONE)
            else if (addState.credentialSheetVisible) accountAddViewModel.beginAtHome()
        }
        addState.providerDialogVisible = false
        addState.credentialSheetVisible = false
    }
    LaunchedEffect(uiState.accounts, accountAddForm.visible, accountAddForm.selectedAccountId) {
        if (accountAddForm.visible && accountAddForm.scope != AccountAddScope.VAULT && !uiState.loading) {
            val candidates = uiState.accounts.filter { it.providerName == accountAddForm.provider }
            if (candidates.isEmpty() || (accountAddForm.selectedAccountId != null && candidates.none { it.id == accountAddForm.selectedAccountId })) {
                accountAddViewModel.dismiss()
                accountScannerVisible = false
            }
        }
    }

    LaunchedEffect(cameraScanError) {
        if (cameraScanError != null) {
            delay(3_500L)
            cameraScanError = null
        }
    }

    @Suppress("DEPRECATION")
    val lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel, isActive) {
        if (!isActive) return@DisposableEffect onDispose { }
        val tickScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var tickJob: Job? = null
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME -> {
                    viewModel.onTick()
                    if (tickJob == null || !tickJob!!.isActive) {
                        tickJob = tickScope.launch {
                            while (true) {
                                delay(1_000L)
                                viewModel.onTick()
                            }
                        }
                    }
                }
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    tickJob?.cancel()
                    tickJob = null
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            tickJob?.cancel()
            tickScope.cancel()
        }
    }

    // Consume event immediately before suspending on showSnackbar so repeat taps
    // work within the snackbar window and back-navigation never re-fires events.
    LaunchedEffect(events, isActive) {
        if (!isActive) return@LaunchedEffect
        val event = events ?: return@LaunchedEffect
        viewModel.onEventShown()
        appScope.launch {
            when (event) {
                is AuthenticatorEvent.CopyCode -> {
                    copyToClipboard(context, event.code)
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    snackbarHostState.showInstantSnackbar(context.getString(R.string.totp_code_copied))
                }
                is AuthenticatorEvent.OpenAccount -> {
                    onOpenAccount?.invoke(event.accountId)
                }
                is AuthenticatorEvent.Deleted -> {
                    val message = context.getString(R.string.undo_snackbar_message, event.label)
                    val actionLabel = context.getString(R.string.undo_snackbar_action)
                    val result = UndoSnackbarContract.showUndoSnackbar(
                        snackbarHostState,
                        message,
                        actionLabel,
                    )
                    if (result == UndoResult.UNDO) {
                        viewModel.undoDelete()
                    }
                }
                is AuthenticatorEvent.Restored -> {
                    snackbarHostState.showInstantSnackbar(context.getString(R.string.totp_restored_message))
                }
                is AuthenticatorEvent.Added -> {
                    snackbarHostState.showInstantSnackbar(context.getString(R.string.totp_added_message))
                }
                is AuthenticatorEvent.MigrationImported -> {
                    snackbarHostState.showInstantSnackbar(
                        context.getString(
                            R.string.migration_imported_message,
                            event.imported,
                            event.duplicates,
                        ),
                    )
                }
                is AuthenticatorEvent.ScanError -> {
                    val message = when (event.messageKey) {
                        "scan_error_not_supported" -> context.getString(R.string.scan_error_not_supported)
                        "scan_error_malformed" -> context.getString(R.string.scan_error_malformed)
                        "scan_error_malformed_migration" -> context.getString(R.string.scan_error_malformed_migration)
                        "scan_error_incomplete_batch" -> context.getString(R.string.scan_error_incomplete_batch)
                        "scan_error_batch_conflict" -> context.getString(
                            R.string.scan_error_batch_conflict,
                            context.getString(R.string.common_unknown),
                        )
                        else -> context.getString(R.string.scan_error_malformed)
                    }
                    if (migrationState.scannerVisible || accountScannerVisible) {
                        cameraScanError = message
                    } else {
                        snackbarHostState.showInstantSnackbar(message)
                    }
                }
                is AuthenticatorEvent.ManagementMessage -> {
                    snackbarHostState.showInstantSnackbar(resolveManagementMessage(context, event.message))
                }
                is AuthenticatorEvent.ManagementError -> {
                    snackbarHostState.showInstantSnackbar(resolveManagementError(context, event.message))
                }
                is AuthenticatorEvent.AccountDeleted -> {
                    val message = context.getString(R.string.account_deleted_message)
                    val actionLabel = context.getString(R.string.undo_snackbar_action)
                    val result = UndoSnackbarContract.showUndoSnackbar(
                        snackbarHostState,
                        message,
                        actionLabel,
                    )
                    if (result == UndoResult.UNDO) {
                        viewModel.undoDeleteAccount()
                    }
                }
                is AuthenticatorEvent.AccountRestored -> {
                    snackbarHostState.showInstantSnackbar(context.getString(R.string.account_restored_message))
                }
                is AuthenticatorEvent.AccountRestoreBlocked -> {
                    snackbarHostState.showInstantSnackbar(context.getString(R.string.account_restore_blocked))
                }
            }
        }
    }

    AuthenticatorScreen(
        uiState = uiState,
        snackbarHostState = if (shellSnackbar == null) localSnackbarHostState else null,
        onAddClick = {
            val account = uiState.providers.flatMap { it.accounts }.firstOrNull { it.id == accountId }
            if (account != null) accountAddViewModel.begin(account.providerName, account, AccountAddContentKind.TOTP)
            else if (providerName != null) accountAddViewModel.begin(providerName, kind = AccountAddContentKind.TOTP)
            else accountAddViewModel.beginAtHome()
        },
        onAddCredentialToAccount = { targetId ->
            val target = uiState.providers.flatMap { it.accounts }.firstOrNull { it.id == targetId }
            if (target != null && (providerName == null || target.providerName == providerName)) {
                accountAddViewModel.begin(target.providerName, target, AccountAddContentKind.TOTP)
            }
        },
        onCopyClick = { viewModel.copyCode(it) },
        onDeleteClick = { appScope.launch { viewModel.deleteCard(it) } },
        onOpenAccount = onOpenAccount?.let { cb -> { id -> cb(id) } },
        onOpenProvider = onOpenProvider,
        onBack = onBack,
        onOpenRecovery = onOpenRecovery,
        onAddRecovery = onAddRecovery,
        initialProviderName = providerName,
        initialAccountId = accountId,
        onOpenSearch = onOpenSearch,
        searchFieldModifier = searchFieldModifier,
        onTogglePin = { account -> appScope.launch { viewModel.togglePin(account.id) } },
        onAddProviderClick = { addState.providerDialogVisible = true },
        onRenameProvider = { provider -> providerToRename = provider },
        onDeleteProvider = { provider -> providerToDelete = provider },
        onAddAccount = { provider -> accountAddViewModel.begin(provider) },
        onCreateAccountFor = { provider, kind ->
            accountAddViewModel.begin(
                provider,
                kind = if (kind == AccountAddKind.RECOVERY) AccountAddContentKind.RECOVERY else AccountAddContentKind.TOTP,
            )
        },
        onOpenAdd = { provider, selectedId ->
            accountAddViewModel.begin(provider, uiState.accounts.firstOrNull { it.id == selectedId })
        },
        onRenameAccount = { account -> accountToRename = account },
        onMoveAccount = { account ->
            appScope.launch {
                val others = viewModel.availableProviders().filter { it != account.providerName }
                if (others.isEmpty()) {
                    snackbarHostState.showInstantSnackbar(context.getString(R.string.account_move_no_other_providers))
                } else {
                    moveState = account to others
                }
            }
        },
        onMergeAccount = { account ->
            val candidates = uiState.accounts
                .filter { it.id != account.id }
                .sortedWith(
                    compareByDescending<AccountUi> { it.providerName == account.providerName }
                        .thenBy { it.providerName }
                        .thenBy { it.accountName },
                )
            if (candidates.isEmpty()) {
                appScope.launch {
                    snackbarHostState.showInstantSnackbar(context.getString(R.string.account_merge_no_other_accounts))
                }
            } else {
                accountToMerge = account
                mergeDestination = candidates.first()
            }
        },
        onDeleteAccount = { account ->
            appScope.launch { viewModel.deleteAccount(account.id) }
        },
        onSetProviderIcon = { provider, iconKey ->
            appScope.launch { viewModel.setProviderIcon(provider, iconKey) }
        },
        modifier = modifier,
        showAddAction = showAddAction && !migrationState.scannerVisible && !accountScannerVisible,
        contentBottomPadding = contentBottomPadding,
        floatingAddPosition = floatingAddPosition,
    )

    if (!isActive) return

    if (accountAddForm.visible && !accountScannerVisible) {
        val addedFeedbackRes = when (accountAddForm.kind) {
            AccountAddContentKind.TOTP -> R.string.totp_added_message
            AccountAddContentKind.RECOVERY -> R.string.recovery_codes_added_message
            AccountAddContentKind.NONE -> R.string.management_account_added
        }
        AccountAddSheet(
            form = accountAddForm,
            accounts = targets,
            allowAccountSelection = accountId == null,
            onDismiss = { accountAddViewModel.dismiss() },
            onAccountSelected = accountAddViewModel::selectAccount,
            onAccountNameChange = accountAddViewModel::onAccountNameChange,
            onProviderChange = accountAddViewModel::onProviderChange,
            onKindChange = accountAddViewModel::selectKind,
            onTotpChange = accountAddViewModel::updateTotp,
            onRecoveryTitleChange = accountAddViewModel::onRecoveryTitleChange,
            onRecoveryValuesChange = accountAddViewModel::onRecoveryValuesChange,
            onStartScan = {
                cameraScanError = null
                accountScannerVisible = true
            },
            onSubmit = {
                appScope.launch {
                    if (accountAddViewModel.submit()) {
                        snackbarHostState.showInstantSnackbar(context.getString(addedFeedbackRes))
                    }
                }
            },
        )
    }

    val batchStatus = migrationState.batchProgress?.let { (collected, total) ->
        context.getString(R.string.migration_preview_batch_progress, collected, total)
    }
    if (accountScannerVisible && accountAddForm.visible) {
        QrScannerScreen(
            onQrDetected = { raw ->
                handleAccountAddScan(raw, accountAddViewModel, viewModel) {
                    cameraScanError = null
                    accountScannerVisible = false
                }
            },
            onDismiss = {
                cameraScanError = null
                accountScannerVisible = false
            },
            statusMessage = batchStatus,
            errorMessage = cameraScanError,
        )
    }

    if (migrationState.scannerVisible) {
        QrScannerScreen(
            onQrDetected = { raw ->
                viewModel.onQrScanned(raw)
            },
            onDismiss = {
                cameraScanError = null
                viewModel.closeScanner()
            },
            statusMessage = batchStatus,
            errorMessage = cameraScanError,
        )
    }

    if (migrationState.isPreviewVisible || migrationState.result != null) {
        MigrationImportSheet(
            state = migrationState,
            onConfirm = { appScope.launch { viewModel.confirmMigrationImport() } },
            onDismiss = { viewModel.dismissMigrationPreview() },
        )
    }

    providerToRename?.let { provider ->
        ManagementTextDialog(
            title = context.getString(R.string.provider_rename_title),
            fieldLabel = context.getString(R.string.provider_new_name_label),
            initialValue = provider,
            confirmLabel = context.getString(R.string.management_rename),
            onConfirm = { newName ->
                providerToRename = null
                if (newName.isNotBlank()) {
                    appScope.launch { viewModel.renameProvider(provider, newName) }
                }
            },
            onDismiss = { providerToRename = null },
        )
    }

    providerToDelete?.let { provider ->
        val providerAccounts = uiState.accounts.filter { it.providerName == provider }
        val totpCount = providerAccounts.sumOf { it.totpCredentials.size }
        val setCount = providerAccounts.sumOf { it.recoverySets.size }
        ManagementDestructiveDialog(
            title = context.getString(R.string.provider_delete_title, provider),
            message = context.getString(
                R.string.provider_delete_message,
                providerAccounts.size,
                totpCount,
                setCount,
            ),
            confirmLabel = context.getString(R.string.provider_delete),
            onConfirm = {
                val p = providerToDelete
                providerToDelete = null
                if (p != null) {
                    appScope.launch {
                        val deleted = viewModel.deleteProvider(p)
                        if (deleted != null && providerName == p) {
                            onBack?.invoke()
                        }
                    }
                }
            },
            onDismiss = { providerToDelete = null },
        )
    }

    accountToRename?.let { account ->
        ManagementTextDialog(
            title = context.getString(R.string.account_rename_title),
            fieldLabel = context.getString(R.string.account_name_label),
            initialValue = account.accountName,
            confirmLabel = context.getString(R.string.management_rename),
            onConfirm = { newName ->
                val a = accountToRename
                accountToRename = null
                if (a != null && newName.isNotBlank()) {
                    appScope.launch { viewModel.renameAccount(a.id, newName) }
                }
            },
            onDismiss = { accountToRename = null },
        )
    }

    moveState?.let { (account, otherProviders) ->
        ProviderPickerDialog(
            title = context.getString(R.string.account_move_title),
            providers = otherProviders,
            initialProvider = otherProviders.first(),
            onConfirm = { destProvider ->
                moveState = null
                appScope.launch { viewModel.moveAccount(account.id, destProvider) }
            },
            onDismiss = { moveState = null },
        )
    }

    accountToMerge?.let { source ->
        val candidates = uiState.accounts
            .filter { it.id != source.id }
            .sortedWith(
                compareByDescending<AccountUi> { it.providerName == source.providerName }
                    .thenBy { it.providerName }
                    .thenBy { it.accountName },
            )
        val dest = mergeDestination?.takeIf { sel -> candidates.any { it.id == sel.id } } ?: candidates.firstOrNull()
        if (dest != null) {
            MergeAccountDialog(
                source = source,
                destination = dest,
                candidates = candidates,
                onSelectDestination = { mergeDestination = it },
                onConfirm = {
                    val s = accountToMerge
                    accountToMerge = null
                    mergeDestination = null
                    if (s != null) {
                        appScope.launch {
                            val ok = viewModel.mergeAccounts(s.id, dest.id)
                            if (ok && accountId == s.id) {
                                if (onOpenAccount != null) onOpenAccount(dest.id) else onBack?.invoke()
                            }
                        }
                    }
                },
                onDismiss = {
                    accountToMerge = null
                    mergeDestination = null
                },
            )
        } else {
            accountToMerge = null
            mergeDestination = null
        }
    }
}

private fun resolveManagementMessage(context: Context, code: String): String = when (code) {
    "provider_created" -> context.getString(R.string.management_provider_created)
    "account_added" -> context.getString(R.string.management_account_added)
    "provider_renamed" -> context.getString(R.string.management_provider_renamed)
    "provider_deleted" -> context.getString(R.string.management_provider_deleted)
    "account_renamed" -> context.getString(R.string.management_account_renamed)
    "account_moved" -> context.getString(R.string.management_account_moved)
    "accounts_merged" -> context.getString(R.string.management_accounts_merged)
    else -> code
}

private fun resolveManagementError(context: Context, code: String): String = when (code) {
    "provider_create_failed" -> context.getString(R.string.management_provider_create_failed)
    "account_add_failed" -> context.getString(R.string.management_account_add_failed)
    "provider_rename_failed" -> context.getString(R.string.management_provider_rename_failed)
    "icon_update_failed" -> context.getString(R.string.management_icon_update_failed)
    "provider_delete_failed" -> context.getString(R.string.management_provider_delete_failed)
    "account_rename_failed" -> context.getString(R.string.management_account_rename_failed)
    "account_move_failed" -> context.getString(R.string.management_account_move_failed)
    "accounts_merge_failed" -> context.getString(R.string.management_accounts_merge_failed)
    "account_delete_failed" -> context.getString(R.string.management_account_delete_failed)
    else -> context.getString(R.string.common_error_title)
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("TOTP code", text))
}
