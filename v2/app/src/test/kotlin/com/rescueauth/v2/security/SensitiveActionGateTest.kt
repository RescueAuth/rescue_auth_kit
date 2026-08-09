package com.rescueauth.v2.security

import com.rescueauth.v2.session.SecureSessionStateMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4 P4 Sensitive Action Gate tests (Issue #20 §25):
 *
 * 10. one pending sensitive action
 * 11. duplicate request does not spawn multiple prompts
 * 12. action A auth result cannot authorize action B
 * 13. cancel clears pending action
 * 14. failure clears pending action
 * 15. Activity lifecycle destruction clears pending authorization
 * 16. session lock clears authorization/reveal state
 * 17. no auth result persisted across recreation
 *
 * The Android prompt itself is never invoked here — a fake prompt boundary is
 * used (never "pretend biometric hardware" on the JVM).
 */
class SensitiveActionGateTest {

    private fun unlockedSession(): SecureSessionStateMachine {
        val s = SecureSessionStateMachine()
        s.beginAuthentication()
        s.onAuthenticationSuccess()
        return s
    }

    private fun gate(
        prompt: FakeSensitiveActionPrompt = FakeSensitiveActionPrompt(),
        session: SecureSessionStateMachine = unlockedSession(),
    ): SensitiveActionGate = SensitiveActionGate(
        prompt = prompt,
        session = session,
        titleProvider = { "Authenticate to continue" },
        subtitleProvider = { null },
    )

    // ---- 10. one pending sensitive action ----

    @Test
    fun `only one pending action at a time`() {
        val prompt = FakeSensitiveActionPrompt()
        val g = gate(prompt)

        assertTrue(g.authorize(SensitiveAction.REVEAL_API_SECRET) {})
        assertEquals(SensitiveAction.REVEAL_API_SECRET, g.pendingActionOrNull())

        // A second request while the first is pending is rejected.
        assertFalse(g.authorize(SensitiveAction.EXPORT_FULL_VAULT) {})
        assertEquals(1, prompt.startedActions.size)
        assertEquals(SensitiveAction.REVEAL_API_SECRET, g.pendingActionOrNull())
    }

    // ---- 11. duplicate request does not spawn multiple prompts ----

    @Test
    fun `duplicate request while prompt showing does not spawn a second prompt`() {
        val prompt = FakeSensitiveActionPrompt()
        val g = gate(prompt)

        assertTrue(g.authorize(SensitiveAction.REVEAL_API_SECRET) {})
        // Duplicate while prompt is showing.
        assertFalse(g.authorize(SensitiveAction.REVEAL_API_SECRET) {})
        assertFalse(g.authorize(SensitiveAction.REVEAL_SSH_PRIVATE_KEY) {})
        assertEquals(1, prompt.startedActions.size)
    }

    // ---- 12. action A auth result cannot authorize action B ----

    @Test
    fun `action A success cannot authorize action B`() {
        val prompt = FakeSensitiveActionPrompt()
        val g = gate(prompt)

        var resultA: SensitiveActionResult? = null
        assertTrue(g.authorize(SensitiveAction.REVEAL_API_SECRET) { resultA = it })
        prompt.deliver(SensitiveActionResult.Success(SensitiveAction.REVEAL_API_SECRET))
        assertTrue(resultA is SensitiveActionResult.Success)

        // Authorization is scoped to A: executing B is rejected.
        var ranB = false
        assertFalse(g.executePending(SensitiveAction.REVEAL_SSH_PRIVATE_KEY) { ranB = true })
        assertFalse(ranB)

        // And executing A consumes it exactly once.
        var ranA1 = false
        var ranA2 = false
        assertTrue(g.executePending(SensitiveAction.REVEAL_API_SECRET) { ranA1 = true })
        assertTrue(ranA1)
        assertFalse(g.executePending(SensitiveAction.REVEAL_API_SECRET) { ranA2 = true })
        assertFalse(ranA2)
    }

    // ---- 13. cancel clears pending action ----

    @Test
    fun `cancel clears the pending action`() {
        val prompt = FakeSensitiveActionPrompt()
        val g = gate(prompt)

        var result: SensitiveActionResult? = null
        assertTrue(g.authorize(SensitiveAction.EXPORT_FULL_VAULT) { result = it })
        assertEquals(SensitiveAction.EXPORT_FULL_VAULT, g.pendingActionOrNull())

        prompt.deliver(SensitiveActionResult.Cancelled)
        assertNull(g.pendingActionOrNull())
        assertFalse(g.isAuthorizedFor(SensitiveAction.EXPORT_FULL_VAULT))
        assertTrue(result is SensitiveActionResult.Cancelled)

        // After cancel the next request starts a fresh prompt.
        assertTrue(g.authorize(SensitiveAction.EXPORT_FULL_VAULT) {})
        assertEquals(2, prompt.startedActions.size)
    }

    // ---- 14. failure clears pending action ----

    @Test
    fun `failure clears the pending action and does not authorize`() {
        val prompt = FakeSensitiveActionPrompt()
        val g = gate(prompt)

        var result: SensitiveActionResult? = null
        assertTrue(g.authorize(SensitiveAction.REVEAL_API_SECRET) { result = it })
        prompt.deliver(SensitiveActionResult.Failed)
        assertNull(g.pendingActionOrNull())
        assertTrue(result is SensitiveActionResult.Failed)
        var ran = false
        assertFalse(g.executePending(SensitiveAction.REVEAL_API_SECRET) { ran = true })
        assertFalse(ran)
    }

    // ---- 15. lifecycle destruction clears pending authorization ----

    @Test
    fun `lifecycle destroy clears pending authorization`() {
        val prompt = FakeSensitiveActionPrompt()
        val g = gate(prompt)

        var result: SensitiveActionResult? = null
        assertTrue(g.authorize(SensitiveAction.REVEAL_GENERIC_SECRET) { result = it })
        prompt.deliver(SensitiveActionResult.Success(SensitiveAction.REVEAL_GENERIC_SECRET))
        assertTrue(g.isAuthorizedFor(SensitiveAction.REVEAL_GENERIC_SECRET))

        g.onLifecycleDestroy()
        assertNull(g.pendingActionOrNull())
        assertFalse(g.isAuthorizedFor(SensitiveAction.REVEAL_GENERIC_SECRET))
    }

    // ---- 16. session lock clears authorization/reveal state ----

    @Test
    fun `session lock clears authorization`() {
        val session = unlockedSession()
        val prompt = FakeSensitiveActionPrompt()
        val g = gate(prompt, session)

        var result: SensitiveActionResult? = null
        assertTrue(g.authorize(SensitiveAction.REVEAL_SSH_PRIVATE_KEY) { result = it })
        prompt.deliver(SensitiveActionResult.Success(SensitiveAction.REVEAL_SSH_PRIVATE_KEY))
        assertTrue(g.isAuthorizedFor(SensitiveAction.REVEAL_SSH_PRIVATE_KEY))

        // MainActivity wires a session-state collector that calls
        // gate.invalidate() when the session leaves UNLOCKED (Issue #20 §6).
        session.lock()
        g.invalidate()
        assertNull(g.pendingActionOrNull())
        assertFalse(g.isAuthorizedFor(SensitiveAction.REVEAL_SSH_PRIVATE_KEY))
    }

    // ---- 17. no auth result persisted across recreation ----

    @Test
    fun `new gate instance after recreation has no pending authorization`() {
        val prompt = FakeSensitiveActionPrompt()
        val g1 = gate(prompt)
        var result: SensitiveActionResult? = null
        assertTrue(g1.authorize(SensitiveAction.EXPORT_FULL_VAULT) { result = it })
        prompt.deliver(SensitiveActionResult.Success(SensitiveAction.EXPORT_FULL_VAULT))

        // Simulate Activity/process recreation: a brand-new gate (fresh
        // controller + gate, as MainActivity does on onCreate) holds nothing.
        val g2 = gate(FakeSensitiveActionPrompt())
        assertNull(g2.pendingActionOrNull())
        assertFalse(g2.isAuthorizedFor(SensitiveAction.EXPORT_FULL_VAULT))
        var ran = false
        assertFalse(g2.executePending(SensitiveAction.EXPORT_FULL_VAULT) { ran = true })
        assertFalse(ran)
    }

    // ---- unavailable blocks (no silent bypass) ----

    @Test
    fun `unavailable authenticator blocks the action`() {
        val prompt = FakeSensitiveActionPrompt(autoResult = SensitiveActionResult.Unavailable)
        val g = gate(prompt)

        var result: SensitiveActionResult? = null
        assertTrue(g.authorize(SensitiveAction.REVEAL_API_SECRET) { result = it })
        assertTrue(result is SensitiveActionResult.Unavailable)
        assertNull(g.pendingActionOrNull())
        var ran = false
        assertFalse(g.executePending(SensitiveAction.REVEAL_API_SECRET) { ran = true })
        assertFalse(ran)
    }

    // ---- locked session rejects before any prompt ----

    @Test
    fun `locked session rejects authorize without a prompt`() {
        val session = SecureSessionStateMachine() // LOCKED
        val prompt = FakeSensitiveActionPrompt()
        val g = gate(prompt, session)

        assertFalse(g.authorize(SensitiveAction.EXPORT_FULL_VAULT) {})
        assertEquals(0, prompt.startedActions.size)
    }

    // ---- cancelPending discards and allows a fresh request ----

    @Test
    fun `cancelPending discards the request`() {
        val prompt = FakeSensitiveActionPrompt()
        val g = gate(prompt)

        assertTrue(g.authorize(SensitiveAction.REVEAL_API_SECRET) {})
        g.cancelPending()
        assertNull(g.pendingActionOrNull())
        assertFalse(g.isAuthorizedFor(SensitiveAction.REVEAL_API_SECRET))
        assertEquals(1, prompt.cancelled)

        // Fresh request works afterwards.
        assertTrue(g.authorize(SensitiveAction.REVEAL_API_SECRET) {})
    }
}
