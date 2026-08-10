package com.rescueauth.v2.ui.authenticator

import com.rescueauth.v2.domain.RecoveryCodeSet
import com.rescueauth.v2.repository.AuthenticatorRepository
import com.rescueauth.v2.repository.RecoveryCodeRepository
import com.rescueauth.v2.session.SecureSessionStateMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Recovery Codes list state (Phase 4 P3).
 *
 * [sets] carries the real UI models (values hidden until revealed); the
 * summary line drives the collapsed card subtitle. `isEmpty` is the empty
 * state of the account detail screen.
 */
data class RecoveryUiState(
    val loading: Boolean = false,
    val accountId: String? = null,
    val providerName: String = "",
    val accountName: String = "",
    val sets: List<com.rescueauth.v2.ui.model.RecoveryCodeSetUi> = emptyList(),
    val error: String? = null,
) {
    val isEmpty: Boolean
        get() = !loading && error == null && sets.isEmpty()
    val remainingCount: Int get() = sets.sumOf { it.remainingCount }
}

/** Form input for the Add/Edit Recovery Codes sheet. */
data class RecoveryFormState(
    val editingSetId: String? = null,
    val title: String = "",
    val valuesText: String = "",
    val submitting: Boolean = false,
    val error: String? = null,
) {
    /** Whitespace-normalised, non-blank, line-per-code parse of [valuesText]. */
    fun parsedValues(): List<String> =
        valuesText.lines().map { it.trim() }.filter { it.isNotEmpty() }

    val isEditing: Boolean get() = editingSetId != null
}

/**
 * One-shot events for the Recovery Codes screen. Android-only side effects
 * (clipboard write, snackbar) are triggered from the Compose layer.
 */
sealed interface RecoveryEvent {
    data class CopyCode(val value: String) : RecoveryEvent
    data class CopyAll(val values: List<String>) : RecoveryEvent
    data class CopyRemaining(val values: List<String>) : RecoveryEvent
    data class Deleted(val label: String, val setId: String) : RecoveryEvent
    data class Restored(val label: String) : RecoveryEvent
    data class Added(val label: String) : RecoveryEvent
    data class Edited(val label: String) : RecoveryEvent
    data class MarkedUsed(val label: String) : RecoveryEvent
    data class MarkedUnused(val label: String) : RecoveryEvent
    data class Moved(val label: String) : RecoveryEvent
    data class Error(val message: String) : RecoveryEvent
}

/**
 * ViewModel for the Account → Recovery Codes screen (Phase 4 P3).
 *
 * Plain class (not androidx ViewModel) so it is JVM-testable; the Compose
 * layer provides the [scope]. It combines the real repository Flows with the
 * session state, exposes add/edit/mark/copy/delete+undo actions and keeps
 * per-code reveal state in memory only (cleared on session lock).
 */
