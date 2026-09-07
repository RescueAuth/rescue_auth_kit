package com.rescueauth.v2

import android.os.Bundle
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
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
import com.rescueauth.v2.ui.navigation.RescueAuthRoutes
import com.rescueauth.v2.ui.screens.startup.NoSecureDeviceScreen
import com.rescueauth.v2.ui.screens.startup.StartupAuthHost
import com.rescueauth.v2.ui.screens.startup.StartupBlockedScreen
import com.rescueauth.v2.ui.screens.startup.StartupIntroScreen
import com.rescueauth.v2.ui.screens.startup.StartupOpeningHost
import com.rescueauth.v2.ui.screens.startup.StartupSplashScreen
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import com.rescueauth.v2.ui.theme.ThemeColor
import com.rescueauth.v2.ui.theme.ThemePreferences
import java.util.concurrent.atomic.AtomicBoolean
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStateAtLeast
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 * Other Phase 2/4 semantics are preserved unchanged: background masking +
 * auto-lock timeout, and the sensitive-action fresh re-auth gate.
 *
 * Screen-capture policy: RescueAuth deliberately does **not** set a global
 * `FLAG_SECURE` / secure-window policy, so users can screenshot / screen-record
 * the app normally. Long-lived secrets still require a fresh re-auth before
 * reveal/copy, and background masking + auto-lock still protect the UI when the
 * app is backgrounded.
 *
 * Theme color (Issue #52): the selected [ThemeColor] is collected as early as
 * possible in the composition so the startup gate, unlock UI and the app shell
 * all render with the correct theme from the very first frame (no flash). It is
 * read via DataStore (async, off the main thread) and does not depend on the
 * Vault being unlocked.
 */
class MainActivity : AppCompatActivity() {

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

    /** Set when the user chose to import from v1 Rescue Auth on the first-run
     *  intro; consumed once on first UNLOCKED to deep-link into the legacy
     *  importer instead of the default authenticator screen. */
    private var pendingV1Import = false

    private enum class StartupMode { FIRST_RUN, UNLOCK }

    /** Compose-visible startup UI state. */
    private enum class StartupUiState {
        /** Not yet determined — waiting for the first onResume; renders the startup splash. */
        INIT,
        /** First-run: one-time "use phone to unlock" notice before creating the Vault. */
        INTRO,
        /** System authentication is being requested / showing — neutral host. */
        AUTHENTICATING,
        /** Authentication succeeded; SQLCipher/Room is opening off the UI thread. */
        OPENING,
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
     * Adaptive first-run intro gate (Issue #50 follow-up). The intro is shown
     * whenever the Vault is not yet enabled (first-run). This is a *session*
     * flag — reset on every fresh launch — so the intro re-appears on each
     * launch until the Vault is actually created, but does NOT re-appear within
     * a single launch after the user has tapped **Enable** (or **Import v1**).
     */
    private var introContinueRequested = false

    /** Guards against infinite auth-required prompt loops (Issue #50 UX §8). */
    private val authRaceCount = AtomicInteger(0)
    private val maxAuthRaceRetries = 2

    private var startupAuthPrompt: StartupAuthPrompt? = null

    /**
     * Phase 4 P4: the production sensitive-action re-auth gate.
     */
    private lateinit var sensitiveActionController: SensitiveActionController
    private lateinit var sensitiveActionGate: SensitiveActionGate

    /** Guards against duplicate/overlapping prompt launches. */
    private val promptActive = AtomicBoolean(false)

    /**
     * Active lifecycle-aware auth scheduler job. Replaced on every
     * [scheduleAuthentication] so stale waits never double-launch the prompt.
     */
    private var authSchedulerJob: Job? = null

    /** Background Vault open operation; prevents duplicate unlock work. */
    private var vaultOpenJob: Job? = null

    /**
     * True while the activity is pausing because it went to background (NOT a
     * user-initiated cancel of the BiometricPrompt). When true, a Cancelled
     * result from the startup auth prompt must NOT finish the Activity — the
     * app should stay alive and re-request authentication on the next
     * foreground resume (Issue #70).
     */
    private var isPausingForBackground = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No global FLAG_SECURE / secure-window policy — screenshots and screen
        // recording are allowed on ordinary pages (see class doc).

        val initialStateMachine = SecureSessionStateMachine()
        sessionManager = sessionManagerFactory?.invoke(this)
            ?: SessionManager(this, initialStateMachine, scope)
        // Test hosts may inject a SessionManager with its own state machine.
        // The activity must observe the same machine that SessionManager
        // mutates; otherwise the UI can remain on the auth host forever even
        // though the database has opened successfully.
        stateMachine = sessionManager.sessionState
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
        // The theme-color preference is wired in as early as possible so both
        // the gate/lock screens and the app shell apply the saved theme.
        val themePreferences = ThemePreferences(this)
        val composeView = ComposeView(this).apply {
            setContent {
                val context = LocalContext.current
                val prefs = remember(themePreferences, context) {
                    themePreferences ?: ThemePreferences(context)
                }
                val themeColor by prefs.themeColor.collectAsState(initial = ThemeColor.DEFAULT)

                RescueAuthTheme(themeColor = themeColor) {
                    val ui by uiState.collectAsState()
                    when (ui) {
                        StartupUiState.INIT -> StartupSplashScreen()
                        StartupUiState.INTRO -> StartupIntroScreen(
                            onContinue = { onIntroContinue() },
                            onImportV1 = { onIntroImportV1() },
                        )
                        StartupUiState.AUTHENTICATING -> StartupAuthHost()
                        StartupUiState.OPENING -> StartupOpeningHost()
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
                        StartupUiState.UNLOCKED -> {
                            val startRoute = if (pendingV1Import) {
                                RescueAuthRoutes.LEGACY_IMPORT
                            } else {
                                null
                            }
                            // Consume the one-shot deep-link flag so a later
                            // relock/re-unlock does not re-enter the importer.
                            pendingV1Import = false
                            RescueAuthApp(
                                versionName = BuildConfig.VERSION_NAME,
                                startRoute = startRoute,
                            )
                        }
                    }
                }
            }
        }
        setContentView(composeView)

        // Never execute Keystore crypto here. Authentication is scheduled
        // lifecycle-aware (see scheduleAuthentication) so the prompt only
        // launches once AndroidX Lifecycle truly reaches RESUMED.
        scheduleAuthentication()
    }

    override fun onStart() {
        super.onStart()
        isPausingForBackground = false
        sessionManager.onAppForegrounded()
        removeMask()
        if (stateMachine.isUnlocked()) {
            // Session still unlocked (short background below the auto-lock
            // timeout): restore the app shell. This also recovers from a
            // BiometricPrompt cancelled by onPause while the session was
            // already unlocked (Issue #70).
            _uiState.value = StartupUiState.UNLOCKED
        } else if (vaultOpenJob?.isActive == true) {
            // Keep the neutral opening host visible while the background open
            // operation completes; do not launch a second auth prompt.
            _uiState.value = StartupUiState.OPENING
        } else {
            // Session locked — schedule re-authentication for when the
            // lifecycle truly reaches RESUMED.
            scheduleAuthentication()
        }
    }

    override fun onResume() {
        super.onResume()
        // No synchronous requestAuthentication() here. Authentication is
        // scheduled lifecycle-aware so it only runs once AndroidX Lifecycle
        // reports RESUMED — the raw onResume callback can fire before the
        // lifecycle state updates, which previously lost the auth request
        // (Issue #70 second-launch deadlock).
        // A background Vault-open may have completed while the Activity was
        // paused. Restore the shell here as well as in onStart because a short
        // pause/resume does not necessarily emit a new ON_START event.
        if (vaultOpenJob?.isActive != true && stateMachine.isUnlocked() && !isFinishing) {
            isPausingForBackground = false
            _uiState.value = StartupUiState.UNLOCKED
            removeMask()
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        // onResume can run before Lifecycle has entered RESUMED. Reconcile
        // once more at the first callback guaranteed to be after that
        // transition. If Android dismissed a prompt while the app was in the
        // background, the controller is no longer active and this schedules a
        // fresh request instead of leaving the neutral host stranded.
        if (!isFinishing && !isDestroyed && !stateMachine.isUnlocked() &&
            vaultOpenJob?.isActive != true && startupMode != StartupMode.FIRST_RUN
        ) {
            val promptIsActive = startupAuthPrompt?.isPromptActive() == true
            if (!promptIsActive) promptActive.set(false)
            if (!promptIsActive) {
                _uiState.value = StartupUiState.AUTHENTICATING
                scheduleAuthentication()
            }
        }
    }

    override fun onPause() {
        isPausingForBackground = true
        if (::sensitiveActionGate.isInitialized) {
            sensitiveActionGate.onLifecyclePause()
        }
        // Do not cancel the startup prompt here. Device-credential auth can
        // temporarily pause the host while the system credential activity is
        // in front; cancelling from onPause races that flow and loses the
        // callback before the credential result returns. AndroidX Biometric
        // owns cancellation when the prompt is actually dismissed/stopped.
        authSchedulerJob?.cancel()
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
        vaultOpenJob?.cancel()
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
        // Mark the intro gate as passed for THIS launch — before authentication.
        // The intro is adaptive (re-shown on each fresh launch until the Vault
        // is created), so we only need to remember it within this session; we do
        // not persist a "seen" flag anymore.
        introContinueRequested = true
        _uiState.value = StartupUiState.AUTHENTICATING
        // Unified lifecycle-aware scheduler — the Activity is already in
        // stable RESUMED when the user taps Enable, so this launches promptly.
        scheduleAuthentication()
    }

    /**
     * Test-visible (internal). The user chose **Import from v1 Rescue Auth** on
     * the first-run intro. Flag the pending v1 import, then run the same
     * enable-and-authenticate flow as [onIntroContinue]; once the Vault is
     * created/unlocked the app shell deep-links into the legacy importer.
     */
    internal fun onIntroImportV1() {
        pendingV1Import = true
        onIntroContinue()
    }

    /**
     * Unified, lifecycle-aware auth scheduler. Every caller that needs
     * authentication (existing-vault startup, session relock + foreground,
     * AuthRequired race, transient auth failure, first-run Enable) funnels
     * through here: it marks the request pending and only consumes it once
     * [Lifecycle.State.RESUMED] is actually reached.
     *
     * This is the single replacement for the old raw
     * `onResume → requestAuthentication()` path, which could run before
     * AndroidX Lifecycle reported RESUMED and silently drop the request
     * (Issue #70 second-launch deadlock). No arbitrary delays — this is a
     * lifecycle race, not a timing problem.
     */
    private fun scheduleAuthentication() {
        if (vaultOpenJob?.isActive == true) {
            _uiState.value = StartupUiState.OPENING
            return
        }
        authPromptPending = true
        if (isFinishing || isDestroyed) return
        authSchedulerJob?.cancel()
        authSchedulerJob = lifecycleScope.launch {
            withStateAtLeast(Lifecycle.State.RESUMED) {
                // Only launch if still pending and not already mid-prompt.
                if (authPromptPending) {
                    requestAuthentication()
                }
            }
        }
    }

    /**
     * Test-visible invariant check (Issue #70): while the startup UI is in
     * [StartupUiState.AUTHENTICATING], at least one of the following must hold:
     *
     *  A. a system auth prompt is active, or
     *  B. an auth request is pending and waiting for a valid RESUMED
     *     opportunity.
     *
     * Returns true when the invariant holds, false otherwise. Only meaningful
     * when invoked on the main thread after the scheduler has run.
     */
    internal fun authenticatingInvariantHolds(): Boolean {
        if (_uiState.value != StartupUiState.AUTHENTICATING) return true
        return promptActive.get() || authPromptPending
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
        // No isResumedFlag / raw-resume check here. The caller
        // (scheduleAuthentication) guarantees the lifecycle has reached RESUMED
        // before invoking this; this method only reacts to host-level guards.
        if (isFinishing || isDestroyed) {
            // Host cannot launch a prompt — keep it pending for the next valid
            // opportunity rather than dropping the request (Issue #70).
            authPromptPending = true
            return
        }
        if (stateMachine.isUnlocked()) {
            _uiState.value = StartupUiState.UNLOCKED
            return
        }
        // First-run (Vault not yet enabled): show the intro until the user taps
        // **Enable** (or **Import v1**) on this launch. Once they pass the gate
        // (introContinueRequested), proceed to authentication so the Vault can
        // be created. If the user cancels auth and the Vault is never created,
        // a fresh launch re-shows the intro (adaptive: "show the intro whenever
        // the Vault is not enabled").
        if (startupMode == StartupMode.FIRST_RUN && !introContinueRequested) {
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
                    if (isPausingForBackground) {
                        // The prompt was cancelled because the app went to
                        // background (NOT a user-initiated cancel). Keep the
                        // Activity alive and keep the request pending so the
                        // AUTHENTICATING invariant (prompt active OR request
                        // pending) holds; the lifecycle-aware scheduler will
                        // re-launch the prompt on the next RESUMED (Issue #70).
                        promptActive.set(false)
                        scheduleAuthentication()
                    } else {
                        // User cancelled — do NOT open the Vault, do not show a
                        // locked screen; finish the Activity (Issue #50 UX §4).
                        _uiState.value = StartupUiState.INIT
                        finish()
                    }
                }
                StartupAuthResult.Failed -> {
                    // Transient failure — keep AUTHENTICATING and re-schedule
                    // so the system gets another attempt at the next valid
                    // RESUMED opportunity (never a synchronous recursive retry).
                    _uiState.value = StartupUiState.AUTHENTICATING
                    scheduleAuthentication()
                }
                StartupAuthResult.Unavailable -> {
                    _uiState.value = StartupUiState.NO_SECURE_DEVICE
                }
            }
        }
        if (!launched) {
            // tryStart returned false: the host was not ready (e.g. an
            // overlapping prompt or the lifecycle was not truly RESUMED yet).
            // Never silently drop the request — restore the pending flag and
            // re-schedule at the next valid RESUMED opportunity. This is the
            // core fix for the second-launch deadlock where the app sat on
            // AUTHENTICATING with no active prompt and no pending request.
            promptActive.set(false)
            scheduleAuthentication()
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
        // Opening SQLCipher/Room can involve native loading, file I/O and
        // migration work. Keep the main thread free to render the opening host
        // and respond to lifecycle events while that work runs on IO.
        if (vaultOpenJob?.isActive == true) return
        _uiState.value = StartupUiState.OPENING
        if (sessionManagerFactory != null) {
            // The injected SessionManager is a deterministic host-test seam
            // (in-memory Room). Keep that path synchronous so existing startup
            // flow tests can assert the state immediately; production always
            // uses the background branch below.
            handleVaultUnlockOutcome(operation())
        } else {
            vaultOpenJob = scope.launch(Dispatchers.IO) {
                val outcome = operation()
                withContext(Dispatchers.Main.immediate) {
                    // The open attempt has finished before its result is
                    // handled. Clear the guard first so AuthRequired/AuthFailed
                    // can schedule a genuine retry instead of seeing this job
                    // as still active and remaining on the opening host.
                    vaultOpenJob = null
                    handleVaultUnlockOutcome(outcome)
                }
            }
        }
    }

    /** Applies a completed background Vault-open outcome on the main thread. */
    private fun handleVaultUnlockOutcome(outcome: VaultUnlockOutcome) {
        when (outcome) {
            is VaultUnlockOutcome.Success -> {
                // First-run: the returned fresh key must be zeroed after the DB
                // is opened (SessionManager holds its own copy).
                outcome.freshFirstRunKey?.fill(0)
                // The open can finish after onPause/onStop. Never reveal the
                // shell or remove the background mask in that window: the
                // next foreground lifecycle callback will restore the shell
                // if the session is still unlocked, or request auth again if
                // the auto-lock timer already closed it.
                if (!isPausingForBackground &&
                    !isFinishing &&
                    !isDestroyed &&
                    lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                ) {
                    _uiState.value = StartupUiState.UNLOCKED
                    removeMask()
                } else {
                    _uiState.value = StartupUiState.OPENING
                    showMask()
                }
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
                scheduleAuthentication()
            }
            VaultUnlockOutcome.AuthCancelled -> {
                _uiState.value = StartupUiState.INIT
                finish()
            }
            VaultUnlockOutcome.AuthFailed -> {
                _uiState.value = StartupUiState.AUTHENTICATING
                scheduleAuthentication()
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
