package com.rescueauth.v2.ui.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.rescueauth.v2.repository.VaultAccess
import com.rescueauth.v2.search.SearchResult
import com.rescueauth.v2.session.SecureSessionStateMachine
import com.rescueauth.v2.ui.screens.search.SearchScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * P7 Global Search route — wires the production [SearchViewModel] (in-memory
 * safe projection) into the [SearchScreen].
 *
 * Navigation to a result target is delegated to the NavHost via
 * [onResultClick]. Leaving this screen discards the ViewModel and therefore
 * the query (in-memory only, P7 §13). Session lock clears query + results in
 * the ViewModel (P7 §14).
 */
@Composable
fun SearchRoute(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onResultClick: (SearchResult) -> Unit,
) {
    val scope = remember { CoroutineScope(SupervisorJob()) }

    val sessionState: kotlinx.coroutines.flow.StateFlow<SecureSessionStateMachine.State> = remember {
        val sm = VaultAccess.sessionManager
        if (sm != null) {
            sm.sessionStateFlow
        } else {
            SecureSessionStateMachine().state
        }
    }
    val viewModel = remember {
        SearchViewModel(
            authenticatorProvider = { VaultAccess.authenticatorRepository() },
            recoveryProvider = { VaultAccess.recoveryRepository() },
            developerProvider = { VaultAccess.developerRepository() },
            sessionState = sessionState,
            scope = scope,
        )
    }
    val uiState by viewModel.uiState.collectAsState()

    SearchScreen(
        uiState = uiState,
        onBack = onBack,
        onQueryChange = viewModel::onQueryChange,
        onClearQuery = viewModel::clearQuery,
        onResultClick = onResultClick,
        modifier = modifier,
    )
}
