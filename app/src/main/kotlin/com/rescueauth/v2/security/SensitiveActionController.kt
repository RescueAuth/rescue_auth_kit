package com.rescueauth.v2.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Platform adapter that owns the real Android [BiometricPrompt] for **fresh
 * sensitive-action re-authentication** (Phase 4 P4).
 *
 * This is the ONLY place that launches a re-auth prompt for a sensitive
 * action. Screen/ViewModel code never creates its own `BiometricPrompt` for
 * re-auth — there is exactly one orchestration path ([SensitiveActionGate])
 * and one prompt owner per [FragmentActivity] ([SensitiveActionController]).
 *
 * ## Authenticators
 *
 * Re-auth mirrors the Phase 2 unlock contract: BIOMETRIC_STRONG when
 * available, plus DEVICE_CREDENTIAL fallback. `DEVICE_CREDENTIAL` is always
 * allowed so the user can fall back to their PIN / pattern / password when
 * biometrics are unavailable. When neither authenticator is available the
 * gate reports [SensitiveActionResult.Unavailable] — never a silent bypass.
 *
 * ## Concurrency
 *
 * A controller can own at most one prompt at a time. [tryStart] returns false
 * when a prompt is already active so a duplicate request can never spawn a
 * second prompt. The [FragmentActivity] is the prompt host: when it is not in
 * RESUMED state the request is rejected (the platform requires a resumed
 * host); [onActivityPause] / [onActivityDestroy] cancel any in-flight prompt
 * and clear the pending authorization.
 */
class SensitiveActionController(
    private val activity: FragmentActivity,
) : SensitiveActionPrompt {
    private var activePrompt: BiometricPrompt? = null
    private var promptShowing = false
    private var callback: ((SensitiveActionResult) -> Unit)? = null

    /** @return the authenticator mask the user can actually use, or null. */
    fun resolveAvailableAuthenticators(): Int? {
        return try {
            val manager = BiometricManager.from(activity)
            val strong = manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            val device = manager.canAuthenticate(BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            var mask = 0
            if (strong == BiometricManager.BIOMETRIC_SUCCESS) {
                mask = mask or BiometricManager.Authenticators.BIOMETRIC_STRONG
            }
            if (device == BiometricManager.BIOMETRIC_SUCCESS) {
                mask = mask or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            }
            if (mask == 0) null else mask
        } catch (e: Exception) {
            // Platform cannot be queried (e.g. no biometrics service). The
            // device-credential prompt is system-provided and is the safest
            // fallback — never a silent bypass.
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        }
    }

    /**
     * Whether a prompt is currently showing / an in-flight authentication is
     * pending. Used by the gate to serialize requests.
     */
    fun isPromptActive(): Boolean = promptShowing

    /**
     * Starts a fresh re-auth prompt for [action] and delivers the result to
     * [onResult] exactly once. Returns false when a prompt is already active
     * or the activity cannot host a prompt right now (caller should keep the
     * request queued or report a safe error).
     */
    override fun tryStart(
        request: SensitiveActionRequest,
        title: CharSequence,
        subtitle: CharSequence?,
        onResult: (SensitiveActionResult) -> Unit,
    ): Boolean {
        if (promptShowing) return false
        if (activity.isFinishing || activity.isDestroyed) return false
        if (activity.lifecycle.currentState != androidx.lifecycle.Lifecycle.State.RESUMED) {
            return false
        }

        val authenticators = resolveAvailableAuthenticators()
        if (authenticators == null) {
            onResult(SensitiveActionResult.Unavailable)
            return true
        }

        promptShowing = true
        callback = onResult
        try {
            val executor = ContextCompat.getMainExecutor(activity)
            val prompt = BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: BiometricPrompt.AuthenticationResult,
                    ) {
                        deliver(SensitiveActionResult.Success(request))
                    }

                    override fun onAuthenticationError(
                        errorCode: Int,
                        errString: CharSequence,
                    ) {
                        // Cancel / lockout / hardware errors all map to a safe
                        // denial — no secret ever leaves the gate, no crash.
                        deliver(SensitiveActionResult.Cancelled)
                    }

                    override fun onAuthenticationFailed() {
                        // Biometric not recognized: the system prompt stays
                        // open for another attempt; do NOT deliver yet.
                    }
                },
            )
            activePrompt = prompt
            val builder = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle ?: "")
                .setAllowedAuthenticators(authenticators)
            if (authenticators and BiometricManager.Authenticators.DEVICE_CREDENTIAL == 0) {
                builder.setNegativeButtonText(
                    activity.getString(com.rescueauth.v2.R.string.unlock_cancel),
                )
            }
            prompt.authenticate(builder.build())
            return true
        } catch (e: Exception) {
            // A prompt that cannot be launched must never crash the Activity.
            deliver(SensitiveActionResult.Failed)
            return true
        }
    }

    /** Cancels any in-flight prompt and discards the pending authorization. */
    override fun cancel() {
        activePrompt?.cancelAuthentication()
        activePrompt = null
        deliver(SensitiveActionResult.Cancelled)
    }

    /** Must be called from the host's onPause — clears any pending auth. */
    fun onActivityPause() {
        if (promptShowing) {
            activePrompt?.cancelAuthentication()
            activePrompt = null
            deliver(SensitiveActionResult.Cancelled)
        }
    }

    /** Must be called from the host's onDestroy — clears any pending auth. */
    fun onActivityDestroy() {
        activePrompt = null
        if (promptShowing) {
            deliver(SensitiveActionResult.Cancelled)
        }
    }

    private fun deliver(result: SensitiveActionResult) {
        if (!promptShowing) return
        promptShowing = false
        val cb = callback
        callback = null
        cb?.invoke(result)
    }
}
