package com.rescueauth.v2.security

import com.rescueauth.v2.session.SecureSessionStateMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4 P4 Sensitive Action Gate tests (Issue #20 §25 + security-boundary CR):
 *
 * 10. one pending sensitive action (request)
 * 11. duplicate request does not spawn multiple prompts
 * 12. action A auth result cannot authorize action B
 * 13. cancel clears pending action
 * 14. failure clears pending action
 * 15. Activity lifecycle destruction clears pending authorization
 * 16. session lock clears authorization/reveal state
 * 17. no auth result persisted across recreation
 *
 * Plus the security-boundary CR race/security tests:
 * - reveal Generic field A auth success cannot reveal field B
 * - copy Generic field A auth success cannot copy field B
 * - reveal authorization cannot authorize copy
 * - SSH passphrase reveal authorization cannot authorize passphrase copy
 * - Entry A request pending while switching to Entry B: auth success still
 *   applies to original A only (or safely cancels)
 * - second same-type request while prompt active does not replace original
 *   target
 * - session lock invalidates pending target
 *
 * The Android prompt itself is never invoked here — a fake prompt boundary is
 * used (never "pretend biometric hardware" on the JVM).
 */
class SensitiveActionGateTest {

    @Test fun `invalidation notifies the waiting action that authentication was cancelled`() {
        val prompt = FakeSensitiveActionPrompt()
        val g = gate(prompt)
        var result: SensitiveActionResult? = null
        g.authorize(revealApi()) { result = it }
        g.onLifecyclePause()
        assertEquals(SensitiveActionResult.Cancelled, result)
    }

    @Test fun `late callback from an invalidated prompt cannot authorize a retry of the same action`() {
        val prompt = FakeSensitiveActionPrompt()
        val g = gate(prompt)
        val request = revealApi()
        g.authorize(request) {}
        val stale = prompt.lastCallback!!
        g.invalidate()
        var current: SensitiveActionResult? = null
        g.authorize(request) { current = it }
        stale(SensitiveActionResult.Success(request))
        assertNull(current)
        assertFalse(g.isAuthorizedFor(request))
        assertEquals(request, g.pendingRequestOrNull())
        prompt.deliver(SensitiveActionResult.Success(request))
        assertTrue(current is SensitiveActionResult.Success)
    }

    @Test fun `success for a different target is denied at the gate`() {
        val prompt = FakeSensitiveActionPrompt()
        val g = gate(prompt)
        var result: SensitiveActionResult? = null
        g.authorize(revealApi(stableId = "entry-A")) { result = it }
        prompt.deliver(SensitiveActionResult.Success(revealApi(stableId = "entry-B")))
        assertEquals(SensitiveActionResult.Failed, result)
        assertFalse(g.isAuthorizedFor(revealApi(stableId = "entry-B")))
    }

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

    private fun revealApi(
        stableId: String = "entry-a",
        fieldKey: String = "apiSecret",
    ) = SensitiveActionRequest(
        action = SensitiveAction.REVEAL_API_SECRET,
        target = SensitiveActionTarget.DeveloperField(stableId, fieldKey),
    )

    private fun copyApi(
        stableId: String = "entry-a",
        fieldKey: String = "apiSecret",
    ) = SensitiveActionRequest(
        action = SensitiveAction.COPY_API_SECRET,
        target = SensitiveActionTarget.DeveloperField(stableId, fieldKey),
    )

    private fun revealGeneric(
        stableId: String = "entry-a",
        fieldKey: String = "field:label",
    ) = SensitiveActionRequest(
        action = SensitiveAction.REVEAL_GENERIC_SECRET,
        target = SensitiveActionTarget.DeveloperField(stableId, fieldKey),
    )

    private fun copyGeneric(
        stableId: String = "entry-a",
        fieldKey: String = "field:label",
    ) = SensitiveActionRequest(
        action = SensitiveAction.COPY_GENERIC_SECRET,
        target = SensitiveActionTarget.DeveloperField(stableId, fieldKey),
    )

    private fun revealPassphrase(
        stableId: String = "entry-a",
    ) = SensitiveActionRequest(
        action = SensitiveAction.REVEAL_SSH_PASSPHRASE,
        target = SensitiveActionTarget.DeveloperField(stableId, "passphrase"),
    )

    private fun copyPassphrase(
        stableId: String = "entry-a",
    ) = SensitiveActionRequest(
        action = SensitiveAction.COPY_SSH_PASSPHRASE,
        target = SensitiveActionTarget.DeveloperField(stableId, "passphrase"),
    )

    private fun export() = SensitiveActionRequest(
        action = SensitiveAction.EXPORT_PACKAGE,
        target = SensitiveActionTarget.ExportRequest(
            scopeName = "FullVault",
            selectionDigest = null,
        ),
    )

    // ---- 10. one pending action ----

    @Test
    fun `only one pending request at a time`() {
        val prompt = FakeSensitiveActionPrompt()
        val g = gate(prompt)

        assertTrue(g.authorize(revealApi()) {})
        assertEquals(revealApi(), g.pendingRequestOrNull())

        // A second request while the first is pending is rejected.
        assertFalse(g.authorize(export()) {})
        assertEquals(1, prompt.startedRequests.size)
        assertEquals(revealApi(), g.pendingRequestOrNull())
    }

    // ---- 11. duplicate request does not spawn multiple prompts ----

    @Test
    fun `duplicate request while prompt showing does not spawn a second prompt`() {
        val prompt = FakeSensitiveActionPrompt()
        val g = gate(prompt)

        assertTrue(g.authorize(revealApi()) {})
        // Duplicate while prompt is showing.
        assertFalse(g.authorize(revealApi()) {})
        assertFalse(g.authorize(revealGeneric()) {})
        assertEquals(1, prompt.startedRequests.size)
    }

    // ---- 12. action A auth result cannot authorize action B ----

    @Test
    fun `request A success cannot authorize request B`() {
        val prompt = FakeSensitiveActionPrompt()
        val g = gate(prompt)

        var resultA: SensitiveActionResult? = null
        assertTrue(g.authorize(revealApi()) { resultA = it })
        prompt.deliver(SensitiveActionResult.Success(revealApi()))
        assertTrue(resultA is SensitiveActionResult.Success)

        // Authorization is scoped to A (action + target): executing B is
        // rejected even if B is the same action on a different field/entry.
        var ranB = false
        assertFalse(g.executePending(revealGeneric()) { ranB = true })
        assertFalse(ranB)
        var ranDifferentField = false
        assertFalse(g.executePending(revealApi(fieldKey = "apiKey")) { ranDifferentField = true })
        assertFalse(ranDifferentField)
        var ranDifferentEntry = false
        assertFalse(g.executePending(revealApi(stableId = "entry-b")) { ranDifferentEntry = true })
        assertFalse(ranDifferentEntry)

        // And executing A consumes it exactly once.
        var ranA1 = false
        var ranA2 = false
        assertTrue(g.executePending(revealApi()) { ranA1 = true })
        assertTrue(ranA1)
        assertFalse(g.executePending(revealApi()) { ranA2 = true })
        assertFalse(ranA2)
    }

    // ---- 13. cancel clears pending action ----

    @Test
    fun `cancel clears the pending request`() {
        val prompt = FakeSensitiveActionPrompt()
        val g = gate(prompt)

        var result: SensitiveActionResult? = null
        assertTrue(g.authorize(export()) { result = it })
        assertEquals(export(), g.pendingRequestOrNull())

        prompt.deliver(SensitiveActionResult.Cancelled)
        assertNull(g.pendingRequestOrNull())
        assertFalse(g.isAuthorizedFor(export()))
        assertTrue(result is SensitiveActionResult.Cancelled)

        // After cancel the next request starts a fresh prompt.
        assertTrue(g.authorize(export()) {})
        assertEquals(2, prompt.startedRequests.size)
    }

    // ---- 14. failure clears pending action ----

    @Test
    fun `failure clears the pending request and does not authorize`() {
        val prompt = FakeSensitiveActionPrompt()
        val g = gate(prompt)

        var result: SensitiveActionResult? = null
        assertTrue(g.authorize(revealApi()) { result = it })
        prompt.deliver(SensitiveActionResult.Failed)
        assertNull(g.pendingRequestOrNull())
        assertTrue(result is SensitiveActionResult.Failed)
        var ran = false
        assertFalse(g.executePending(revealApi()) { ran = true })
        assertFalse(ran)
    }

    // ---- 15. lifecycle destruction clears pending authorization ----

    @Test
    fun `lifecycle destroy clears pending authorization`() {
        val prompt = FakeSensitiveActionPrompt()
        val g = gate(prompt)

        var result: SensitiveActionResult? = null
        assertTrue(g.authorize(revealGeneric()) { result = it })
        prompt.deliver(SensitiveActionResult.Success(revealGeneric()))
        assertTrue(g.isAuthorizedFor(revealGeneric()))

        g.onLifecycleDestroy()
        assertNull(g.pendingRequestOrNull())
        assertFalse(g.isAuthorizedFor(revealGeneric()))
    }

    // ---- 16. session lock clears authorization/reveal state ----

    @Test
    fun `session lock clears authorization`() {
        val session = unlockedSession()
        val prompt = FakeSensitiveActionPrompt()
        val g = gate(prompt, session)

        var result: SensitiveActionResult? = null
        assertTrue(g.authorize(revealGeneric()) { result = it })
        prompt.deliver(SensitiveActionResult.Success(revealGeneric()))
        assertTrue(g.isAuthorizedFor(revealGeneric()))

        // MainActivity wires a session-state collector that calls
        // gate.invalidate() when the session leaves UNLOCKED (Issue #20 §6).
        session.lock()
        g.invalidate()
        assertNull(g.pendingRequestOrNull())
        assertFalse(g.isAuthorizedFor(revealGeneric()))
    }

    // ---- 17. no auth result persisted across recreation ----

    @Test
    fun `new gate instance after recreation has no pending authorization`() {
        val prompt = FakeSensitiveActionPrompt()
        val g1 = gate(prompt)
        var result: SensitiveActionResult? = null
        assertTrue(g1.authorize(export()) { result = it })
        prompt.deliver(SensitiveActionResult.Success(export()))

        // Simulate Activity/process recreation: a brand-new gate (fresh
        // controller + gate, as MainActivity does on onCreate) holds nothing.
        val g2 = gate(FakeSensitiveActionPrompt())
        assertNull(g2.pendingRequestOrNull())
        assertFalse(g2.isAuthorizedFor(export()))
        var ran = false
        assertFalse(g2.executePending(export()) { ran = true })
        assertFalse(ran)
    }

    // ---- unavailable blocks (no silent bypass) ----

    @Test
    fun `unavailable authenticator blocks the request`() {
        val prompt = FakeSensitiveActionPrompt(autoResult = SensitiveActionResult.Unavailable)
        val g = gate(prompt)

        var result: SensitiveActionResult? = null
        assertTrue(g.authorize(revealApi()) { result = it })
        assertTrue(result is SensitiveActionResult.Unavailable)
        assertNull(g.pendingRequestOrNull())
        var ran = false
        assertFalse(g.executePending(revealApi()) { ran = true })
        assertFalse(ran)
    }

    // ---- locked session rejects before any prompt ----

    @Test
    fun `locked session rejects authorize without a prompt`() {
        val session = SecureSessionStateMachine() // LOCKED
        val prompt = FakeSensitiveActionPrompt()
        val g = gate(prompt, session)

        assertFalse(g.authorize(export()) {})
        assertEquals(0, prompt.startedRequests.size)
    }

    // ---- cancelPending discards and allows a fresh request ----

    @Test
    fun `cancelPending discards the request`() {
        val prompt = FakeSensitiveActionPrompt()
        val g = gate(prompt)

        assertTrue(g.authorize(revealApi()) {})
        g.cancelPending()
        assertNull(g.pendingRequestOrNull())
        assertFalse(g.isAuthorizedFor(revealApi()))
        assertEquals(1, prompt.cancelled)

        // Fresh request works afterwards.
        assertTrue(g.authorize(revealApi()) {})
    }

    // ------------------------------------------------------------------
    // Security-boundary CR §1: reveal vs copy fully separated
    // ------------------------------------------------------------------

    @Test
    fun `reveal authorization cannot authorize copy`() {
        val prompt = FakeSensitiveActionPrompt()
        val g = gate(prompt)

        var result: SensitiveActionResult? = null
        assertTrue(g.authorize(revealApi()) { result = it })
        prompt.deliver(SensitiveActionResult.Success(revealApi()))
        assertTrue(g.isAuthorizedFor(revealApi()))

        // Reveal authorization cannot execute a COPY request (different action).
        var copied = false
        assertFalse(g.executePending(copyApi()) { copied = true })
        assertFalse(copied)

        // Reveal still executes its own request exactly once.
        var revealed = false
        assertTrue(g.executePending(revealApi()) { revealed = true })
        assertTrue(revealed)
    }

    @Test
    fun `ssh passphrase reveal authorization cannot authorize passphrase copy`() {
        val prompt = FakeSensitiveActionPrompt()
        val g = gate(prompt)

        var result: SensitiveActionResult? = null
        assertTrue(g.authorize(revealPassphrase()) { result = it })
        prompt.deliver(SensitiveActionResult.Success(revealPassphrase()))
        assertTrue(g.isAuthorizedFor(revealPassphrase()))

        // Passphrase COPY requires its own fresh re-auth.
        var copied = false
        assertFalse(g.executePending(copyPassphrase()) { copied = true })
        assertFalse(copied)

        var revealed = false
        assertTrue(g.executePending(revealPassphrase()) { revealed = true })
        assertTrue(revealed)
    }

    // ------------------------------------------------------------------
    // Security-boundary CR §2: target binding (stableId + fieldKey)
    // ------------------------------------------------------------------

    @Test
    fun `reveal generic field A auth success cannot reveal field B`() {
        val prompt = FakeSensitiveActionPrompt()
        val g = gate(prompt)

        val fieldA = revealGeneric(fieldKey = "field:A")
        val fieldB = revealGeneric(fieldKey = "field:B")
        var result: SensitiveActionResult? = null
        assertTrue(g.authorize(fieldA) { result = it })
        prompt.deliver(SensitiveActionResult.Success(fieldA))
        assertTrue(result is SensitiveActionResult.Success)

        // The authorization is bound to field A; field B cannot consume it.
        var revealedB = false
        assertFalse(g.executePending(fieldB) { revealedB = true })
        assertFalse(revealedB)

        // Field A consumes it exactly once.
        var revealedA = false
        assertTrue(g.executePending(fieldA) { revealedA = true })
        assertTrue(revealedA)
        assertFalse(g.executePending(fieldA) { revealedA = true })
    }

    @Test
    fun `copy generic field A auth success cannot copy field B`() {
        val prompt = FakeSensitiveActionPrompt()
        val g = gate(prompt)

        val fieldA = copyGeneric(fieldKey = "field:A")
        val fieldB = copyGeneric(fieldKey = "field:B")
        var result: SensitiveActionResult? = null
        assertTrue(g.authorize(fieldA) { result = it })
        prompt.deliver(SensitiveActionResult.Success(fieldA))
        assertTrue(result is SensitiveActionResult.Success)

        var copiedB = false
        assertFalse(g.executePending(fieldB) { copiedB = true })
        assertFalse(copiedB)

        var copiedA = false
        assertTrue(g.executePending(fieldA) { copiedA = true })
        assertTrue(copiedA)
    }

    @Test
    fun `entry A request pending switching to entry B - auth success applies to A only`() {
        val prompt = FakeSensitiveActionPrompt()
        val g = gate(prompt)

        val entryA = revealApi(stableId = "entry-A", fieldKey = "apiSecret")
        val entryB = revealApi(stableId = "entry-B", fieldKey = "apiSecret")
        var result: SensitiveActionResult? = null
        assertTrue(g.authorize(entryA) { result = it })

        // While the prompt is showing the user navigates to entry B and
        // requests the same-type action there. It must NOT replace the
        // original target.
        assertFalse(g.authorize(entryB) { })
        assertEquals(entryA, g.pendingRequestOrNull())
        assertEquals(1, prompt.startedRequests.size)

        // Auth succeeds: only entry A can consume it.
        prompt.deliver(SensitiveActionResult.Success(entryA))
        var revealedB = false
        assertFalse(g.executePending(entryB) { revealedB = true })
        assertFalse(revealedB)
        var revealedA = false
        assertTrue(g.executePending(entryA) { revealedA = true })
        assertTrue(revealedA)
    }

    @Test
    fun `second same-type request while prompt active does not replace original target`() {
        val prompt = FakeSensitiveActionPrompt()
        val g = gate(prompt)

        val original = copyGeneric(stableId = "entry-A", fieldKey = "field:A")
        val second = copyGeneric(stableId = "entry-A", fieldKey = "field:B")
        var result: SensitiveActionResult? = null
        assertTrue(g.authorize(original) { result = it })

        // A second same-type request while the prompt is active is rejected —
        // it does NOT replace the original target.
        assertFalse(g.authorize(second) { })
        assertEquals(original, g.pendingRequestOrNull())

        prompt.deliver(SensitiveActionResult.Success(original))
        assertTrue(g.isAuthorizedFor(original))
        var copiedB = false
        assertFalse(g.executePending(second) { copiedB = true })
        assertFalse(copiedB)
        var copiedA = false
        assertTrue(g.executePending(original) { copiedA = true })
        assertTrue(copiedA)
    }

    @Test
    fun `session lock invalidates a pending target`() {
        val session = unlockedSession()
        val prompt = FakeSensitiveActionPrompt()
        val g = gate(prompt, session)

        var result: SensitiveActionResult? = null
        assertTrue(g.authorize(revealApi(stableId = "entry-A", fieldKey = "apiSecret")) { result = it })
        assertEquals(
            revealApi(stableId = "entry-A", fieldKey = "apiSecret"),
            g.pendingRequestOrNull(),
        )

        // Lock while the prompt is pending: the target is invalidated.
        session.lock()
        g.invalidate()
        assertNull(g.pendingRequestOrNull())
        assertFalse(g.isAuthorizedFor(revealApi(stableId = "entry-A", fieldKey = "apiSecret")))
        var ran = false
        assertFalse(g.executePending(revealApi(stableId = "entry-A", fieldKey = "apiSecret")) { ran = true })
        assertFalse(ran)
    }

    @Test
    fun `successful auth is one-shot for the exact request`() {
        val prompt = FakeSensitiveActionPrompt()
        val g = gate(prompt)

        var result: SensitiveActionResult? = null
        assertTrue(g.authorize(revealGeneric(stableId = "entry-A", fieldKey = "field:A")) { result = it })
        prompt.deliver(
            SensitiveActionResult.Success(revealGeneric(stableId = "entry-A", fieldKey = "field:A")),
        )
        assertTrue(result is SensitiveActionResult.Success)

        // First execution consumes it.
        var ran1 = false
        assertTrue(g.executePending(revealGeneric(stableId = "entry-A", fieldKey = "field:A")) { ran1 = true })
        assertTrue(ran1)

        // No second use — neither for the same request nor any other.
        var ran2 = false
        assertFalse(g.executePending(revealGeneric(stableId = "entry-A", fieldKey = "field:A")) { ran2 = true })
        assertFalse(ran2)
        var ranB = false
        assertFalse(g.executePending(copyGeneric(stableId = "entry-A", fieldKey = "field:A")) { ranB = true })
        assertFalse(ranB)
    }
}
