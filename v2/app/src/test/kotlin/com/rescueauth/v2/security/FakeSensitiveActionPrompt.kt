package com.rescueauth.v2.security

/**
 * Test fake for [SensitiveActionPrompt] — simulates fresh re-auth outcomes
 * deterministically WITHOUT touching biometric hardware (Issue #20 §25).
 *
 * - [autoResult] when set delivers that result synchronously on the next
 *   [tryStart]; otherwise the caller controls delivery via [lastCallback].
 * - Records every start for prompt-count assertions (duplicate-request race
 *   tests).
 */
class FakeSensitiveActionPrompt(
    var autoResult: SensitiveActionResult? = null,
    var startResult: Boolean = true,
) : SensitiveActionPrompt {

    val startedActions = mutableListOf<SensitiveAction>()
    var lastCallback: ((SensitiveActionResult) -> Unit)? = null
    var cancelled = 0

    override fun tryStart(
        action: SensitiveAction,
        title: CharSequence,
        subtitle: CharSequence?,
        onResult: (SensitiveActionResult) -> Unit,
    ): Boolean {
        startedActions += action
        if (!startResult) return false
        lastCallback = onResult
        autoResult?.let {
            lastCallback = null
            onResult(it)
        }
        return true
    }

    override fun cancel() {
        cancelled++
    }

    /** Simulates the user / system delivering a result. */
    fun deliver(result: SensitiveActionResult) {
        lastCallback?.invoke(result)
        lastCallback = null
    }
}
