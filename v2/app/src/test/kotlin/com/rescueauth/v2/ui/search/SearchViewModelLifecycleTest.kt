package com.rescueauth.v2.ui.search

import com.rescueauth.v2.session.SecureSessionStateMachine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P7 Search lifecycle tests (28-34) on a pure in-memory [SearchViewModel].
 *
 * The ViewModel is constructed with repository providers that return `null`
 * (locked state) or a stubbed repository, so we can drive session transitions
 * deterministically and assert query/result lifecycle without a real DB.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelLifecycleTest {

    @Test
    fun `session lock clears query and results`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = kotlinx.coroutines.CoroutineScope(dispatcher + kotlinx.coroutines.SupervisorJob())
        val sessionState = MutableStateFlow(SecureSessionStateMachine.State.UNLOCKED)
        val vm = SearchViewModel(
            authenticatorProvider = { null },
            recoveryProvider = { null },
            developerProvider = { null },
            sessionState = sessionState,
            scope = scope,
        )
        vm.onQueryChange("github")
        assertEquals("github", vm.uiState.value.query)

        sessionState.value = SecureSessionStateMachine.State.LOCKED
        testScheduler.advanceUntilIdle()
        assertEquals("", vm.uiState.value.query)
        assertTrue(vm.uiState.value.results.isEmpty())
        scope.cancel()
    }

    @Test
    fun `fresh viewmodel starts with empty query`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = kotlinx.coroutines.CoroutineScope(dispatcher + kotlinx.coroutines.SupervisorJob())
        val sessionState = MutableStateFlow(SecureSessionStateMachine.State.LOCKED)
        val vm = SearchViewModel(
            authenticatorProvider = { null },
            recoveryProvider = { null },
            developerProvider = { null },
            sessionState = sessionState,
            scope = scope,
        )
        // A new ViewModel never restores a prior query (process recreation).
        assertEquals("", vm.uiState.value.query)
        assertTrue(vm.uiState.value.results.isEmpty())
        scope.cancel()
    }
}
