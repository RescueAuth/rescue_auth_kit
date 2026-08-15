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

    /** Total number of [tryStart] invocations (accepted or rejected). */
    var launchAttemptCount = 0

    /** Number of [tryStart] invocations that returned true (prompt accepted). */
    var successfulLaunchCount = 0

    /**
     * Sequence of [tryStart] return values, one per launch attempt. Defaults
     * to always returning true. When the list is exhausted the last value is
     * reused. Set this to e.g. `listOf(false, true)` to simulate a host that is
     * not ready on the first attempt but accepts the retry — this reproduces
     * the production lifecycle race that used to drop the request (Issue #70).
     */
    var launchResults: List<Boolean> = listOf(true)

    var cancelCalled = false
    private var callback: ((StartupAuthResult) -> Unit)? = null

    /** Backwards-compatible alias — true once at least one launch succeeded. */
    val promptLaunched: Boolean get() = successfulLaunchCount > 0

    override fun tryStart(onResult: (StartupAuthResult) -> Unit): Boolean {
        launchAttemptCount++
        val index = (launchAttemptCount - 1).coerceAtMost(launchResults.lastIndex)
        if (!launchResults[index]) {
            // Host not ready — do not accept a callback, signal failure.
            return false
        }
        successfulLaunchCount++
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
