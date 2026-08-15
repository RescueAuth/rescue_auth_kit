package com.rescueauth.v2.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureSessionStateMachineTest {

    @Test
    fun `starts locked`() {
        val sm = SecureSessionStateMachine()
        assertEquals(SecureSessionStateMachine.State.LOCKED, sm.state.value)
        assertFalse(sm.isUnlocked())
    }

    @Test
    fun `full unlock lock cycle`() {
        val sm = SecureSessionStateMachine()
        assertTrue(sm.beginAuthentication())
        assertEquals(SecureSessionStateMachine.State.AUTHENTICATING, sm.state.value)
        sm.onAuthenticationSuccess()
        assertEquals(SecureSessionStateMachine.State.UNLOCKED, sm.state.value)
        assertTrue(sm.isUnlocked())

        sm.lock()
        assertEquals(SecureSessionStateMachine.State.LOCKED, sm.state.value)
        assertFalse(sm.isUnlocked())
    }

    @Test
    fun `authentication failure rolls back to locked`() {
        val sm = SecureSessionStateMachine()
        assertTrue(sm.beginAuthentication())
        sm.onAuthenticationFailure()
        assertEquals(SecureSessionStateMachine.State.LOCKED, sm.state.value)
    }

    @Test
    fun `beginAuthentication while unlocked is rejected`() {
        val sm = SecureSessionStateMachine()
        sm.beginAuthentication()
        sm.onAuthenticationSuccess()
        assertFalse(sm.beginAuthentication())
        assertEquals(SecureSessionStateMachine.State.UNLOCKED, sm.state.value)
    }

    @Test
    fun `keystore invalidation moves to KEY_INVALIDATED and stays`() {
        val sm = SecureSessionStateMachine()
        sm.beginAuthentication()
        sm.onAuthenticationSuccess()
        sm.onKeystoreInvalidated()
        assertEquals(SecureSessionStateMachine.State.KEY_INVALIDATED, sm.state.value)
        // From KEY_INVALIDATED we cannot begin auth; recovery must reset first.
        assertFalse(sm.beginAuthentication())
        sm.resetToLocked()
        assertEquals(SecureSessionStateMachine.State.LOCKED, sm.state.value)
        assertTrue(sm.beginAuthentication())
    }

    @Test
    fun `lock from any non-locked state returns to locked`() {
        val sm = SecureSessionStateMachine()
        sm.beginAuthentication()
        sm.lock()
        assertEquals(SecureSessionStateMachine.State.LOCKED, sm.state.value)
    }
}
