package com.rescueauth.v2

import android.os.Bundle
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import com.rescueauth.v2.repository.VaultAccess
import com.rescueauth.v2.security.SensitiveActionAccess
import com.rescueauth.v2.security.SensitiveActionController
import com.rescueauth.v2.security.SensitiveActionGate
import com.rescueauth.v2.security.StartupAuthPrompt
import com.rescueauth.v2.security.StartupAuthResult
import com.rescueauth.v2.security.StartupUnlockController
import com.rescueauth.v2.session.SecureSessionStateMachine
import com.rescueauth.v2.session.SessionManager
import com.rescueauth.v2.session.VaultUnlockOutcome
import com.rescueauth.v2.ui.RescueAuthApp
import com.rescueauth.v2.ui.screens.startup.NoSecureDeviceScreen
import com.rescueauth.v2.ui.screens.startup.StartupAuthHost
import com.rescueauth.v2.ui.screens.startup.StartupBlockedScreen
import com.rescueauth.v2.ui.screens.startup.StartupIntroScreen
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Root activity — the Compose host for the RescueAuth v2 app shell.
 *
 * **Startup authentication contract (Issue #50).**
 *
 * The Android Keystore wrap key requires user authentication
 * (`setUserAuthenticationRequired(true)`). The previous first-run path
 * executed `createVault()` (a Keystore operation) directly in `onCreate`,
 * which threw an unhandled `UserNotAuthenticatedException` when no valid
 * auth token existed — the P1 startup crash on a freshly booted device.
 *
 * The flow is **authentication-first and lock-screen-free**:
 *
 * ```
 * App launch
 *   → determine startup mode (first-run create vs existing unlock)
 *   → FIRST_RUN: show one-time "use phone to unlock" intro, then prompt auth
 *   → UNLOCK:    prompt authentication automatically (no button tap)
 *   → (biometric OR device credential) success
 *   → then run createVaultAndOpen() / unlock()  (Keystore operation)
 *   → open Vault
 * ```
 *
 * There is no interactive lock screen in the normal flow: authentication is
 * requested automatically (Issue #50 UX rework). A user cancel finishes the
 * Activity instead of dropping into a locked screen. During authentication a
 * neutral, non-sensitive host is shown. Keystore crypto is **never** executed
 * before a successful authentication. `UserNotAuthenticatedException` is
 * mapped defensively to AUTH_REQUIRED (never crash / never "Keystore
 * unavailable") to absorb token-expiry races.
 *
 * Other Phase 2/4 semantics are preserved unchanged: `FLAG_SECURE`, background
 * masking + auto-lock timeout, and the sensitive-action fresh re-auth gate.
 */
class MainActivity : AppCompatActivity() {

    /** Non-sensitive preference file name (first-run auth intro state). */
    companion object {
        private const val STARTUP_PREFS_NAME = "startup_auth_intro"
        private const val KEY_FIRST_RUN_INTRO_CONFIRMED = "first_run_intro_confirmed"
    }

    /** Test-only injection point for a fake [SessionManager]. */
    internal var sessionManagerFactory: ((MainActivity) -> SessionManager)? = null

    /** Test-only injection point for a fake [StartupAuthPrompt]. */
    internal var startupAuthPromptFactory: ((MainActivity) -> StartupAuthPrompt)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** Test-visible (internal) so startup-flow tests can assert session state. */
    internal lateinit var sessionManager: SessionManager
    private lateinit var stateMachine: SecureSessionStateMachine
    private var maskView: FrameLayout? = null

    /** How this launch should open the Vault (first-run vs existing). */
    private var startupMode: StartupMode = StartupMode.UNLOCK

    private enum class StartupMode { FIRST_RUN, UNLOCK }

    /** Compose-visible startup UI state. */
    private enum class StartupUiState {
        /** Not yet determined — waiting for the first onResume. */
        INIT,
        /** First-run: one-time "use phone to unlock" notice before creating the Vault. */
        INTRO,
        /** System authentication is being requested / showing — neutral host. */
        AUTHENTICATING,
        /** Device has no usable secure lock — show the blocking screen. */
        NO_SECURE_DEVICE,
        /** Unrecoverable state (key invalidated / DB corrupt / repeated race) — show + exit. */
        BLOCKED,
        /** Vault open — show the app shell. */
        UNLOCKED,
    }

    private val _uiState = MutableStateFlow(StartupUiState.INIT)
    private val uiState: StateFlow<StartupUiState> = _uiState.asStateFlow()

    /** Whether the current [StartupUiState.BLOCKED] is a key-invalidation error. */
    private var blockedIsKeyInvalidated = false

    /** Whether the system auth prompt should be auto-launched on resume. */
    private var authPromptPending = false

    /**
     * Simple, non-sensitive preference file for the first-run auth intro
     * acknowledgment. It is intentionally NOT tied to Vault creation: once the
     * user has seen the intro and tapped **Enable**, we never show it again,
     * even if they cancelled system authentication before the Vault was created
     * (Issue #50 — "user saw & enabled" is distinct from "Vault created").
     */
    private val startupPrefs by lazy {
        getSharedPreferences(STARTUP_PREFS_NAME, MODE_PRIVATE)
    }

    /** Guards against infinite auth-required prompt loops (Issue #50 UX §8). */
    private val authRaceCount = AtomicInteger(0)
    private val maxAuthRaceRetries = 2

    private var startupAuthPrompt: StartupAuthPrompt? = null

    private fun isFirstRunIntroConfirmed(): Boolean =
        startupPrefs.getBoolean(KEY_FIRST_RUN_INTRO_CONFIRMED, false)

    private fun setFirstRunIntroConfirmed() {
        startupPrefs.edit().putBoolean(KEY_FIRST_RUN_INTRO_CONFIRMED, true).apply()
    }

    /**
     * Phase 4 P4: the production sensitive-action re-auth gate.
     */
    private lateinit var sensitiveActionController: SensitiveActionController
    private lateinit var sensitiveActionGate: SensitiveActionGate

    /** True between onResume() and onPause(). */
    private var isResumedFlag = false

    /** Guards against duplicate/overlapping prompt launches. */
    private val promptActive = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Block screenshots & recents preview for the whole activity.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        stateMachine = SecureSessionStateMachine()
        sessionManager = sessionManagerFactory?.invoke(this)
            ?: SessionManager(this, stateMachine, scope)
        VaultAccess.sessionManager = sessionManager

        startupMode = if (sessionManager.needsFirstRunSetup()) {
            StartupMode.FIRST_RUN
        } else {
            StartupMode.UNLOCK
        }

        // Startup unlock prompt (auth-first — never crypto-first, Issue #50).
        startupAuthPrompt = startupAuthPromptFactory?.invoke(this)
            ?: StartupUnlockController(this)

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
        // authorization (Issue #20 §6).
        scope.launch {
            stateMachine.state.collect { state ->
                if (state != SecureSessionStateMachine.State.UNLOCKED) {
                    sensitiveActionGate.invalidate()
                }
            }
        }

        // Compose host — renders a startup gate until the Vault is unlocked.
        val composeView = ComposeView(this).apply {
            setContent {
                RescueAuthTheme {
                    val ui by uiState.collectAsState()
                    when (ui) {
                        StartupUiState.INIT -> {
                            // Neutral placeholder until the first onResume decides.
                            StartupAuthHost()
                        }
                        StartupUiState.INTRO -> StartupIntroScreen(
                            onContinue = { onIntroContinue() },
                        )
                        StartupUiState.AUTHENTICATING -> StartupAuthHost()
                        StartupUiState.NO_SECURE_DEVICE -> NoSecureDeviceScreen(
                            onExit = { finish() },
                        )
                        StartupUiState.BLOCKED -> {
                            val title = if (blockedIsKeyInvalidated) {
                                getString(R.string.startup_key_invalidated_title)
                            } else {
                                getString(R.string.startup_error_title)
                            }
                            val body = if (blockedIsKeyInvalidated) {
                                getString(R.string.startup_key_invalidated_body)
                            } else {
                                getString(R.string.startup_error_body)
                            }
                            StartupBlockedScreen(
                                title = title,
                                body = body,
                                onExit = { finish() },
                            )
                        }
                        StartupUiState.UNLOCKED -> RescueAuthApp(
                            versionName = BuildConfig.VERSION_NAME,
                        )
                    }
                }
            }
        }
        setContentView(composeView)

        // Never execute Keystore crypto here. Authentication happens in
        // onResume → requestAuthentication() → success → Keystore operation.
        authPromptPending = true
    }

    override fun onStart() {
        super.onStart()
        sessionManager.onAppForegrounded()
        removeMask()
        // Returning to foreground after a session lock must re-authenticate.
        if (!stateMachine.isUnlocked()) {
            authPromptPending = true
        }
    }

    override fun onResume() {
        super.onResume()
        isResumedFlag = true
        if (authPromptPending) {
            requestAuthentication()
        }
    }

    override fun onPause() {
        isResumedFlag = false
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

    /**
     * Test-visible (internal) so startup-flow tests can simulate the user
     * tapping **Enable** on the first-run intro without driving the Compose UI.
     */
    internal fun onIntroContinue() {
        // Persist the acknowledgment immediately — before authentication. Even
        // if the user cancels system auth and the Vault is never created, we
        // must not show this one-time intro again on the next launch.
        setFirstRunIntroConfirmed()
        _uiState.value = StartupUiState.AUTHENTICATING
        requestAuthentication()
    }

    /**
     * Runs the startup authentication flow. Resolves usable authenticators,
     * then (re)launches the system prompt. After a successful authentication
     * it runs the (now safe) Keystore operation.
     *
     * First-run: the one-time intro is shown first (until the user has
     * acknowledged it, which is persisted independently of Vault creation);
     * authentication is only requested after the user taps **Enable**.
     * Existing-vault launches request authentication automatically.
     */
    private fun requestAuthentication() {
        authPromptPending = false
        if (!isResumedFlag || isFinishing || isDestroyed) {
            authPromptPending = true
            return
        }
        if (stateMachine.isUnlocked()) {
            _uiState.value = StartupUiState.UNLOCKED
            return
        }
        // First-run: gate on the one-time intro before prompting. The intro is
        // shown only until the user has acknowledged it (persisted), which is
        // independent of whether the Vault was successfully created.
        if (startupMode == StartupMode.FIRST_RUN && !isFirstRunIntroConfirmed()) {
            // Show the intro; do not launch the prompt until the user enables it.
            _uiState.value = StartupUiState.INTRO
            return
        }
        _uiState.value = StartupUiState.AUTHENTICATING
        if (!promptActive.compareAndSet(false, true)) return

        val prompt = startupAuthPrompt
        if (prompt == null) {
            promptActive.set(false)
            _uiState.value = StartupUiState.BLOCKED
            return
        }

        val authenticators = prompt.resolveAvailableAuthenticators()
        if (authenticators == null) {
            // No PIN / password / pattern and no strong biometric: blocking
            // state, never a silent bypass (Issue #50 §9).
            promptActive.set(false)
            _uiState.value = StartupUiState.NO_SECURE_DEVICE
            return
        }

        val launched = prompt.tryStart { result ->
            promptActive.set(false)
            when (result) {
                StartupAuthResult.Success -> {
                    authRaceCount.set(0)
                    onAuthenticationSucceeded()
                }
                StartupAuthResult.Cancelled -> {
                    // User cancelled — do NOT open the Vault, do not show a
                    // locked screen; finish the Activity (Issue #50 UX §4).
                    _uiState.value = StartupUiState.INIT
                    finish()
                }
                StartupAuthResult.Failed -> {
                    // Transient failure — allow the system to retry.
                    _uiState.value = StartupUiState.AUTHENTICATING
                    authPromptPending = true
                    if (isResumedFlag) requestAuthentication()
                }
                StartupAuthResult.Unavailable -> {
                    _uiState.value = StartupUiState.NO_SECURE_DEVICE
                }
            }
        }
        if (!launched) {
            promptActive.set(false)
        }
    }

    /**
     * After a successful system authentication, run the Keystore operation
     * for the current startup mode. This is the ONLY place Keystore crypto is
     * executed during startup — and it is guaranteed to be after auth success.
     */
    private fun onAuthenticationSucceeded() {
        // Decide the Keystore operation dynamically: if a Vault already exists
        // (e.g. first-run created it and the session later relocked), unlock it
        // instead of re-creating a new Vault and discarding the old one
        // (Issue #50 UX §5, §8 — never create a new Vault on relock).
        val operation: () -> VaultUnlockOutcome = when {
            !sessionManager.needsFirstRunSetup() -> sessionManager::unlock
            startupMode == StartupMode.FIRST_RUN -> sessionManager::createVaultAndOpen
            else -> sessionManager::unlock
        }
        val outcome = operation()
        when (outcome) {
            is VaultUnlockOutcome.Success -> {
                // First-run: the returned fresh key must be zeroed after the DB
                // is opened (SessionManager holds its own copy).
                outcome.freshFirstRunKey?.fill(0)
                _uiState.value = StartupUiState.UNLOCKED
                removeMask()
            }
            VaultUnlockOutcome.AuthRequired -> {
                // Rare race: auth token expired between prompt success and the
                // crypto op. Re-prompt — never crash / never mislabel — but
                // guard against an infinite prompt loop (Issue #50 UX §8).
                if (authRaceCount.incrementAndGet() > maxAuthRaceRetries) {
                    blockedIsKeyInvalidated = false
                    _uiState.value = StartupUiState.BLOCKED
                    return
                }
                _uiState.value = StartupUiState.AUTHENTICATING
                authPromptPending = true
                if (isResumedFlag) requestAuthentication()
            }
            VaultUnlockOutcome.AuthCancelled -> {
                _uiState.value = StartupUiState.INIT
                finish()
            }
            VaultUnlockOutcome.AuthFailed -> {
                _uiState.value = StartupUiState.AUTHENTICATING
                authPromptPending = true
                if (isResumedFlag) requestAuthentication()
            }
            VaultUnlockOutcome.NoSecureDevice -> {
                _uiState.value = StartupUiState.NO_SECURE_DEVICE
            }
            VaultUnlockOutcome.KeyInvalidated -> {
                // Biometric enrollment changed — this is NOT "please authenticate",
                // it is a distinct invalidated-key error (Issue #50 §12).
                blockedIsKeyInvalidated = true
                _uiState.value = StartupUiState.BLOCKED
            }
            VaultUnlockOutcome.KeystoreUnavailable,
            VaultUnlockOutcome.VaultCorrupt,
            -> {
                blockedIsKeyInvalidated = false
                _uiState.value = StartupUiState.BLOCKED
            }
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
}
