package com.rescueauth.v2.security

/**
 * Outcome of an authorization request against a [SensitiveActionGate].
 */
sealed interface SensitiveActionResult {
    /**
     * The pending action was authorized by a **fresh** successful
     * authentication. The authorization is **one-shot**: the gate consumed it
     * for exactly this pending action and the caller must either execute the
     * action or abandon it — there is nothing reusable left.
     */
    data class Success(val action: SensitiveAction) : SensitiveActionResult

    /**
     * The user cancelled the prompt. The pending action was NOT authorized and
     * the sensitive operation MUST NOT run.
     */
    object Cancelled : SensitiveActionResult

    /**
     * Authentication failed (e.g. biometric not recognized after the system
     * closed the prompt, transient error). The pending action was NOT
     * authorized.
     */
    object Failed : SensitiveActionResult

    /**
     * No usable authenticator exists on this device (no biometric enrolled
     * and no device credential available). Sensitive actions are **blocked**:
     * there is never a silent bypass (AGENTS 禁区 / no NoOp gate in
     * production).
     */
    object Unavailable : SensitiveActionResult
}
