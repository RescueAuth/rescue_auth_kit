package com.rescueauth.v2.security

/**
 * Test double for [StartupAuthPrompt] that simulates the system auth prompt
 * deterministically — tests never touch real biometric hardware (Issue #50
 * §13).
 *
 * Defaults to an auto-resolved authenticator mask so the caller behaves as if
 * a device credential is available. Set [availableAuthenticators] to null to
 * simulate a device with no secure lock.
 */
class FakeStartupAuthPrompt(
    var availableAuthenticators: Int? = 1, // non-null = usable authenticator
    var nextResult: StartupAuthResult = StartupAuthResult.Success,
) : StartupAuthPrompt {

    var promptLaunched = false
    var cancelCalled = false
    private var callback: ((StartupAuthResult) -> Unit)? = null

    override fun tryStart(onResult: (StartupAuthResult) -> Unit): Boolean {
        promptLaunched = true
        callback = onResult
        // Simulate delivery on the next call of deliver().
        return true
    }

    override fun resolveAvailableAuthenticators(): Int? = availableAuthenticators

    override fun isPromptActive(): Boolean = callback != null

    override fun cancel() {
        cancelCalled = true
        deliver(StartupAuthResult.Cancelled)
    }

    /** Delivers the configured [nextResult] once (as if the system returned). */
    fun deliver(result: StartupAuthResult = nextResult) {
        val cb = callback ?: return
        callback = null
        cb(result)
    }
}
