package com.rescueauth.v2

import android.os.Bundle
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import com.rescueauth.v2.security.SensitiveActionAccess
import com.rescueauth.v2.security.SensitiveActionController
import com.rescueauth.v2.security.SensitiveActionGate
import com.rescueauth.v2.security.VaultKeyManager
import com.rescueauth.v2.repository.VaultAccess
import com.rescueauth.v2.session.SecureSessionStateMachine
import com.rescueauth.v2.session.SessionManager
import com.rescueauth.v2.ui.RescueAuthRoot
import com.rescueauth.v2.ui.theme.ThemePreferences
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Root activity — the Compose host for the RescueAuth v2 app shell.
 *
 * **Phase 2 platform/session semantics are preserved unchanged:**
 * - `FLAG_SECURE` on the root window (blocks screenshots & recents preview).
 * - Background masking: an opaque overlay is shown in [onStop] and removed in
 *   [onStart] (after optional re-auth).
 * - First-run: create the VaultKey; otherwise unlock via BiometricPrompt.
 * - Auto-lock: [SessionManager.onAppBackgrounded] with the configured timeout.
 *
 * **Minimal UI-hosting change only:** the previous `simple_list_item_1`
 * placeholder TextView is replaced by a [ComposeView] hosting [RescueAuthApp].
 * The BiometricPrompt contract (phase2-blocker-hotfix), the state machine and
 * the session manager are untouched.
 *
 * The shell is presentation-only: it renders the three top-level destinations
 * and does not read any Vault data. Real Vault CRUD belongs to later vertical
 * slices.
 */
class MainActivity : AppCompatActivity() {

    /** Test-only injection point for a fake [SessionManager]. */
    internal var sessionManagerFactory: ((MainActivity) -> SessionManager)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var sessionManager: SessionManager
    private lateinit var stateMachine: SecureSessionStateMachine
    private var maskView: FrameLayout? = null
    private var authRequested = false

    /**
     * Phase 4 P4: the production sensitive-action re-auth gate.
     *
     * Owned by the Activity because the real [BiometricPrompt] needs a resumed
     * FragmentActivity host. Recreated on every Activity create and destroyed
     * on destroy — a pending sensitive authorization never survives
     * Activity/process recreation (Issue #20 §6/§15).
     */
    private lateinit var sensitiveActionController: SensitiveActionController
    private lateinit var sensitiveActionGate: SensitiveActionGate

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
        VaultAccess.sessionManager = sessionManager

        // Sensitive-action fresh re-auth gate (single orchestration path).
        sensitiveActionController = SensitiveActionController(this)
        sensitiveActionGate = SensitiveActionGate(
            prompt = sensitiveActionController,
            session = stateMachine,
            titleProvider = { getString(R.string.reauth_title) },
            subtitleProvider = { getString(R.string.reauth_subtitle) },
        )
        SensitiveActionAccess.gate = sensitiveActionGate

        // Session lock invalidates any pending sensitive action / one-shot
        // authorization (Issue #20 §6): a lock while a prompt is showing must
        // not leave a stale authorized-but-unexecuted action behind.
        scope.launch {
            stateMachine.state.collect { state ->
                if (state != SecureSessionStateMachine.State.UNLOCKED) {
                    sensitiveActionGate.invalidate()
                }
            }
        }

        // Compose host for the app shell. The shell is presentation-only and
        // does not need the unlocked session; it never reads Vault data.
        val themePreferences = ThemePreferences(this)
        val composeView = ComposeView(this).apply {
            setContent {
                RescueAuthRoot(
                    themePreferences = themePreferences,
                    versionName = BuildConfig.VERSION_NAME,
                )
            }
        }
        setContentView(composeView)

        if (sessionManager.needsFirstRunSetup()) {
            val key = sessionManager.createVault()
            sessionManager.unlockWithFreshKey(key)
            key.fill(0)
        } else {
            authRequested = true
        }
    }

    override fun onStart() {
        super.onStart()
        sessionManager.onAppForegrounded()
        removeMask()
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
        // A prompt that is still showing while the Activity pauses must never
        // outlive the resumed host; its pending authorization is discarded.
        if (::sensitiveActionGate.isInitialized) {
            sensitiveActionGate.onLifecyclePause()
        }
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
                        }
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        // Cancel, lockout and hardware errors must NOT relaunch
                        // the prompt automatically (avoids an auth loop).
                        biometricPromptActive.set(false)
                    }

                    override fun onAuthenticationFailed() {
                        // Biometric not recognized: the system prompt stays
                        // open for another attempt — do not relaunch or close.
                    }
                },
            )
            val builder = BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.unlock_title))
                .setSubtitle(getString(R.string.unlock_subtitle))
                .setAllowedAuthenticators(authenticators)
            // DEVICE_CREDENTIAL forbids a negative button (PromptInfo.build()
            // throws IllegalArgumentException otherwise). Biometric-only
            // prompts keep a "Cancel" negative button.
            if (authenticators and BiometricManager.Authenticators.DEVICE_CREDENTIAL == 0) {
                builder.setNegativeButtonText(getString(R.string.unlock_cancel))
            }
            prompt.authenticate(builder.build())
        } catch (e: Exception) {
            // A prompt that cannot be launched must never crash the Activity;
            // surface the reason instead and allow a manual retry.
            biometricPromptActive.set(false)
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

    override fun onDestroy() {
        if (::sensitiveActionGate.isInitialized) {
            sensitiveActionGate.onLifecycleDestroy()
        }
        SensitiveActionAccess.clear()
        super.onDestroy()
        sessionManager.lock()
        VaultAccess.clear()
        scope.cancel()
    }
}
