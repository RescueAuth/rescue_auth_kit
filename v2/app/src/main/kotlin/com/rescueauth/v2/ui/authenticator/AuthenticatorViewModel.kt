package com.rescueauth.v2.ui.authenticator

import com.rescueauth.v2.domain.AuthAccount
import com.rescueauth.v2.domain.TotpCredential
import com.rescueauth.v2.repository.AuthenticatorRepository
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
) {
    val isEmpty: Boolean get() = !loading && error == null && totpCards.isEmpty()
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

enum class AddMode { PASTE, MANUAL }

/**
 * One-shot user events for the Authenticator screen. Android-only side effects
 * (clipboard write, snackbar) are triggered from the Compose layer.
 */
sealed interface AuthenticatorEvent {
    /** Copy the given code to the Android clipboard + show "copied" snackbar. */
    data class CopyCode(val code: String, val label: String) : AuthenticatorEvent

    /** A credential was deleted; show a snackbar with an Undo action. */
    data class Deleted(val label: String, val credentialId: String) : AuthenticatorEvent

    /** A credential was restored via Undo. */
    data class Restored(val label: String) : AuthenticatorEvent

    /** A new TOTP credential was added. */
    data class Added(val label: String) : AuthenticatorEvent
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
                    _uiState.value = AuthenticatorUiState(loading = false)
                }
            }
        }
    }

    private suspend fun collectCards(repo: AuthenticatorRepository) {
        combine(repo.observeAccounts(), repo.observeTotpCredentials(), tickSeconds) { accounts, creds, now ->
            credentialsById.clear()
            creds.forEach { credentialsById[it.id] = it }
            buildCards(accounts, creds, now)
        }.collectLatest { state -> _uiState.value = state }
    }

    private fun buildCards(
        accounts: List<AuthAccount>,
        creds: List<TotpCredential>,
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
        return AuthenticatorUiState(
            loading = false,
            error = null,
            totpCards = cards,
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
    // Copy + Delete + Undo
    // ------------------------------------------------------------------

    fun copyCode(card: TotpCardUi) {
        if (card.currentCode.isNotBlank() && card.currentCode != "••••••") {
            _events.value = AuthenticatorEvent.CopyCode(card.currentCode, "${card.issuer} · ${card.accountName}")
        }
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
}
