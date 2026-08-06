package com.rescueauth.v2

import android.os.Bundle
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.rescueauth.v2.session.SecureSessionStateMachine
import com.rescueauth.v2.session.SessionManager
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
 */
class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var sessionManager: SessionManager
    private lateinit var stateMachine: SecureSessionStateMachine
    private var maskView: FrameLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Block screenshots & recents preview for the whole activity.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        stateMachine = SecureSessionStateMachine()
        sessionManager = SessionManager(this, stateMachine, scope)

        setContentView(android.R.layout.simple_list_item_1)
        findViewById<TextView>(android.R.id.text1).text =
            "RescueAuth v2 — phase 2 session lifecycle"

        if (sessionManager.needsFirstRunSetup()) {
            val key = sessionManager.createVault()
            sessionManager.unlockWithFreshKey(key)
            key.fill(0)
            showStatus("First run: VaultKey created & unlocked")
        } else {
            showStatus("Locked — authenticate to unlock")
            promptBiometricUnlock()
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

    override fun onStop() {
        super.onStop()
        showMask()
        sessionManager.onAppBackgrounded(lockAfterMillis = 30_000L)
    }

    private fun promptBiometricUnlock() {
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (sessionManager.unlock()) {
                        removeMask()
                        showStatus("Unlocked")
                    } else {
                        showStatus("Unlock failed (Keystore invalidated?)")
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    showStatus("Auth error: $errString")
                }

                override fun onAuthenticationFailed() {
                    showStatus("Auth failed — try again")
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock RescueAuth")
            .setSubtitle("Use your fingerprint, face, or device credential")
            .setNegativeButtonText("Cancel")
            .setAllowedAuthenticators(
                androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        prompt.authenticate(info)
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
