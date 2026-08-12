package com.rescueauth.v2.ui.authenticator

import com.rescueauth.v2.domain.AuthAccount
import com.rescueauth.v2.domain.TotpCredential
import com.rescueauth.v2.migration.MigrationBatchSession
import com.rescueauth.v2.migration.MigrationEntryStatus
import com.rescueauth.v2.migration.MigrationPayloadParser
import com.rescueauth.v2.migration.MigrationTotpCandidate
import com.rescueauth.v2.repository.AuthenticatorRepository
import com.rescueauth.v2.repository.ProviderAccountRepository
import com.rescueauth.v2.repository.TotpImportItem
import com.rescueauth.v2.scanner.ScannerResultRouter
import com.rescueauth.v2.session.SecureSessionStateMachine
import com.rescueauth.v2.totp.TotpCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI state of the Authenticator home screen.
 */
data class AuthenticatorUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val totpCards: List<TotpCardUi> = emptyList(),
    /** Provider/Account grouping (Phase 4 P3) — drives the account list UI. */
    val accounts: List<com.rescueauth.v2.ui.model.AccountUi> = emptyList(),
    /** Provider-grouped view (Phase 4 — Provider/Account Full Management). */
    val providers: List<com.rescueauth.v2.ui.model.ProviderUi> = emptyList(),
) {
    val isEmpty: Boolean get() = !loading && error == null && totpCards.isEmpty() && accounts.isEmpty() && providers.isEmpty()
}

/**
 * One displayed TOTP row. **No secret is ever present** — the card only shows
 * display metadata plus the currently generated code and countdown values.
 */
data class TotpCardUi(
    val credentialId: String,
    val stableId: String,
    val accountId: String,
    val issuer: String,
    val accountName: String,
    val algorithm: String,
    val digits: Int,
    val periodSeconds: Int,
    val currentCode: String,
    val remainingSeconds: Int,
    val progressFraction: Float,
)

/** Form input for the "Add TOTP" flow. */
data class AddTotpFormState(
    val mode: AddMode = AddMode.PASTE,
    val uri: String = "",
    val provider: String = "",
    val accountName: String = "",
    val secret: String = "",
    val algorithm: String = TotpCore.DEFAULT_ALGORITHM,
    val digits: Int = TotpCore.DEFAULT_DIGITS,
    val periodSeconds: Int = TotpCore.DEFAULT_PERIOD_SECONDS,
    val submitting: Boolean = false,
    val error: String? = null,
) {
    val isPaste: Boolean get() = mode == AddMode.PASTE
}

enum class AddMode { SCAN, PASTE, MANUAL }

/** UI state of the otpauth-migration import flow (Phase 4 P2). */
data class MigrationImportUiState(
    /** Full-screen camera open (add flow Scan QR). */
    val scannerVisible: Boolean = false,
    /** Multi-QR collection progress (collected / total), when active. */
    val batchProgress: Pair<Int, Int>? = null,
    /** Fully collected migration candidates awaiting confirmation. */
    val candidates: List<MigrationTotpCandidate> = emptyList(),
    /** Counts reported after a completed import (null = not yet imported). */
    val result: MigrationImportResultUi? = null,
    /** Import in flight. */
    val importing: Boolean = false,
    /** User-facing scan/import error (never a raw exception / secret). */
    val error: String? = null,
) {
    val isPreviewVisible: Boolean get() = candidates.isNotEmpty()
}

/** Aggregated import result shown to the user. */
data class MigrationImportResultUi(
    val imported: Int,
    val duplicates: Int,
    val unsupported: Int,
    val invalid: Int,
)

/**
 * One-shot user events for the Authenticator screen. Android-only side effects
 * (clipboard write, snackbar) are triggered from the Compose layer.
 */
sealed interface AuthenticatorEvent {
    /** Copy the given code to the Android clipboard + show "copied" snackbar. */
    data class CopyCode(val code: String, val label: String) : AuthenticatorEvent

    /** The user opened an account detail destination (Phase 4 P3). */
    data class OpenAccount(val accountId: String) : AuthenticatorEvent

    /** A credential was deleted; show a snackbar with an Undo action. */
    data class Deleted(val label: String, val credentialId: String) : AuthenticatorEvent

    /** A credential was restored via Undo. */
    data class Restored(val label: String) : AuthenticatorEvent

