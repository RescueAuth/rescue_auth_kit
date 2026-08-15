package com.rescueauth.v2.ui.developer

import com.rescueauth.v2.domain.DeveloperEntry
import com.rescueauth.v2.repository.DeveloperRepository
import com.rescueauth.v2.session.SecureSessionStateMachine
import com.rescueauth.v2.ui.model.DeveloperEntryType
import com.rescueauth.v2.ui.model.DeveloperEntryUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Developer Vault list state (Phase 4 P4).
 *
 * [entries] carries only non-secret metadata ([title] / [subtitle]) — secret
 * values never cross into the UI model (Issue #20 §11/§12/§21). `isEmpty` is
 * the empty state of the Developer top-level screen.
 */
data class DeveloperListUiState(
    val loading: Boolean = true,
    val entries: List<DeveloperEntryUi> = emptyList(),
    val error: String? = null,
) {
    val isEmpty: Boolean
        get() = !loading && error == null && entries.isEmpty()
}

/**
 * ViewModel for the Developer Vault list screen.
 *
 * Plain class (not androidx ViewModel) so it is JVM-testable; the Compose
 * layer provides the [scope]. Collects the real repository metadata flow and
 * never touches plaintext secrets.
 */
class DeveloperListViewModel(
    private val developerRepositoryProvider: () -> DeveloperRepository?,
    sessionState: StateFlow<SecureSessionStateMachine.State>,
    private val scope: CoroutineScope,
) {

    private val _uiState = MutableStateFlow(DeveloperListUiState())
    val uiState: StateFlow<DeveloperListUiState> = _uiState.asStateFlow()

    private var collectionJob: Job? = null

    init {
        scope.launch {
            sessionState.collect { state ->
                if (state == SecureSessionStateMachine.State.UNLOCKED) {
                    val repo = developerRepositoryProvider()
                    if (repo != null && collectionJob == null) {
                        collectionJob = scope.launch { collect(repo) }
                    }
                } else {
                    collectionJob?.cancel()
                    collectionJob = null
                    // P8 §6: session lock clears the shared Developer Undo store.
                    DeveloperUndoStore.clear()
                    _uiState.value = DeveloperListUiState(loading = false)
                }
            }
        }
    }

    private suspend fun collect(repo: DeveloperRepository) {
        repo.observeAll().collectLatest { entries ->
            _uiState.value = DeveloperListUiState(
                loading = false,
                entries = entries.map { it.toUi() },
            )
        }
    }

    private fun DeveloperEntry.toUi(): DeveloperEntryUi = DeveloperEntryUi(
        id = id,
        stableId = stableId,
        type = type.toUi(),
        title = title,
        subtitle = subtitle(),
    )

    private fun DeveloperEntry.subtitle(): String? = when (type) {
        com.rescueauth.v2.domain.DeveloperEntryType.API_CREDENTIAL -> "API"
        com.rescueauth.v2.domain.DeveloperEntryType.SSH_KEY -> "SSH"
        com.rescueauth.v2.domain.DeveloperEntryType.GENERIC_SECRET -> "Secret"
        com.rescueauth.v2.domain.DeveloperEntryType.ANDROID_SIGNING_KEY -> "Signing"
        com.rescueauth.v2.domain.DeveloperEntryType.ENVIRONMENT_VARIABLE_SET -> "Env"
    }

    private fun com.rescueauth.v2.domain.DeveloperEntryType.toUi(): DeveloperEntryType =
        when (this) {
            com.rescueauth.v2.domain.DeveloperEntryType.ANDROID_SIGNING_KEY -> DeveloperEntryType.ANDROID_SIGNING_KEY
            com.rescueauth.v2.domain.DeveloperEntryType.API_CREDENTIAL -> DeveloperEntryType.API_CREDENTIAL
            com.rescueauth.v2.domain.DeveloperEntryType.SSH_KEY -> DeveloperEntryType.SSH_KEY
            com.rescueauth.v2.domain.DeveloperEntryType.ENVIRONMENT_VARIABLE_SET -> DeveloperEntryType.ENVIRONMENT_VARIABLE_SET
            com.rescueauth.v2.domain.DeveloperEntryType.GENERIC_SECRET -> DeveloperEntryType.GENERIC_SECRET
        }
}
