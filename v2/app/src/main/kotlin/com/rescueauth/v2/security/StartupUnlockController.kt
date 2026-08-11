package com.rescueauth.v2.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Result of a startup vault-unlock authentication prompt.
 */
sealed interface StartupAuthResult {
    /** The user authenticated successfully — Keystore operations may proceed. */
    object Success : StartupAuthResult

    /** The user cancelled the prompt — the Vault must NOT be opened. */
    object Cancelled : StartupAuthResult

    /** Authentication failed (transient) — retry is allowed. */
    object Failed : StartupAuthResult

    /** No usable strong biometric or device credential exists on this device. */
    object Unavailable : StartupAuthResult
}

/**
 * Platform backend for the startup vault-unlock prompt.
 *
 * Production injects [StartupUnlockController] (real Android BiometricPrompt);
 * tests inject a fake that simulates Success / Cancelled / Failed /
 * Unavailable deterministically — they never touch biometric hardware
 * (Issue #50 §13).
 */
interface StartupAuthPrompt {
    /**
     * Launches the system authentication prompt and delivers the result to
     * [onResult] exactly once. Returns false when a prompt is already active
     * or the host cannot launch a prompt right now.
     */
    fun tryStart(onResult: (StartupAuthResult) -> Unit): Boolean

    /** @return the authenticator mask the user can actually use, or null. */
    fun resolveAvailableAuthenticators(): Int?

    /** Whether a prompt is currently showing / pending. */
    fun isPromptActive(): Boolean

    fun cancel() = Unit
}

/**
 * Production [StartupAuthPrompt] that owns the single real Android
 * [BiometricPrompt] used for **startup vault unlock** (Issue #50).
 *
 * This is the ONLY prompt-owner for the app-unlock path. It mirrors the
 * Phase 2 unlock contract — BIOMETRIC_STRONG plus DEVICE_CREDENTIAL fallback —
 * so the user can unlock with fingerprint / face / PIN / pattern / password.
 * It never lowers security and never silently bypasses authentication.
 *
 * The Vault Keystore operation (create/unwrap) is executed by the caller ONLY
 * after a successful [StartupAuthResult.Success] — auth-first, never
 * crypto-first (Issue #50 §3).
 */
class StartupUnlockController(
    private val activity: FragmentActivity,
) : StartupAuthPrompt {

    private var activePrompt: BiometricPrompt? = null
    private var promptShowing = false
    private var callback: ((StartupAuthResult) -> Unit)? = null

    override fun resolveAvailableAuthenticators(): Int? {
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

    override fun isPromptActive(): Boolean = promptShowing

    override fun tryStart(onResult: (StartupAuthResult) -> Unit): Boolean {
        if (promptShowing) return false
        if (activity.isFinishing || activity.isDestroyed) return false
        if (activity.lifecycle.currentState != androidx.lifecycle.Lifecycle.State.RESUMED) {
            return false
        }

        val authenticators = resolveAvailableAuthenticators()
        if (authenticators == null) {
            onResult(StartupAuthResult.Unavailable)
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
                        deliver(StartupAuthResult.Success)
                    }

                    override fun onAuthenticationError(
                        errorCode: Int,
                        errString: CharSequence,
                    ) {
                        // Cancel, lockout and hardware errors map to a safe
                        // denial — the Vault is never opened without auth.
                        deliver(StartupAuthResult.Cancelled)
                    }

                    override fun onAuthenticationFailed() {
                        // Biometric not recognized: the system prompt stays
                        // open for another attempt; do NOT deliver yet.
                    }
                },
            )
            activePrompt = prompt
            val builder = BiometricPrompt.PromptInfo.Builder()
                .setTitle(activity.getString(com.rescueauth.v2.R.string.unlock_title))
                .setSubtitle(activity.getString(com.rescueauth.v2.R.string.unlock_subtitle))
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
            deliver(StartupAuthResult.Failed)
            return true
        }
    }

    override fun cancel() {
        activePrompt?.cancelAuthentication()
        activePrompt = null
        deliver(StartupAuthResult.Cancelled)
    }

    private fun deliver(result: StartupAuthResult) {
        if (!promptShowing) return
        promptShowing = false
        val cb = callback
        callback = null
        cb?.invoke(result)
    }
}
