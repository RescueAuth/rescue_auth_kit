package com.rescueauth.v2

import android.os.Bundle
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.rescueauth.v2.security.VaultKeyManager
import com.rescueauth.v2.session.SecureSessionStateMachine
import com.rescueauth.v2.session.SessionManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Root activity for phase 2 platform lifecycle validation.
 *
 * Responsibilities (ADR-0003 §2, §4):
 * - `FLAG_SECURE` on the root window (blocks screenshots & recents preview).
 * - Background masking: an opaque overlay is shown in [onStop] and removed in
 *   [onStart] (after optional re-auth).
 * - First-run: create the VaultKey; otherwise unlock via BiometricPrompt.
 * - Auto-lock: [SessionManager.onAppBackgrounded] with the configured timeout.
 *
 * BiometricPrompt contract (phase2-blocker-hotfix):
 * - When `DEVICE_CREDENTIAL` is part of the allowed authenticators a negative
 *   button must NOT be set — `PromptInfo.Builder.build()` throws
 *   `IllegalArgumentException` otherwise, crashing the Activity on startup.
 *   The system UI supplies its own cancel button for the device-credential
 *   path. For a biometric-only prompt a negative "Cancel" button is kept.
 * - Authentication is only ever triggered while the Activity is RESUMED (never
 *   directly from `onCreate`) and is guarded so that cancel, failure and
 *   repeated `onResume` events can never create an authentication loop or
 *   crash the Activity.
 */
class MainActivity : AppCompatActivity() {

    /** Test-only injection point for a fake [SessionManager]. */
    internal var sessionManagerFactory: ((MainActivity) -> SessionManager)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var sessionManager: SessionManager
    private lateinit var stateMachine: SecureSessionStateMachine
    private var maskView: FrameLayout? = null
    private var authRequested = false

    /** True between onResume() and onPause() — used to gate authenticate(). */
    private var isResumedFlag = false

    /** Guards against duplicate/overlapping prompt launches. */
    private val biometricPromptActive = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Block screenshots & recents preview for the whole activity.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        stateMachine = SecureSessionStateMachine()
        sessionManager = sessionManagerFactory?.invoke(this)
            ?: SessionManager(this, stateMachine, scope)

        setContentView(android.R.layout.simple_list_item_1)
        findViewById<TextView>(android.R.id.text1).text =
            "RescueAuth v2 — phase 2 session lifecycle"

        if (sessionManager.needsFirstRunSetup()) {
            val key = sessionManager.createVault()
            sessionManager.unlockWithFreshKey(key)
            key.fill(0)
            showStatus("First run: VaultKey created & unlocked")
        } else {
            authRequested = true
            showStatus("Locked — authenticate to unlock")
        }
    }

    override fun onStart() {
        super.onStart()
        sessionManager.onAppForegrounded()
        removeMask()
        if (stateMachine.isUnlocked()) {
            showStatus("Unlocked")
        }
    }

    override fun onResume() {
        super.onResume()
        isResumedFlag = true
        // Never authenticate before the Activity is RESUMED (the platform
        // requires a resumed host for BiometricPrompt); `onCreate` only
        // records that authentication is needed.
        if (authRequested && !stateMachine.isUnlocked()) {
            promptBiometricUnlock()
        }
    }

    override fun onPause() {
        isResumedFlag = false
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        showMask()
        sessionManager.onAppBackgrounded(lockAfterMillis = 30_000L)
    }

    private fun promptBiometricUnlock() {
        if (!isResumedFlag || isFinishing || isDestroyed) return
        if (stateMachine.isUnlocked()) return
        if (!biometricPromptActive.compareAndSet(false, true)) return

        val authenticators = resolveAvailableAuthenticators()
        if (authenticators == null) {
            biometricPromptActive.set(false)
            showStatus("No enrolled biometrics or device credential — recovery required")
            return
        }

        try {
            val executor = ContextCompat.getMainExecutor(this)
            val prompt = BiometricPrompt(
                this,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        biometricPromptActive.set(false)
                        if (sessionManager.unlock()) {
                            removeMask()
                            showStatus("Unlocked")
                        } else {
                            showStatus("Unlock failed (Keystore invalidated?)")
                        }
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        // Cancel, lockout and hardware errors must NOT relaunch
                        // the prompt automatically (avoids an auth loop).
                        biometricPromptActive.set(false)
                        showStatus("Auth error ($errorCode): $errString")
                    }

                    override fun onAuthenticationFailed() {
                        // Biometric not recognized: the system prompt stays
                        // open for another attempt — do not relaunch or close.
                        showStatus("Auth failed — try again")
                    }
                },
            )
            val builder = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock RescueAuth")
                .setSubtitle("Use your fingerprint, face, or device credential")
                .setAllowedAuthenticators(authenticators)
            // DEVICE_CREDENTIAL forbids a negative button (PromptInfo.build()
            // throws IllegalArgumentException otherwise). Biometric-only
            // prompts keep a "Cancel" negative button.
            if (authenticators and BiometricManager.Authenticators.DEVICE_CREDENTIAL == 0) {
                builder.setNegativeButtonText("Cancel")
            }
            prompt.authenticate(builder.build())
        } catch (e: Exception) {
            // A prompt that cannot be launched must never crash the Activity;
            // surface the reason instead and allow a manual retry.
            biometricPromptActive.set(false)
            showStatus("Biometric prompt unavailable: ${e.message}")
        }
    }

    /**
     * Returns the authenticators that are actually usable on this device, or
     * null when none are enrolled/available. `DEVICE_CREDENTIAL` is always
     * considered when the platform cannot be queried, because the device
     * credential prompt is system-provided and needs no biometric enrollment.
     */
    private fun resolveAvailableAuthenticators(): Int? {
        return try {
            val manager = BiometricManager.from(this)
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
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        }
    }

    private fun showMask() {
        if (maskView == null) {
            val mask = FrameLayout(this)
            mask.setBackgroundColor(0xFF111111.toInt())
            maskView = mask
        }
        (window.decorView as FrameLayout).addView(maskView)
    }

    private fun removeMask() {
        maskView?.let { (window.decorView as FrameLayout).removeView(it) }
    }

    private fun showStatus(text: String) {
        findViewById<TextView>(android.R.id.text1).text = text
    }

    override fun onDestroy() {
        super.onDestroy()
        sessionManager.lock()
        scope.cancel()
    }
}
