package com.rescueauth.v2.security

import com.rescueauth.v2.session.SecureSessionStateMachine

/**
 * The single orchestration path for **fresh sensitive-action re-authentication**
 * (Phase 4 P4 / ROADMAP §5.6 / ADR-0006).
 *
 * ## Contract
 *
 * - Every high-risk action goes through [authorize]. The gate owns exactly
 *   one pending action at a time and is the only production component that
 *   launches a re-auth prompt.
 * - A successful authentication authorizes **exactly one** pending action and
 *   the authorization is consumed immediately by [executePending] — there is
 *   no freshness window, no "verified this session" cache and no global
 *   `authenticated = true`. The next sensitive action requires a new prompt.
 * - The gate is **platform-agnostic**: the prompt itself is launched by a
 *   platform [SensitiveActionPrompt] (production = [SensitiveActionController]
 *   backed by the real Android BiometricPrompt; tests inject a fake). There is
 *   NO production NoOp gate — [SensitiveActionResult.Unavailable] is returned
 *   whenever no usable authenticator exists and the sensitive action is
 *   blocked.
 *
 * ## One pending action + serialized requests
 *
 * [authorize] returns `false` when another request is already pending or a
 * prompt is showing, so a duplicate request / concurrent request (e.g.
 * "Reveal" then "Export") never spawns a second prompt. The result callback
 * is delivered to the requester of the single pending action — an action A
 * result can therefore never authorize a different action B.
 *
 * ## Lifecycle / concurrency
 *
 * - [onSessionLocked] / [onLifecyclePause] / [onLifecycleDestroy] invalidate
 *   the pending action and discard any one-shot authorization. A pending
 *   sensitive action is NEVER persisted (no SavedStateHandle / Bundle /
 *   DataStore / Room / navigation arguments), so Activity/process recreation
 *   does not resume an authorized-but-unexecuted action.
 *
 * @param prompt the platform prompt backend (production or test fake).
 * @param session the current secure session state; a locked session
 *   invalidates pending actions and discards authorizations.
 * @param titleProvider / [subtitleProvider] supply the prompt strings from
 *   Android resources so this class stays JVM-testable.
 */
class SensitiveActionGate(
    private val prompt: SensitiveActionPrompt,
    private val session: SecureSessionStateMachine,
    private val titleProvider: () -> CharSequence,
    private val subtitleProvider: () -> CharSequence?,
) {

    private var pendingAction: SensitiveAction? = null
    private var pendingCallback: ((SensitiveActionResult) -> Unit)? = null
    private var authorizedAction: SensitiveAction? = null
    private var promptActive = false

    /** The currently pending action (or null). Test-visible. */
    fun pendingActionOrNull(): SensitiveAction? = pendingAction

    /** @return true when an authorization is outstanding for [action]. */
    fun isAuthorizedFor(action: SensitiveAction): Boolean =
        authorizedAction == action

    /**
     * Requests fresh re-auth for [action].
     *
     * @return true when the request was accepted (a prompt is showing / will
     *   show, or an immediate outcome like [SensitiveActionResult.Unavailable]
     *   was delivered synchronously); false when another request is pending,
     *   a prompt is active, the session is locked or the platform cannot host
     *   a prompt right now.
     */
    fun authorize(
        action: SensitiveAction,
        onResult: (SensitiveActionResult) -> Unit,
    ): Boolean {
        if (pendingAction != null || promptActive) return false
        if (!session.isUnlocked()) return false
        pendingAction = action
        pendingCallback = onResult
        promptActive = true
        val started = prompt.tryStart(
            action = action,
            title = titleProvider(),
            subtitle = subtitleProvider(),
            onResult = { result -> onPromptResult(result) },
        )
        if (!started) {
            // Platform cannot host the prompt right now (e.g. host not
            // resumed). Release the request so a retry can re-request once
            // the host is ready.
            pendingAction = null
            pendingCallback = null
            promptActive = false
            return false
        }
        return true
    }

    /**
     * Executes [block] only when [action] was authorized by a fresh
     * successful re-auth, then **consumes** the authorization (one-shot).
     *
     * @return true when the authorization was valid and [block] ran.
     */
    fun <T> executePending(action: SensitiveAction, block: () -> T): Boolean {
        if (authorizedAction != action) return false
        authorizedAction = null
        block()
        return true
    }

    /** Cancels the pending action without executing anything. */
    fun cancelPending() {
        pendingAction = null
        pendingCallback = null
        authorizedAction = null
        promptActive = false
        prompt.cancel()
    }

    /**
     * Invalidates any pending action / authorization and cancels the prompt
     * (session lock / lifecycle pause / destroy).
     */
    fun invalidate() {
        pendingAction = null
        pendingCallback = null
        authorizedAction = null
        promptActive = false
        prompt.cancel()
    }

    /** Called when the host activity pauses — clears any pending auth. */
    fun onLifecyclePause() {
        invalidate()
        prompt.onLifecyclePause()
    }

    /** Called when the host activity is destroyed — clears any pending auth. */
    fun onLifecycleDestroy() {
        invalidate()
        prompt.onLifecycleDestroy()
    }

    private fun onPromptResult(result: SensitiveActionResult) {
        promptActive = false
        when (result) {
            is SensitiveActionResult.Success -> {
                // Authorize exactly the pending action; the authorization is
                // consumed by the next executePending call.
                authorizedAction = result.action
                pendingAction = null
            }
            SensitiveActionResult.Cancelled,
            SensitiveActionResult.Failed,
            SensitiveActionResult.Unavailable,
            -> {
                pendingAction = null
                authorizedAction = null
            }
        }
        val cb = pendingCallback
        pendingCallback = null
        cb?.invoke(result)
    }
}

/**
 * Platform prompt backend for [SensitiveActionGate].
 *
 * Production injects [SensitiveActionController] (real Android
 * BiometricPrompt). Tests inject a fake that simulates success / cancel /
 * failure / unavailable deterministically — they never touch biometric
 * hardware (AGENTS / Issue #20 §25).
 */
interface SensitiveActionPrompt {
    fun tryStart(
        action: SensitiveAction,
        title: CharSequence,
        subtitle: CharSequence?,
        onResult: (SensitiveActionResult) -> Unit,
    ): Boolean

    fun cancel() = Unit
    fun onLifecyclePause() = Unit
    fun onLifecycleDestroy() = Unit
}
