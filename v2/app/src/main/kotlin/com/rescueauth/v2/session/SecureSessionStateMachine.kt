package com.rescueauth.v2.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Secure session state machine (ADR-0003 §4).
 *
 * ```
 * LOCKED ──authenticate()──▶ AUTHENTICATING ──success──▶ UNLOCKED
 *    ▲                                                    │
 *    └──────────lock()/timeout/background─────────────────┘
 * UNLOCKED ──keystore invalidated──▶ KEY_INVALIDATED (recovery flow)
 * ```
 *
 * Thread-safe: all state transitions are atomic (single MutableStateFlow).
 * The repository checks [state] before any DB mutation.
 */
class SecureSessionStateMachine {

    enum class State { LOCKED, AUTHENTICATING, UNLOCKED, KEY_INVALIDATED }

    private val _state = MutableStateFlow(State.LOCKED)
    val state: StateFlow<State> = _state.asStateFlow()

    /** @return true if the transition happened (false if already unlocked). */
    fun beginAuthentication(): Boolean {
        // From LOCKED only; KEY_INVALIDATED must go through recovery first.
        return _state.compareAndSet(State.LOCKED, State.AUTHENTICATING)
    }

    fun onAuthenticationSuccess() {
        _state.value = State.UNLOCKED
    }

    fun onAuthenticationFailure() {
        // Only roll back from AUTHENTICATING to LOCKED.
        _state.compareAndSet(State.AUTHENTICATING, State.LOCKED)
    }

    fun lock() {
        _state.value = State.LOCKED
    }

    fun onKeystoreInvalidated() {
        _state.value = State.KEY_INVALIDATED
    }

    fun isUnlocked(): Boolean = _state.value == State.UNLOCKED

    /** Call after recovery completes (backup restore or reset). */
    fun resetToLocked() {
        _state.value = State.LOCKED
    }
}
