package com.rescueauth.v2.ui.search

import com.rescueauth.v2.repository.AuthenticatorRepository
import com.rescueauth.v2.repository.DeveloperRepository
import com.rescueauth.v2.repository.RecoveryCodeRepository
import com.rescueauth.v2.search.SearchIndex
import com.rescueauth.v2.search.SearchResult
import com.rescueauth.v2.session.SecureSessionStateMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * P7 Global Search screen state.
 */
data class SearchUiState(
    val query: String = "",
    val results: List<SearchResult> = emptyList(),
    val searching: Boolean = false,
    val unlocked: Boolean = false,
) {
    val isEmptyQuery: Boolean get() = query.isBlank()
}

/**
 * P7 Global Search ViewModel.
 *
 * Plain class (not androidx ViewModel) so it is JVM-testable. It collects the
 * real repository Flows (accounts + TOTP + recovery sets + Developer safe
 * metadata) into an in-memory [SearchIndex] and runs deterministic matching.
 *
 * ## Lifecycle / secrecy (P7 §13/§14)
 *
 * - The query lives **in-memory only**. It is never written to Room,
 *   DataStore, SavedStateHandle, rememberSaveable, Bundle, logs or analytics.
 * - On session lock we clear the query, clear results, cancel all DB
 *   collectors and drop the in-memory [SearchIndex] / projection.
 * - On unlock we rebuild the projection from the current repository Flows.
 * - Leaving/recreating the screen (a fresh ViewModel) starts with an empty
 *   query — no query is restored across process recreation.
 */
class SearchViewModel(
    private val authenticatorProvider: () -> AuthenticatorRepository?,
    private val recoveryProvider: () -> RecoveryCodeRepository?,
    private val developerProvider: () -> DeveloperRepository?,
    sessionState: StateFlow<SecureSessionStateMachine.State>,
    private val scope: CoroutineScope,
) {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var collectionJob: Job? = null
    private var searchIndex: SearchIndex? = null

    init {
        scope.launch {
            sessionState.collect { state ->
                if (state == SecureSessionStateMachine.State.UNLOCKED) {
                    collectionJob?.cancel()
                    collectionJob = scope.launch { collect() }
                } else {
                    collectionJob?.cancel()
                    collectionJob = null
                    searchIndex = null
                    _uiState.value = SearchUiState() // clears query + results
                }
            }
        }
    }

    private suspend fun collect() {
        val authRepo = authenticatorProvider()
        val recoveryRepo = recoveryProvider()
        val devRepo = developerProvider()
        if (authRepo == null) {
            _uiState.update { it.copy(searching = false, unlocked = true) }
            return
        }
        val accounts = authRepo.observeAccounts()
        val totps = authRepo.observeTotpCredentials()
        val recovery = recoveryRepo?.observeAllSets()
            ?: kotlinx.coroutines.flow.flowOf(emptyList())
        val devMeta = devRepo?.observeSearchMetadata()
            ?: kotlinx.coroutines.flow.flowOf(emptyList())

        combine(accounts, totps, recovery, devMeta) { acc, tp, rec, dev ->
            acc to SearchIndex(acc, tp, rec, dev)
        }.collectLatest { (_, index) ->
            searchIndex = index
            _uiState.update { it.copy(searching = false, unlocked = true) }
            reapplyQuery()
        }
    }

    /** User typed a new query. In-memory only; never persisted. */
    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query) }
        reapplyQuery()
    }

    /** Clears the query (clear button). Also clears results. */
    fun clearQuery() {
        _uiState.update { it.copy(query = "", results = emptyList()) }
    }

    private fun reapplyQuery() {
        val state = _uiState.value
        val index = searchIndex
        if (state.isEmptyQuery || index == null) {
            _uiState.update { it.copy(results = emptyList(), searching = false) }
            return
        }
        _uiState.update { it.copy(results = index.search(state.query), searching = false) }
    }

    private fun MutableStateFlow<SearchUiState>.update(block: (SearchUiState) -> SearchUiState) {
        this.value = block(this.value)
    }
}