    /** A new TOTP credential was added. */
    data class Added(val label: String) : AuthenticatorEvent

    /** A migration batch was imported. */
    data class MigrationImported(val imported: Int, val duplicates: Int) : AuthenticatorEvent

    /** A scanned QR could not be used (message resource key). */
    data class ScanError(val messageKey: String) : AuthenticatorEvent

    /** A hierarchy management operation completed; show a snackbar message. */
    data class ManagementMessage(val message: String) : AuthenticatorEvent

    /** A hierarchy management operation failed; show an error message. */
    data class ManagementError(val message: String) : AuthenticatorEvent

    /** P8 §11 — an Account was deleted; show a Snackbar with an Undo action. */
    data class AccountDeleted(val label: String) : AuthenticatorEvent

    /** P8 §11 — an Account was restored via Undo. */
    data class AccountRestored(val label: String) : AuthenticatorEvent

    /** P8 §10 — an Account Undo restore was blocked because the vault changed. */
    data class AccountRestoreBlocked(val label: String) : AuthenticatorEvent
}

/**
 * ViewModel for the production Authenticator vertical slice (Phase 4 P1).
 *
 * - Combines the real repository Flows (accounts + credentials) with a shared
 *   countdown tick into the UI list; the tick is driven by the wall clock.
 * - Re-collects when the session transitions to UNLOCKED (the production
 *   database handle may change across lock/unlock), and clears while locked.
 * - Add TOTP (Paste URI / Manual Entry) through the repository.
 * - Delete with Snackbar Undo that **really restores the DB row** (original
 *   stableId + content).
 *
 * Plain class (not androidx ViewModel) so it is trivially testable on the JVM;
 * the Compose layer provides the [scope].
 */
