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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.rescueauth.v2.R
import com.rescueauth.v2.repository.VaultAccess
import com.rescueauth.v2.session.SecureSessionStateMachine
import com.rescueauth.v2.ui.components.UndoResult
import com.rescueauth.v2.ui.components.UndoSnackbarContract
import com.rescueauth.v2.ui.model.AccountUi
import com.rescueauth.v2.ui.screens.authenticator.AddTotpSheet
import com.rescueauth.v2.ui.screens.authenticator.AuthenticatorScreen
import com.rescueauth.v2.ui.screens.authenticator.CreateProviderDialog
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Authenticator route (Phase 4 P1) — wires the production ViewModel, the
 * shared 1s countdown tick, the Android clipboard and the Undo Snackbar into
 * the presentational screen, and hosts the Add TOTP sheet.
 *
 * Android-only side effects (clipboard write, snackbar action mapping) live
 * here — never inside the ViewModel or the screen.
 */
@Composable
fun AuthenticatorRoute(
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    onOpenAccount: ((String) -> Unit)? = null,
    onOpenProvider: ((String) -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    onOpenRecovery: ((String) -> Unit)? = null,
    providerName: String? = null,
    accountId: String? = null,
    onOpenSearch: (() -> Unit)? = null,
    // Injected by the app shell (RescueAuthApp) so the ViewModel + Room
    // collection survive bottom-tab switches and render instantly on re-entry
    // (Issue #70 "每次进入认证器都要加载一段时间"). When null (tests / standalone
    // previews) the route falls back to creating its own instance.
    viewModel: AuthenticatorViewModel? = null,
) {
    val context = LocalContext.current
    val appScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showAddSheet by remember { mutableStateOf(false) }

    // ---- Provider & Account management dialog state (Phase 4) -----------
    var showAddProvider by remember { mutableStateOf(false) }
    var providerToRename by remember { mutableStateOf<String?>(null) }
    var providerToDelete by remember { mutableStateOf<String?>(null) }
    var providerToAddAccount by remember { mutableStateOf<String?>(null) }
    var accountToRename by remember { mutableStateOf<AccountUi?>(null) }
    var accountToMove by remember { mutableStateOf<AccountUi?>(null) }
    var accountToMerge by remember { mutableStateOf<AccountUi?>(null) }
    var mergeDestination by remember { mutableStateOf<AccountUi?>(null) }
    var providerList by remember { mutableStateOf<List<String>>(emptyList()) }

    val sessionState: kotlinx.coroutines.flow.StateFlow<SecureSessionStateMachine.State> = remember {
        val sm = VaultAccess.sessionManager
        if (sm != null) {
            sm.sessionStateFlow
        } else {
            SecureSessionStateMachine().state
        }
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
    val formState by viewModel.formState.collectAsState()
    val events by viewModel.events.collectAsState()
    val migrationState by viewModel.migrationState.collectAsState()

    // Shared 1s tick: wall-clock based, driven from a background scope so it
    // never holds the main-looper / compose idling system busy. It only runs
    // while the lifecycle is at least STARTED (pauses when the app backgrounds
    // or the route leaves composition), and the ViewModel corrects from the
    // real wall clock on every tick — a missed/paused tick never drifts.
    val lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current
    val tickScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    DisposableEffect(lifecycleOwner) {
        var tickJob: Job? = null
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME -> {
                    viewModel.onTick() // immediate wall-clock correction
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

    // One-shot event handling (clipboard + snackbar).
    LaunchedEffect(events) {
        val event = events ?: return@LaunchedEffect
        when (event) {
            is AuthenticatorEvent.CopyCode -> {
                copyToClipboard(context, event.code)
                snackbarHostState.showSnackbar(
                    message = context.getString(R.string.totp_code_copied),
                )
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
                snackbarHostState.showSnackbar(
                    message = context.getString(R.string.totp_restored_message),
                )
            }
            is AuthenticatorEvent.Added -> {
                snackbarHostState.showSnackbar(
                    message = context.getString(R.string.totp_added_message),
                )
            }
            is AuthenticatorEvent.MigrationImported -> {
                snackbarHostState.showSnackbar(
                    message = context.getString(
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
                    "scan_error_batch_conflict" -> context.getString(R.string.scan_error_batch_conflict)
                    else -> context.getString(R.string.scan_error_malformed)
                }
                snackbarHostState.showSnackbar(message)
            }
            is AuthenticatorEvent.ManagementMessage -> {
                snackbarHostState.showSnackbar(event.message)
            }
            is AuthenticatorEvent.ManagementError -> {
                snackbarHostState.showSnackbar(event.message)
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
                snackbarHostState.showSnackbar(
                    context.getString(R.string.account_restored_message),
                )
            }
            is AuthenticatorEvent.AccountRestoreBlocked -> {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.account_restore_blocked),
                )
            }
        }
        viewModel.onEventShown()
    }

    AuthenticatorScreen(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onAddClick = { showAddSheet = true },
        onCopyClick = { viewModel.copyCode(it) },
        onDeleteClick = { appScope.launch { viewModel.deleteCard(it) } },
        onOpenAccount = onOpenAccount?.let { cb -> { accountId -> cb(accountId) } },
        onOpenProvider = onOpenProvider,
        onBack = onBack,
        onOpenRecovery = onOpenRecovery,
        initialProviderName = providerName,
        initialAccountId = accountId,
        onOpenSearch = onOpenSearch,
        onTogglePin = { account -> appScope.launch { viewModel.togglePin(account.id) } },
        onAddProviderClick = { showAddProvider = true },
        onRenameProvider = { provider -> providerToRename = provider },
        onDeleteProvider = { provider -> providerToDelete = provider },
        onAddAccount = { provider -> providerToAddAccount = provider },
        onRenameAccount = { account -> accountToRename = account },
        onMoveAccount = { account ->
            accountToMove = account
            appScope.launch { providerList = viewModel.availableProviders() }
        },
        onMergeAccount = { account ->
            accountToMerge = account
            mergeDestination = null
        },
        // P8 §11: Account delete is now an ordinary immediate-delete + Undo
        // (no destructive confirmation). The confirmation-only path remains for
        // Provider cascading delete and Account merge.
        onDeleteAccount = { account ->
            appScope.launch { viewModel.deleteAccount(account.id) }
        },
        onSetProviderIcon = { provider, iconKey ->
            appScope.launch { viewModel.setProviderIcon(provider, iconKey) }
        },
        modifier = modifier,
    )

    if (showAddSheet) {
        AddTotpSheet(
            form = formState,
            onDismiss = { showAddSheet = false },
            onModeChange = { viewModel.setMode(it) },
            onStartScan = {
                showAddSheet = false
                viewModel.openScanner()
            },
            onUriChange = { viewModel.onUriChange(it) },
            onProviderChange = { viewModel.onProviderChange(it) },
            onAccountNameChange = { viewModel.onAccountNameChange(it) },
            onSecretChange = { viewModel.onSecretChange(it) },
            onAlgorithmChange = { viewModel.onAlgorithmChange(it) },
            onDigitsChange = { viewModel.onDigitsChange(it) },
            onPeriodChange = { viewModel.onPeriodChange(it) },
            onSubmit = { appScope.launch { viewModel.submitAdd() } },
        )
    }

    // Full-screen camera QR scanner (Phase 4 P2).
    if (migrationState.scannerVisible) {
        QrScannerScreen(
            onQrDetected = { raw ->
                viewModel.onQrScanned(raw)
            },
            onDismiss = { viewModel.closeScanner() },
        )
    }

    // Migration preview / result (Compose confirmation state, not camera).
    if (migrationState.isPreviewVisible || migrationState.result != null) {
        MigrationImportSheet(
            state = migrationState,
            onConfirm = { appScope.launch { viewModel.confirmMigrationImport() } },
            onDismiss = { viewModel.dismissMigrationPreview() },
        )
    }

    // ---- Provider & Account management dialogs (Phase 4) -----------------
    if (showAddProvider) {
        CreateProviderDialog(
            onConfirm = { provider, accountName ->
                showAddProvider = false
                if (provider.isNotBlank() && accountName.isNotBlank()) {
                    // Current model requires a first Account to persist a
                    // Provider (no empty Provider support).
                    appScope.launch { viewModel.createProvider(provider, accountName) }
                }
            },
            onDismiss = { showAddProvider = false },
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
                if (p != null) appScope.launch { viewModel.deleteProvider(p) }
            },
            onDismiss = { providerToDelete = null },
        )
    }

    providerToAddAccount?.let { provider ->
        ManagementTextDialog(
            title = context.getString(R.string.account_add_title),
            fieldLabel = context.getString(R.string.account_name_label),
            confirmLabel = context.getString(R.string.management_confirm),
            onConfirm = { accountName ->
                providerToAddAccount = null
                if (accountName.isNotBlank()) {
                    appScope.launch { viewModel.createAccount(provider, accountName) }
                }
            },
            onDismiss = { providerToAddAccount = null },
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

    accountToMove?.let { account ->
        val otherProviders = providerList.filter { it != account.providerName }
        if (otherProviders.isNotEmpty()) {
            ProviderPickerDialog(
                title = context.getString(R.string.account_move_title),
                providers = otherProviders,
                initialProvider = otherProviders.first(),
                onConfirm = { destProvider ->
                    val a = accountToMove
                    accountToMove = null
                    if (a != null) appScope.launch { viewModel.moveAccount(a.id, destProvider) }
                },
                onDismiss = { accountToMove = null },
            )
        } else {
            LaunchedEffect(accountToMove) {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.account_move_destination) + ": none available",
                )
                accountToMove = null
            }
        }
    }

    accountToMerge?.let { source ->
        val candidates = uiState.accounts.filter { it.id != source.id }
        if (candidates.isNotEmpty()) {
            if (mergeDestination == null) {
                val first = candidates.first()
                LaunchedEffect(Unit) { mergeDestination = first }
            }
            mergeDestination?.let { dest ->
                MergeAccountDialog(
                    source = source,
                    destination = dest,
                    onConfirm = {
                        val s = accountToMerge
                        accountToMerge = null
                        mergeDestination = null
                        if (s != null) appScope.launch { viewModel.mergeAccounts(s.id, dest.id) }
                    },
                    onDismiss = {
                        accountToMerge = null
                        mergeDestination = null
                    },
                )
            }
        } else {
            LaunchedEffect(accountToMerge) {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.account_merge) + ": create another account first",
                )
                accountToMerge = null
            }
        }
    }

}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("TOTP code", text))
}