class RecoveryViewModel(
    private val authRepositoryProvider: () -> AuthenticatorRepository?,
    private val recoveryRepositoryProvider: () -> RecoveryCodeRepository?,
    sessionState: StateFlow<SecureSessionStateMachine.State>,
    private val accountId: String,
    private val scope: CoroutineScope,
) {

    private val _uiState = MutableStateFlow(RecoveryUiState())
    val uiState: StateFlow<RecoveryUiState> = _uiState.asStateFlow()

    private val _formState = MutableStateFlow(RecoveryFormState())
    val formState: StateFlow<RecoveryFormState> = _formState.asStateFlow()

    private val _events = MutableStateFlow<RecoveryEvent?>(null)
    val events: StateFlow<RecoveryEvent?> = _events.asStateFlow()

    /** Reveal state keyed by code id — in-memory only, cleared on session lock. */
    private val _revealedIds = MutableStateFlow<Set<String>>(emptySet())
    val revealedIds: StateFlow<Set<String>> = _revealedIds.asStateFlow()

    private var collectionJob: Job? = null

    /** Last deleted set — restored on Undo with exact stableIds/states. */
    private var pendingUndo: RecoveryCodeSet? = null

    init {
        scope.launch {
            sessionState.collect { state ->
                if (state == SecureSessionStateMachine.State.UNLOCKED) {
                    val repo = recoveryRepositoryProvider()
                    if (repo != null && collectionJob == null) {
                        collectionJob = scope.launch { collectSets(repo) }
                    }
                } else {
                    collectionJob?.cancel()
                    collectionJob = null
                    // Session locked: no visible secret may remain in the UI, and
                    // the pending Undo payload (recovery plaintext) is cleared
                    // (P8 §6).
                    _revealedIds.value = emptySet()
                    pendingUndo = null
                    _uiState.value = RecoveryUiState(loading = false)
                    _formState.value = RecoveryFormState()
                }
            }
        }
    }

    private suspend fun collectSets(repo: RecoveryCodeRepository) {
        repo.observeSetsByAccount(accountId).collectLatest { sets ->
            val accounts = authRepositoryProvider()?.observeAccounts()?.firstOrNull()
            val account = accounts?.firstOrNull { it.id == accountId }
            _uiState.value = RecoveryUiState(
                loading = false,
                accountId = accountId,
                providerName = account?.serviceName ?: "",
                accountName = account?.accountName ?: "",
                sets = sets.map { set ->
                    com.rescueauth.v2.ui.model.RecoveryCodeSetUi(
                        id = set.id,
                        title = set.title,
                        usedCount = set.usedCount,
                        totalCount = set.totalCount,
                        codes = set.codes.map { code ->
                            com.rescueauth.v2.ui.model.RecoveryCodeUi(
                                id = code.id,
                                value = code.value,
                                isUsed = code.isUsed,
                                usedAt = code.usedAt,
                            )
                        },
                    )
                },
            )
        }
    }

    fun onEventShown() {
        _events.value = null
    }

    fun isRevealed(codeId: String): Boolean = codeId in _revealedIds.value

    fun toggleReveal(codeId: String) {
        val current = _revealedIds.value
        _revealedIds.value =
            if (codeId in current) current - codeId else current + codeId
    }

    /** Resets revealed state (used when a sensitive value leaves composition). */
    fun clearRevealed() {
        _revealedIds.value = emptySet()
    }

    // ------------------------------------------------------------------
    // Add / Edit form
    // ------------------------------------------------------------------

    fun beginCreate() {
        _formState.value = RecoveryFormState()
    }

    fun beginEdit(setId: String) {
        val set = _uiState.value.sets.firstOrNull { it.id == setId } ?: return
        _formState.value = RecoveryFormState(
            editingSetId = setId,
            title = set.title,
            valuesText = set.codes.joinToString("\n") { it.value },
            error = null,
        )
    }

    fun onTitleChange(value: String) = _formState.update { it.copy(title = value, error = null) }

    fun onValuesChange(value: String) = _formState.update { it.copy(valuesText = value, error = null) }

    fun dismissForm() {
        _formState.value = RecoveryFormState()
    }

    /** Parses the form, validates duplicates, and saves (create or edit). */
    suspend fun submitForm(): Boolean {
        val form = _formState.value
        if (form.submitting) return false
        val repo = recoveryRepositoryProvider() ?: return false

        val title = form.title.trim()
        if (title.isEmpty()) {
            _formState.update { it.copy(error = "title_required") }
            return false
        }
        val values = form.parsedValues()
        if (values.isEmpty()) {
            _formState.update { it.copy(error = "empty_values") }
            return false
        }
        val duplicates = values.groupBy { it }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) {
            _formState.update { it.copy(error = "duplicate:${duplicates.first()}") }
            return false
        }

        _formState.update { it.copy(submitting = true, error = null) }
        return try {
            if (form.isEditing) {
                repo.editSet(form.editingSetId!!, title, values)
                _events.value = RecoveryEvent.Edited(title)
            } else {
                repo.createSet(accountId, title, values)
                _events.value = RecoveryEvent.Added(title)
            }
            _formState.value = RecoveryFormState()
            true
        } catch (e: RecoveryCodeRepository.ValidationException) {
            _formState.update { it.copy(error = e.message ?: "invalid") }
            false
        } catch (e: RecoveryCodeRepository.NotFoundException) {
            _formState.update { it.copy(error = "not_found") }
            false
        } catch (e: Exception) {
            _formState.update { it.copy(error = "unexpected") }
            false
        } finally {
            _formState.update { it.copy(submitting = false) }
        }
    }

    // ------------------------------------------------------------------
    // USED / UNUSED
    // ------------------------------------------------------------------

    suspend fun markUsed(codeId: String, label: String): Boolean {
        val repo = recoveryRepositoryProvider() ?: return false
        return try {
            repo.markUsed(codeId)
            _events.value = RecoveryEvent.MarkedUsed(label)
            true
        } catch (e: Exception) {
            _events.value = RecoveryEvent.Error("mark_failed")
            false
        }
    }

    suspend fun markUnused(codeId: String, label: String): Boolean {
        val repo = recoveryRepositoryProvider() ?: return false
        return try {
            repo.markUnused(codeId)
            _events.value = RecoveryEvent.MarkedUnused(label)
            true
        } catch (e: Exception) {
            _events.value = RecoveryEvent.Error("mark_failed")
            false
        }
    }

    // ------------------------------------------------------------------
    // Copy
    // ------------------------------------------------------------------

    fun copyCode(codeId: String) {
        val code = _uiState.value.sets.flatMap { it.codes }.firstOrNull { it.id == codeId } ?: return
        _events.value = RecoveryEvent.CopyCode(code.value)
    }

    fun copyAll(setId: String) {
        val set = _uiState.value.sets.firstOrNull { it.id == setId } ?: return
        _events.value = RecoveryEvent.CopyAll(set.codes.map { it.value })
    }

    fun copyRemaining(setId: String) {
        val set = _uiState.value.sets.firstOrNull { it.id == setId } ?: return
        _events.value = RecoveryEvent.CopyRemaining(set.codes.filter { !it.isUsed }.map { it.value })
    }

    // ------------------------------------------------------------------
    // Delete + Undo
    // ------------------------------------------------------------------

    suspend fun deleteSet(setId: String): Boolean {
        val repo = recoveryRepositoryProvider() ?: return false
        val set = _uiState.value.sets.firstOrNull { it.id == setId } ?: return false
        return try {
            val deleted = repo.deleteSet(setId)
            if (deleted != null) {
                pendingUndo = deleted
                _events.value = RecoveryEvent.Deleted(set.title, setId)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            _events.value = RecoveryEvent.Error("delete_failed")
            false
        }
    }

    suspend fun undoDelete(): Boolean {
        val repo = recoveryRepositoryProvider() ?: return false
        val pending = pendingUndo ?: return false
        pendingUndo = null
        return try {
            repo.restoreSet(pending)
            _events.value = RecoveryEvent.Restored(pending.title)
            true
        } catch (e: Exception) {
            false
        }
    }

    // ------------------------------------------------------------------
    // Move (P8 §16–§20)
    // ------------------------------------------------------------------

    /**
     * All destination Accounts (provider + account metadata) EXCLUDING the
     * current owning Account, used by the Move picker (P8 §16). Returns an
     * empty list when there is no other Account (the caller disables Move).
     */
    suspend fun availableMoveDestinations(): List<MoveDestination> {
        val authRepo = authRepositoryProvider() ?: return emptyList()
        val accounts = runCatching { authRepo.observeAccounts().firstOrNull() ?: emptyList() }
            .getOrDefault(emptyList())
        return accounts
            .filter { it.id != accountId }
            .map { MoveDestination(accountId = it.id, providerName = it.serviceName, accountName = it.accountName) }
    }

    /** Moves [setId] to [destinationAccountId] atomically (P8 §19). */
    suspend fun moveSet(setId: String, destinationAccountId: String): Boolean {
        val repo = recoveryRepositoryProvider() ?: return false
        val set = _uiState.value.sets.firstOrNull { it.id == setId } ?: return false
        return try {
            repo.moveSet(setId, destinationAccountId)
            _events.value = RecoveryEvent.Moved(set.title)
            true
        } catch (e: RecoveryCodeRepository.NotFoundException) {
            _events.value = RecoveryEvent.Error("move_failed")
            false
        } catch (e: RecoveryCodeRepository.ConflictException) {
            _events.value = RecoveryEvent.Error("move_conflict")
            false
        } catch (e: Exception) {
            _events.value = RecoveryEvent.Error("move_failed")
            false
        }
    }
}

/** A safe, non-secret destination Account option for the Recovery Move picker. */
data class MoveDestination(
    val accountId: String,
    val providerName: String,
    val accountName: String,
) {
    val label: String get() = "$providerName · $accountName"
}