class AuthenticatorViewModel(
    private val repositoryProvider: () -> AuthenticatorRepository?,
    private val recoveryRepositoryProvider: () -> com.rescueauth.v2.repository.RecoveryCodeRepository? = { null },
    private val managementRepositoryProvider: () -> ProviderAccountRepository? = { null },
    sessionState: StateFlow<SecureSessionStateMachine.State>,
    private val clock: Clock,
    private val scope: CoroutineScope,
) {

    /** Minimal wall-clock abstraction so tests can advance time deterministically. */
    fun interface Clock {
        fun currentTimeSeconds(): Long
    }

    private val _uiState = MutableStateFlow(AuthenticatorUiState())
    val uiState: StateFlow<AuthenticatorUiState> = _uiState.asStateFlow()

    private val _formState = MutableStateFlow(AddTotpFormState())
    val formState: StateFlow<AddTotpFormState> = _formState.asStateFlow()

    private val _events = MutableStateFlow<AuthenticatorEvent?>(null)
    val events: StateFlow<AuthenticatorEvent?> = _events.asStateFlow()

    private val _migrationState = MutableStateFlow(MigrationImportUiState())
    val migrationState: StateFlow<MigrationImportUiState> = _migrationState.asStateFlow()

    private val batchSession = MigrationBatchSession()

    private val tickSeconds = MutableStateFlow(clock.currentTimeSeconds())

    private var collectionJob: Job? = null
    private var currentRepo: AuthenticatorRepository? = null

    /** Latest credentials keyed by credentialId — needed to restore on Undo. */
    private val credentialsById = mutableMapOf<String, TotpCredential>()

    init {
        scope.launch {
            sessionState.collect { state ->
                if (state == SecureSessionStateMachine.State.UNLOCKED) {
                    val repo = repositoryProvider()
                    if (repo != null && repo !== currentRepo) {
                        collectionJob?.cancel()
                        collectionJob = scope.launch { collectCards(repo) }
                        currentRepo = repo
                    }
                } else {
                    collectionJob?.cancel()
                    collectionJob = null
                    currentRepo = null
                    // P8 §6: session lock clears every pending Undo payload
                    // (TOTP secret / recovery plaintext / account subtree). The
                    // Undo token is NOT restored after unlock.
                    pendingUndo = null
                    pendingAccountUndo = null
                    _uiState.value = AuthenticatorUiState(loading = false)
                }
            }
        }
    }

    private suspend fun collectCards(repo: AuthenticatorRepository) {
        val recoveryFlow = recoveryRepositoryProvider()?.observeAllSets()
            ?: kotlinx.coroutines.flow.flowOf(emptyList())
        combine(repo.observeAccounts(), repo.observeTotpCredentials(), recoveryFlow, tickSeconds) {
            accounts, creds, sets, now ->
            credentialsById.clear()
            creds.forEach { credentialsById[it.id] = it }
            buildCards(accounts, creds, sets, now)
        }.collectLatest { state -> _uiState.value = state }
    }

    private fun buildCards(
        accounts: List<AuthAccount>,
        creds: List<TotpCredential>,
        recoverySets: List<com.rescueauth.v2.domain.RecoveryCodeSet>,
        now: Long,
    ): AuthenticatorUiState {
        val accountById = accounts.associateBy { it.id }
        val cards = creds.map { c ->
            val account = accountById[c.accountId]
            val issuer = account?.serviceName ?: "Unknown"
            val accountName = account?.accountName ?: ""
            val code = try {
                TotpCore.generate(c.secretBase32, c.algorithm, c.digits, c.periodSeconds, now)
            } catch (e: Exception) {
                "••••••"
            }
            val remaining = try {
                TotpCore.remainingSeconds(now, c.periodSeconds)
            } catch (e: Exception) {
                0
            }
            val progress = try {
                TotpCore.progressFraction(now, c.periodSeconds)
            } catch (e: Exception) {
                0f
            }
            TotpCardUi(
                credentialId = c.id,
                stableId = c.stableId,
                accountId = c.accountId,
                issuer = issuer,
                accountName = accountName,
                algorithm = c.algorithm,
                digits = c.digits,
                periodSeconds = c.periodSeconds,
                currentCode = code,
                remainingSeconds = remaining,
                progressFraction = progress,
            )
        }

        // Provider/Account grouping with a recovery summary (P3) — accounts
        // that hold recovery sets surface a one-line "Recovery codes · N
        // remaining" summary so the home screen never expands secret values.
        val setsByAccount = recoverySets.groupBy { it.accountId }
        val accountUis = accounts.map { account ->
            com.rescueauth.v2.ui.model.AccountUi(
                id = account.id,
                providerName = account.serviceName,
                accountName = account.accountName,
                isPinned = account.favorite,
                totpCredentials = cards.filter { it.accountId == account.id }
                    .map { card ->
                        com.rescueauth.v2.ui.model.TotpCredentialUi(
                            id = card.credentialId,
                            stableId = card.stableId,
                            issuer = card.issuer,
                            accountName = card.accountName,
                            algorithm = card.algorithm,
                            digits = card.digits,
                            periodSeconds = card.periodSeconds,
                            currentCode = card.currentCode,
                            remainingSeconds = card.remainingSeconds,
                            progressFraction = card.progressFraction,
                        )
                    },
                recoverySets = setsByAccount[account.id].orEmpty().map { set ->
                    com.rescueauth.v2.ui.model.RecoveryCodeSetUi(
                        id = set.id,
                        title = set.title,
                        usedCount = set.usedCount,
                        totalCount = set.totalCount,
                        codes = emptyList(), // secret values never shown on the home list
                    )
                },
            )
        }
        // Provider-grouped view (Phase 4 — Provider/Account management): group
        // the flat account list under their serviceName. A Provider is the
        // serviceName grouping — no separate entity, no Provider stableId.
        // P7 Pin ordering: within each Provider, pinned accounts sort before
        // unpinned; inside a pinned/unpinned tier the existing stable sort
        // (by accountName) is preserved.
        val providers = accountUis.groupBy { it.providerName }
            .map { (name, group) ->
                com.rescueauth.v2.ui.model.ProviderUi(
                    id = "provider:" + name,
                    serviceName = name,
                    accounts = group.sortedWith(
                        compareByDescending<com.rescueauth.v2.ui.model.AccountUi> { it.isPinned }
                            .thenBy { it.accountName },
                    ),
                )
            }
            .sortedBy { it.serviceName }

        return AuthenticatorUiState(
            loading = false,
            error = null,
            totpCards = cards,
            accounts = accountUis,
            providers = providers,
        )
    }

    /** Called by the UI every second (single shared tick source, wall-clock based). */
    fun onTick() {
        tickSeconds.value = clock.currentTimeSeconds()
    }

    // ------------------------------------------------------------------
    // Add flow
    // ------------------------------------------------------------------

    fun setMode(mode: AddMode) = _formState.update { it.copy(mode = mode, error = null) }

    fun onUriChange(value: String) = _formState.update { it.copy(uri = value, error = null) }

    fun onProviderChange(value: String) = _formState.update { it.copy(provider = value, error = null) }

    fun onAccountNameChange(value: String) = _formState.update { it.copy(accountName = value, error = null) }

    fun onSecretChange(value: String) = _formState.update { it.copy(secret = value, error = null) }

    fun onAlgorithmChange(value: String) = _formState.update { it.copy(algorithm = value, error = null) }

    fun onDigitsChange(value: Int) = _formState.update { it.copy(digits = value, error = null) }

    fun onPeriodChange(value: Int) = _formState.update { it.copy(periodSeconds = value, error = null) }

    fun onEventShown() {
        _events.value = null
    }

    suspend fun submitAdd(): Boolean {
        val form = _formState.value
        if (form.submitting) return false
        _formState.update { it.copy(submitting = true, error = null) }
        return try {
            val result = when (form.mode) {
                AddMode.SCAN -> false // scan has its own flow; no direct submit
                AddMode.PASTE -> addFromUri(form.uri)
                AddMode.MANUAL -> addManual(form)
            }
            result
        } catch (e: AuthenticatorRepository.ValidationException) {
            _formState.update { it.copy(error = e.message ?: "Invalid input") }
            false
        } catch (e: com.rescueauth.v2.totp.OtpauthParser.OtpauthParseException) {
            _formState.update { it.copy(error = e.message ?: "Invalid otpauth URI") }
            false
        } catch (e: Exception) {
            _formState.update { it.copy(error = "Unable to add: ${e.message}") }
            false
        } finally {
            _formState.update { it.copy(submitting = false) }
        }
    }

    private suspend fun addFromUri(uri: String): Boolean {
        val repo = repositoryProvider() ?: return false
        val parsed = com.rescueauth.v2.totp.OtpauthParser.parse(uri)
        val issuer = parsed.issuer ?: "Unknown"
        val account = parsed.accountName ?: issuer
        val accountDomain = repo.findOrCreateAccount(issuer, account)
        repo.addTotpCredential(
            accountId = accountDomain.id,
            secretBase32 = parsed.secretBase32,
            algorithm = parsed.algorithm,
            digits = parsed.digits,
            periodSeconds = parsed.periodSeconds,
        )
        _formState.value = AddTotpFormState(mode = AddMode.PASTE)
        _events.value = AuthenticatorEvent.Added("$issuer · $account")
        return true
    }

    private suspend fun addManual(form: AddTotpFormState): Boolean {
        val repo = repositoryProvider() ?: return false
        val provider = form.provider.trim()
        val account = form.accountName.trim()
        if (provider.isEmpty()) {
            _formState.update { it.copy(error = "Provider / issuer is required") }
            return false
        }
        if (account.isEmpty()) {
            _formState.update { it.copy(error = "Account name is required") }
            return false
        }
        val secret = TotpCore.normalizeSecret(form.secret.trim())
        if (secret.isEmpty() || !TotpCore.isValidBase32(secret)) {
            _formState.update { it.copy(error = "Invalid Base32 secret") }
            return false
        }
        val accountDomain = repo.findOrCreateAccount(provider, account)
        repo.addTotpCredential(
            accountId = accountDomain.id,
            secretBase32 = secret,
            algorithm = form.algorithm,
            digits = form.digits,
            periodSeconds = form.periodSeconds,
        )
        _formState.value = AddTotpFormState(mode = AddMode.MANUAL)
        _events.value = AuthenticatorEvent.Added("$provider · $account")
        return true
    }

    // ------------------------------------------------------------------
    // QR scan + otpauth-migration import (Phase 4 P2)
    // ------------------------------------------------------------------

    fun openScanner() {
        _migrationState.update { it.copy(scannerVisible = true, error = null) }
    }

    /** Closes the camera screen and releases scanner state (cancel). */
    fun closeScanner() {
        batchSession.reset()
        _migrationState.update {
            it.copy(
                scannerVisible = false,
                batchProgress = null,
                error = null,
            )
        }
    }

    fun dismissMigrationPreview() {
        batchSession.reset()
        _migrationState.update {
            it.copy(
                candidates = emptyList(),
                result = null,
                importing = false,
                error = null,
                scannerVisible = false,
                batchProgress = null,
            )
        }
    }

    fun onScanErrorShown() {
        _events.value = null
    }

    /**
     * Routes one scanned raw string through [ScannerResultRouter]. Handles
     * single TOTP QR, migration single/multi-batch collection, and distinct
     * error states. Called on the main thread from the camera screen.
     */
    fun onQrScanned(raw: String) {
        val current = _migrationState.value
        // Do not re-route while a preview is already awaiting confirmation.
        if (current.isPreviewVisible || current.importing) return

        when (val result = ScannerResultRouter.route(raw)) {
            is ScannerResultRouter.ScanResult.Totp -> {
                val parsed = result.parsed
                _migrationState.update {
                    it.copy(
                        candidates = listOf(
                            MigrationTotpCandidate.importable(
                                secretBase32 = parsed.secretBase32,
                                name = parsed.accountName,
                                issuer = parsed.issuer,
                                algorithm = parsed.algorithm,
                                digits = parsed.digits,
                                periodSeconds = parsed.periodSeconds,
                            )
                        ),
                        scannerVisible = false,
                        batchProgress = null,
                        error = null,
                    )
                }
            }
            is ScannerResultRouter.ScanResult.Migration -> {
                handleMigrationScan(result.parsed, result.raw)
            }
            is ScannerResultRouter.ScanResult.NotSupported -> {
                _events.value = AuthenticatorEvent.ScanError("scan_error_not_supported")
            }
            is ScannerResultRouter.ScanResult.MalformedOtpauth -> {
                _events.value = AuthenticatorEvent.ScanError("scan_error_malformed")
            }
            is ScannerResultRouter.ScanResult.MalformedMigration -> {
                _events.value = AuthenticatorEvent.ScanError("scan_error_malformed_migration")
            }
        }
    }

    private fun handleMigrationScan(parsed: com.rescueauth.v2.migration.MigrationParseResult, raw: String) {
        val batchSize = parsed.batchSize
        val batchIndex = parsed.batchIndex
        val batchId = parsed.batchId

        if (batchSize <= 1) {
            // Single QR migration: go straight to preview.
            _migrationState.update {
                it.copy(
                    candidates = parsed.entries,
                    scannerVisible = false,
                    batchProgress = null,
                    error = null,
                )
            }
            return
        }

        // Multi-QR batch collection session.
        try {
            val candidates = batchSession.addPart(
                batchId = batchId,
                batchIndex = batchIndex,
                batchSize = batchSize,
                candidates = parsed.entries,
                rawPayload = raw,
            )
            val progress = batchSession.progress
            _migrationState.update {
                it.copy(
                    batchProgress = progress?.let { p -> p.collected to p.total },
                    scannerVisible = true,
                    error = null,
                )
            }
            if (candidates.isNotEmpty()) {
                // Batch complete → leave camera and show the full preview.
                _migrationState.update {
                    it.copy(
                        candidates = candidates,
                        scannerVisible = false,
                        batchProgress = null,
                    )
                }
            }
        } catch (e: MigrationBatchSession.BatchError) {
            _events.value = AuthenticatorEvent.ScanError("scan_error_batch_conflict")
        }
    }

    /** Imports the confirmed migration candidates into the real Vault. */
    suspend fun confirmMigrationImport(): Boolean {
        val state = _migrationState.value
        val repo = repositoryProvider() ?: return false
        if (state.importing) return false
        val candidates = state.candidates.filter { it.status == MigrationEntryStatus.IMPORTABLE }
        if (candidates.isEmpty()) return false

        _migrationState.update { it.copy(importing = true, error = null) }
        return try {
            val items = candidates.map {
                TotpImportItem(
                    issuer = it.issuer ?: "Unknown",
                    accountName = it.name ?: it.issuer ?: "Unknown",
                    secretBase32 = it.secretBase32 ?: "",
                    algorithm = it.algorithm ?: TotpCore.DEFAULT_ALGORITHM,
                    digits = it.digits ?: TotpCore.DEFAULT_DIGITS,
                    periodSeconds = it.periodSeconds ?: TotpCore.DEFAULT_PERIOD_SECONDS,
                )
            }
            val result = repo.importTotpBatch(items)
            _migrationState.update {
                it.copy(
                    importing = false,
                    result = MigrationImportResultUi(
                        imported = result.importedCount,
                        duplicates = result.duplicateCount,
                        unsupported = state.candidates.count { it.status == MigrationEntryStatus.UNSUPPORTED },
                        invalid = state.candidates.count { it.status == MigrationEntryStatus.INVALID },
                    ),
                )
            }
            _events.value = AuthenticatorEvent.MigrationImported(result.importedCount, result.duplicateCount)
            true
        } catch (e: Exception) {
            _migrationState.update {
                it.copy(importing = false, error = "migration_import_failed")
            }
            false
        }
    }

    // ------------------------------------------------------------------
    // Copy + Delete + Undo
    // ------------------------------------------------------------------

    fun copyCode(card: TotpCardUi) {
        if (card.currentCode.isNotBlank() && card.currentCode != "••••••") {
            _events.value = AuthenticatorEvent.CopyCode(card.currentCode, "${card.issuer} · ${card.accountName}")
        }
    }

    /** User tapped an account row — the caller navigates to its detail screen. */
    fun openAccount(accountId: String) {
        _events.value = AuthenticatorEvent.OpenAccount(accountId)
    }

    /**
     * P7 Account Pin/Unpin toggle (serialized repository mutation; session-
     * locked fails safely). The real Room Flow is the source of truth — the
     * list updates optimistically via the Flow on the next emission.
     */
    suspend fun togglePin(accountId: String) {
        val repo = repositoryProvider() ?: return
        val pinned = _uiState.value.accounts.firstOrNull { it.id == accountId }?.isPinned ?: return
        runCatching { repo.setPinned(accountId, !pinned) }
    }

    suspend fun deleteCard(card: TotpCardUi): Boolean {
        val repo = repositoryProvider() ?: return false
        val deleted = repo.deleteTotpCredential(card.credentialId)
        if (deleted != null) {
            pendingUndo = deleted
            _events.value = AuthenticatorEvent.Deleted("${card.issuer} · ${card.accountName}", card.credentialId)
            return true
        }
        return false
    }

    suspend fun undoDelete(): Boolean {
        val repo = repositoryProvider() ?: return false
        val pending = pendingUndo ?: return false
        pendingUndo = null
        repo.restoreTotpCredential(pending)
        _events.value = AuthenticatorEvent.Restored("${pending.id.take(8)}")
        return true
    }

    private var pendingUndo: TotpCredential? = null

    /** P8 §8 — in-memory exact Account subtree snapshot captured on delete. */
    private var pendingAccountUndo: com.rescueauth.v2.domain.DeletedAccountSnapshot? = null

    // ------------------------------------------------------------------
    // Provider & Account Full Management (Phase 4 — hierarchy management)
    // ------------------------------------------------------------------

    /** All distinct provider names (for Add Account / Move / Merge pickers). */
    suspend fun availableProviders(): List<String> {
        val repo = managementRepositoryProvider() ?: return emptyList()
        return runCatching { repo.listProviders() }.getOrDefault(emptyList())
    }

    suspend fun createProvider(serviceName: String, accountName: String): Boolean {
        val repo = managementRepositoryProvider() ?: return false
        return runCatching {
            repo.createProvider(serviceName, accountName)
            _events.value = AuthenticatorEvent.ManagementMessage("Provider created")
            true
        }.getOrElse {
            _events.value = AuthenticatorEvent.ManagementError(it.message ?: "Unable to create provider")
            false
        }
    }

    suspend fun createAccount(provider: String, accountName: String): Boolean {
        val repo = managementRepositoryProvider() ?: return false
        return runCatching {
            repo.createAccount(provider, accountName)
            _events.value = AuthenticatorEvent.ManagementMessage("Account added")
            true
        }.getOrElse {
            _events.value = AuthenticatorEvent.ManagementError(it.message ?: "Unable to add account")
            false
        }
    }

    suspend fun renameProvider(oldName: String, newName: String): Boolean {
        val repo = managementRepositoryProvider() ?: return false
        return runCatching {
            repo.renameProvider(oldName, newName)
            _events.value = AuthenticatorEvent.ManagementMessage("Provider renamed")
            true
        }.getOrElse {
            _events.value = AuthenticatorEvent.ManagementError(it.message ?: "Unable to rename provider")
            false
        }
    }

    suspend fun deleteProvider(provider: String): ProviderDeleteUiResult? {
        val repo = managementRepositoryProvider() ?: return null
        return runCatching {
            val result = repo.deleteProvider(provider)
            _events.value = AuthenticatorEvent.ManagementMessage("Provider deleted")
            ProviderDeleteUiResult(result.accountCount, result.totpCount, result.recoverySetCount)
        }.getOrElse {
            _events.value = AuthenticatorEvent.ManagementError(it.message ?: "Unable to delete provider")
            null
        }
    }

    suspend fun renameAccount(accountId: String, newName: String): Boolean {
        val repo = managementRepositoryProvider() ?: return false
        return runCatching {
            repo.renameAccount(accountId, newName)
            _events.value = AuthenticatorEvent.ManagementMessage("Account renamed")
            true
        }.getOrElse {
            _events.value = AuthenticatorEvent.ManagementError(it.message ?: "Unable to rename account")
            false
        }
    }

    suspend fun moveAccount(accountId: String, destinationProvider: String): Boolean {
        val repo = managementRepositoryProvider() ?: return false
        return runCatching {
            repo.moveAccount(accountId, destinationProvider)
            _events.value = AuthenticatorEvent.ManagementMessage("Account moved")
            true
        }.getOrElse {
            _events.value = AuthenticatorEvent.ManagementError(it.message ?: "Unable to move account")
            false
        }
    }

    suspend fun mergeAccounts(sourceAccountId: String, destinationAccountId: String): Boolean {
        val repo = managementRepositoryProvider() ?: return false
        return runCatching {
            repo.mergeAccounts(sourceAccountId, destinationAccountId)
            _events.value = AuthenticatorEvent.ManagementMessage("Accounts merged")
            true
        }.getOrElse {
            _events.value = AuthenticatorEvent.ManagementError(it.message ?: "Unable to merge accounts")
            false
        }
    }

    /**
     * P8 §11 — deletes an Account immediately (no confirmation) and captures an
     * in-memory exact subtree snapshot so Undo can restore it. The previous
     * pending Account Undo (if any) is replaced: a new delete forgets the old
     * token and its secret snapshot is released (P8 §7).
     */
    suspend fun deleteAccount(accountId: String): AccountDeleteUiResult? {
        val repo = managementRepositoryProvider() ?: return null
        return runCatching {
            val snapshot = repo.deleteAccountWithSnapshot(accountId) ?: return null
            pendingAccountUndo = snapshot
            _events.value = AuthenticatorEvent.AccountDeleted(snapshot.safeLabel)
            AccountDeleteUiResult(
                totpCount = snapshot.totps.size,
                recoverySetCount = snapshot.recoverySets.size,
            )
        }.getOrElse {
            _events.value = AuthenticatorEvent.ManagementError(it.message ?: "Unable to delete account")
            null
        }
    }

    /** P8 §9/§10 — restores the last deleted Account (single consume of the token). */
    suspend fun undoDeleteAccount(): Boolean {
        val repo = managementRepositoryProvider() ?: return false
        val pending = pendingAccountUndo ?: return false
        pendingAccountUndo = null
        val outcome = runCatching { repo.restoreAccount(pending) }.getOrNull()
            ?: return false
        return when (outcome) {
            is com.rescueauth.v2.domain.UndoRestoreOutcome.Restored -> {
                _events.value = AuthenticatorEvent.AccountRestored(pending.safeLabel)
                true
            }
            is com.rescueauth.v2.domain.UndoRestoreOutcome.Blocked -> {
                _events.value = AuthenticatorEvent.AccountRestoreBlocked(pending.safeLabel)
                false
            }
        }
    }
}

/** Safe metadata counts returned from an Account delete (no secrets). */
data class AccountDeleteUiResult(
    val totpCount: Int,
    val recoverySetCount: Int,
)

/** Safe metadata counts returned from a Provider delete (no secrets). */
data class ProviderDeleteUiResult(
    val accountCount: Int,
    val totpCount: Int,
    val recoverySetCount: Int,
)
