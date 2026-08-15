package com.rescueauth.v2.security

import com.rescueauth.v2.session.SecureSessionStateMachine

/**
 * The single orchestration path for **fresh sensitive-action re-authentication**
 * (Phase 4 P4 / ROADMAP §5.6 / ADR-0006 / ADR-0010).
 *
 * ## Contract
 *
 * - Every high-risk action goes through [authorize]. The gate owns exactly
 *   one pending request at a time and is the only production component that
 *   launches a re-auth prompt.
 * - A successful authentication authorizes **exactly one** pending request
 *   (action + target) and the authorization is consumed immediately by
 *   [executePending] — there is no freshness window, no "verified this
 *   session" cache and no global `authenticated = true`. The next sensitive
 *   request requires a new prompt.
 * - The gate is **platform-agnostic**: the prompt itself is launched by a
 *   platform [SensitiveActionPrompt] (production = [SensitiveActionController]
 *   backed by the real Android BiometricPrompt; tests inject a fake). There is
 *   NO production NoOp gate — [SensitiveActionResult.Unavailable] is returned
 *   whenever no usable authenticator exists and the sensitive action is
 *   blocked.
 *
 * ## Precise request / target binding
 *
 * The pending request is a [SensitiveActionRequest] that binds the operation
 * ([SensitiveAction]) to an immutable [SensitiveActionTarget] (e.g. a specific
 * Developer entry `stableId` + `fieldKey`). The gate compares by full value
 * equality, so a successful re-auth can never be applied to a different
 * entry / field / operation — even if the current selection, navigation or a
 * same-type second request changes while the prompt is showing
 * (Issue #20 P4 security-boundary CR §2).
 *
 * ## One pending request + serialized requests
 *
 * [authorize] returns `false` when another request is already pending or a
 * prompt is showing, so a duplicate request / concurrent request (e.g.
 * "Reveal" then "Export") never spawns a second prompt and never replaces the
 * original pending target. The result callback is delivered to the requester
 * of the single pending request — a request A result can therefore never
 * authorize a different request B.
 *
 * ## Lifecycle / concurrency
 *
 * - [onSessionLocked] / [onLifecyclePause] / [onLifecycleDestroy] invalidate
 *   the pending request and discard any one-shot authorization. A pending
 *   sensitive request is NEVER persisted (no SavedStateHandle / Bundle /
 *   DataStore / Room / navigation arguments), so Activity/process recreation
 *   does not resume an authorized-but-unexecuted request.
 *
 * @param prompt the platform prompt backend (production or test fake).
 * @param session the current secure session state; a locked session
 *   invalidates pending requests and discards authorizations.
 * @param titleProvider / [subtitleProvider] supply the prompt strings from
 *   Android resources so this class stays JVM-testable.
 */
class SensitiveActionGate(
    private val prompt: SensitiveActionPrompt,
    private val session: SecureSessionStateMachine,
    private val titleProvider: () -> CharSequence,
    private val subtitleProvider: () -> CharSequence?,
) {

    private var pendingRequest: SensitiveActionRequest? = null
    private var pendingCallback: ((SensitiveActionResult) -> Unit)? = null
    private var authorizedRequest: SensitiveActionRequest? = null
    private var promptActive = false

    /** The currently pending request (or null). Test-visible. */
    fun pendingRequestOrNull(): SensitiveActionRequest? = pendingRequest

    /** @return true when an authorization is outstanding for [request]. */
    fun isAuthorizedFor(request: SensitiveActionRequest): Boolean =
        authorizedRequest == request

    /**
     * Requests fresh re-auth for [request] (an action bound to a target).
     *
     * @return true when the request was accepted (a prompt is showing / will
     *   show, or an immediate outcome like [SensitiveActionResult.Unavailable]
     *   was delivered synchronously); false when another request is pending,
     *   a prompt is active, the session is locked or the platform cannot host
     *   a prompt right now.
     */
    fun authorize(
        request: SensitiveActionRequest,
        onResult: (SensitiveActionResult) -> Unit,
    ): Boolean {
        if (pendingRequest != null || promptActive) return false
        if (!session.isUnlocked()) return false
        pendingRequest = request
        pendingCallback = onResult
        promptActive = true
        val started = prompt.tryStart(
            request = request,
            title = titleProvider(),
            subtitle = subtitleProvider(),
            onResult = { result -> onPromptResult(result) },
        )
        if (!started) {
            // Platform cannot host the prompt right now (e.g. host not
            // resumed). Release the request so a retry can re-request once
            // the host is ready.
            pendingRequest = null
            pendingCallback = null
            promptActive = false
            return false
        }
        return true
    }

    /**
     * Executes [block] only when [request] was authorized by a fresh
     * successful re-auth, then **consumes** the authorization (one-shot).
     *
     * @return true when the authorization was valid (action + target match)
     *   and [block] ran.
     */
    fun <T> executePending(request: SensitiveActionRequest, block: () -> T): Boolean {
        if (authorizedRequest != request) return false
        authorizedRequest = null
        block()
        return true
    }

    /** Cancels the pending request without executing anything. */
    fun cancelPending() {
        pendingRequest = null
        pendingCallback = null
        authorizedRequest = null
        promptActive = false
        prompt.cancel()
    }

    /**
     * Invalidates any pending request / authorization and cancels the prompt
     * (session lock / lifecycle pause / destroy).
     */
    fun invalidate() {
        pendingRequest = null
        pendingCallback = null
        authorizedRequest = null
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
                // Authorize exactly the pending request; the authorization is
                // consumed by the next executePending call for that request.
                authorizedRequest = result.request
                pendingRequest = null
            }
            SensitiveActionResult.Cancelled,
            SensitiveActionResult.Failed,
            SensitiveActionResult.Unavailable,
            -> {
                pendingRequest = null
                authorizedRequest = null
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
        request: SensitiveActionRequest,
        title: CharSequence,
        subtitle: CharSequence?,
        onResult: (SensitiveActionResult) -> Unit,
    ): Boolean

    fun cancel() = Unit
    fun onLifecyclePause() = Unit
    fun onLifecycleDestroy() = Unit
}
