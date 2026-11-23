package com.rescueauth.v2

import android.os.Bundle
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStateAtLeast
import com.rescueauth.v2.repository.VaultAccess
import com.rescueauth.v2.security.BackupPinFallbackPrompt
import com.rescueauth.v2.security.DeviceAuthCapability
import com.rescueauth.v2.security.SensitiveActionAccess
import com.rescueauth.v2.security.SensitiveActionController
import com.rescueauth.v2.security.SensitiveActionGate
import com.rescueauth.v2.security.StartupAuthPrompt
import com.rescueauth.v2.security.StartupAuthResult
import com.rescueauth.v2.security.StartupUnlockController
import com.rescueauth.v2.session.BackupPinUnlockResult
import com.rescueauth.v2.session.SecureSessionStateMachine
import com.rescueauth.v2.session.SessionManager
import com.rescueauth.v2.session.VaultUnlockOutcome
import com.rescueauth.v2.ui.RescueAuthApp
import com.rescueauth.v2.ui.navigation.RescueAuthRoutes
import com.rescueauth.v2.ui.screens.startup.BackupPinReauthSheet
import com.rescueauth.v2.ui.screens.startup.FirstRunCreationBranch
import com.rescueauth.v2.ui.screens.startup.FirstRunPinMode
import com.rescueauth.v2.ui.screens.startup.resolveFirstRunPinMode
import com.rescueauth.v2.ui.screens.startup.NoSecureDeviceScreen
import com.rescueauth.v2.ui.screens.startup.SessionLockOverlay
import com.rescueauth.v2.ui.screens.startup.StartupAuthHost
import com.rescueauth.v2.ui.screens.startup.StartupBackupPinSetupScreen
import com.rescueauth.v2.ui.screens.startup.StartupBackupPinUnlockScreen
import com.rescueauth.v2.ui.screens.startup.StartupBlockedScreen
import com.rescueauth.v2.ui.screens.startup.StartupIntroScreen
import com.rescueauth.v2.ui.screens.startup.StartupOpeningHost
import com.rescueauth.v2.ui.screens.startup.StartupSplashScreen
import com.rescueauth.v2.ui.theme.RescueAuthAppearance
import com.rescueauth.v2.ui.theme.ThemePreferences
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Root activity — the Compose host for the RescueAuth v2 app shell.
 *
 * Coordinates dual-slot unlock (Android Keystore Slot A + Backup PIN Slot B,
 * ADR-0015), retained-shell relocking, background masking, and sensitive-action
 * fresh re-authentication with automatic Backup PIN fallback on lockless devices.
 */
class MainActivity : AppCompatActivity() {

    /** Test-only injection point for a fake [SessionManager]. */
    internal var sessionManagerFactory: ((MainActivity) -> SessionManager)? = null

    /** Test-only injection point for a fake [StartupAuthPrompt]. */
    internal var startupAuthPromptFactory: ((MainActivity) -> StartupAuthPrompt)? = null

    /** Test-only injection point for a fake device capability snapshot. */
    internal var deviceCapabilityProvider: (() -> DeviceAuthCapability)? = null


    internal fun currentFirstRunPinMode(): FirstRunPinMode = firstRunPinMode

    internal fun firstRunPinSubmissionBranch(): FirstRunCreationBranch = firstRunPinMode.createBranchForPinSubmission

    internal fun firstRunSetUpLaterBranch(): FirstRunCreationBranch = firstRunPinMode.createBranchForSetUpLater

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Test-visible (internal) so startup-flow tests can assert session state. */
    internal lateinit var sessionManager: SessionManager
    private lateinit var stateMachine: SecureSessionStateMachine
    private var maskView: FrameLayout? = null

    private var firstRunPinMode: FirstRunPinMode = defaultFirstRunPinMode()
    private var startupMode: StartupMode = StartupMode.UNLOCK
    private var pendingV1Import = false

    private enum class StartupMode { FIRST_RUN, UNLOCK }

    private enum class StartupUiState {
        INIT,
        INTRO,
        AUTHENTICATING,
        OPENING,
        NO_SECURE_DEVICE,
        BACKUP_PIN_SETUP,
        BACKUP_PIN_UNLOCK,
        BLOCKED,
        UNLOCKED,
    }

    private data class PinSurfaceState(
        val submitting: Boolean = false,
        val wrongPin: Boolean = false,
        val cooldownSeconds: Int = 0,
        val fallbackNotice: Boolean = false,
        val setupError: String? = null,
        val relockPromptDismissed: Boolean = false,
        val relockUsingPin: Boolean = false,
    )

    private val _uiState = MutableStateFlow(StartupUiState.INIT)
    private val uiState: StateFlow<StartupUiState> = _uiState.asStateFlow()

    private val _pinSurfaceState = MutableStateFlow(PinSurfaceState())

    private var blockedIsKeyInvalidated = false
    private var authPromptPending = false
    private var introContinueRequested = false

    private val authRaceCount = AtomicInteger(0)
    private val maxAuthRaceRetries = 2

    private var startupAuthPrompt: StartupAuthPrompt? = null

    private lateinit var sensitiveActionController: SensitiveActionController
    private lateinit var backupPinFallbackPrompt: BackupPinFallbackPrompt
    private lateinit var sensitiveActionGate: SensitiveActionGate

    private val promptActive = AtomicBoolean(false)
    private var authSchedulerJob: Job? = null
    private var vaultOpenJob: Job? = null
    private var isPausingForBackground = false
    private var hasUnlockedOnce = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val initialStateMachine = SecureSessionStateMachine()
        sessionManager = sessionManagerFactory?.invoke(this)
            ?: SessionManager(this, initialStateMachine, scope)
        stateMachine = sessionManager.sessionState
        sessionManager.refreshSecurityStatus()
        VaultAccess.sessionManager = sessionManager

        startupMode = if (sessionManager.needsFirstRunSetup()) {
            StartupMode.FIRST_RUN
        } else {
            StartupMode.UNLOCK
        }
        restoreShellState(savedInstanceState)

        startupAuthPrompt = startupAuthPromptFactory?.invoke(this)
            ?: StartupUnlockController(this)

        val deviceCapability = deviceCapabilityProvider?.invoke()
            ?: startupAuthPrompt!!.resolveCapability()
        firstRunPinMode = resolveFirstRunPinMode(
            hasSystemAuth = deviceCapability.hasSystemAuth,
            hasWeakBiometricOnly = deviceCapability.hasWeakBiometricOnly,
            hasNoSecureLock = deviceCapability.hasNoSecureLock,
        )


        sensitiveActionController = SensitiveActionController(this)
        backupPinFallbackPrompt = BackupPinFallbackPrompt(
            systemPrompt = sensitiveActionController,
            canUseSystemPrompt = {
                sensitiveActionController.resolveAvailableAuthenticators() != null &&
                    sessionManager.hasSystemKeySlot
            },
            hasBackupPin = { sessionManager.hasBackupPin },
            initialCooldownSeconds = { sessionManager.remainingBackupPinCooldownSeconds() },
            verifyPin = { pin -> sessionManager.verifyBackupPinForReauth(pin) },
            scope = scope,
        )
        sensitiveActionGate = SensitiveActionGate(
            prompt = backupPinFallbackPrompt,
            session = stateMachine,
            titleProvider = { getString(R.string.reauth_title) },
            subtitleProvider = { getString(R.string.reauth_subtitle) },
        )
        SensitiveActionAccess.gate = sensitiveActionGate

        scope.launch {
            stateMachine.state.collect { state ->
                if (state != SecureSessionStateMachine.State.UNLOCKED) {
                    sensitiveActionGate.invalidate()
                }
            }
        }

        val themePreferences = ThemePreferences(this)
        val composeView = ComposeView(this).apply {
            id = R.id.main_compose_host
            setContent {
                RescueAuthAppearance(themePreferences) { themeMode, onThemeModeChange, themeColor, onThemeColorChange ->
                    val ui by uiState.collectAsState()
                    val sessionUnlocked by stateMachine.state.collectAsState()
                    val securityStatus by sessionManager.securityStatusFlow.collectAsState()
                    val pinUi by _pinSurfaceState.collectAsState()
                    val reauthPinDialog by backupPinFallbackPrompt.dialogState.collectAsState()
                    val hasDeviceLock = startupAuthPrompt?.resolveAvailableAuthenticators() != null

                    Box(Modifier.fillMaxSize()) {
                        when (ui) {
                            StartupUiState.INIT -> StartupSplashScreen()
                            StartupUiState.INTRO -> StartupIntroScreen(
                                onContinue = { onIntroContinue() },
                            )
                            StartupUiState.AUTHENTICATING -> StartupAuthHost()
                            StartupUiState.OPENING -> StartupOpeningHost()
                            StartupUiState.NO_SECURE_DEVICE -> NoSecureDeviceScreen(
                                onExit = { finish() },
                                onUseBackupPin = if (startupMode == StartupMode.FIRST_RUN) {
                                    { enterBackupPinSetup() }
                                } else null,
                                onResetVault = if (startupMode != StartupMode.FIRST_RUN) {
                                    { resetVaultToFirstRun() }
                                } else null,
                            )
                            StartupUiState.BACKUP_PIN_SETUP -> StartupBackupPinSetupScreen(
                                submitting = pinUi.submitting,
                                errorMessage = pinUi.setupError,
                                mode = firstRunPinMode,
                                onBack = { finish() },
                                onSetUpLater = if (startupMode == StartupMode.FIRST_RUN && firstRunPinMode.showSetUpLater) {
                                    { submitFirstRunSetUpLater() }
                                } else null,
                                onCreateWithPin = { pin -> submitFirstRunBackupPin(pin) },
                            )
                            StartupUiState.BACKUP_PIN_UNLOCK -> StartupBackupPinUnlockScreen(
                                submitting = pinUi.submitting,
                                wrongPin = pinUi.wrongPin,
                                cooldownSeconds = pinUi.cooldownSeconds,
                                showFallbackNotice = pinUi.fallbackNotice,
                                canSwitchToSystemAuth = hasDeviceLock && sessionManager.hasSystemKeySlot,
                                onSubmitPin = { pin -> submitBackupPinUnlock(pin) },
                                onTickCooldown = { refreshPinCooldown() },
                                onBack = { finish() },
                                onSwitchToSystemAuth = {
                                    _pinSurfaceState.update { it.copy(wrongPin = false, fallbackNotice = false) }
                                    _uiState.value = StartupUiState.AUTHENTICATING
                                    scheduleAuthentication()
                                },
                                onResetVault = { resetVaultToFirstRun() },
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
                                    onResetVault = { resetVaultToFirstRun() },
                                )
                            }
                            StartupUiState.UNLOCKED -> {
                                val startRoute = if (pendingV1Import) {
                                    RescueAuthRoutes.LEGACY_IMPORT
                                } else {
                                    null
                                }
                                pendingV1Import = false
                                Box(Modifier.fillMaxSize()) {
                                    RescueAuthApp(
                                        versionName = BuildConfig.VERSION_NAME,
                                        startRoute = startRoute,
                                        themeMode = themeMode,
                                        onThemeModeChange = onThemeModeChange,
                                        themeColor = themeColor,
                                        onThemeColorChange = onThemeColorChange,
                                        securityStatus = securityStatus,
                                        hasDeviceLock = hasDeviceLock,
                                        onSaveBackupPin = { pin -> sessionManager.setOrReplaceBackupPin(pin) },
                                        onRemoveBackupPin = { sessionManager.removeBackupPin() },
                                        onEnableSystemUnlock = { rebindSystemUnlockFromSettings() },
                                    )
                                    if (sessionUnlocked != SecureSessionStateMachine.State.UNLOCKED) {
                                        if (pinUi.relockUsingPin) {
                                            StartupBackupPinUnlockScreen(
                                                submitting = pinUi.submitting,
                                                wrongPin = pinUi.wrongPin,
                                                cooldownSeconds = pinUi.cooldownSeconds,
                                                showFallbackNotice = pinUi.fallbackNotice,
                                                canSwitchToSystemAuth = hasDeviceLock && sessionManager.hasSystemKeySlot,
                                                onSubmitPin = { pin -> submitBackupPinUnlock(pin) },
                                                onTickCooldown = { refreshPinCooldown() },
                                                onBack = { finish() },
                                                onSwitchToSystemAuth = {
                                                    _pinSurfaceState.update {
                                                        it.copy(relockUsingPin = false, relockPromptDismissed = false, wrongPin = false)
                                                    }
                                                    scheduleAuthentication()
                                                },
                                                onResetVault = { resetVaultToFirstRun() },
                                            )
                                        } else {
                                            SessionLockOverlay(
                                                showUnlockActions = pinUi.relockPromptDismissed,
                                                onUnlockClick = {
                                                    _pinSurfaceState.update { it.copy(relockPromptDismissed = false) }
                                                    scheduleAuthentication()
                                                },
                                                onUseBackupPinClick = if (sessionManager.hasBackupPin) {
                                                    {
                                                        _pinSurfaceState.update {
                                                            it.copy(
                                                                relockUsingPin = true,
                                                                relockPromptDismissed = false,
                                                                wrongPin = false,
                                                                cooldownSeconds = sessionManager.remainingBackupPinCooldownSeconds(),
                                                            )
                                                        }
                                                    }
                                                } else null,
                                                onBackPress = { finish() },
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (sessionUnlocked == SecureSessionStateMachine.State.UNLOCKED && reauthPinDialog != null) {
                            BackupPinReauthSheet(
                                state = reauthPinDialog!!,
                                onSubmitPin = { pin -> backupPinFallbackPrompt.submitPin(pin) },
                                onTickCooldown = { backupPinFallbackPrompt.updateCooldownTick() },
                                onDismiss = { backupPinFallbackPrompt.dismissByUser() },
                            )
                        }
                    }
                }
            }
        }
        setContentView(composeView)

        scheduleAuthentication()
    }

    override fun onStart() {
        super.onStart()
        isPausingForBackground = false
        sessionManager.onAppForegrounded()
        sessionManager.refreshSecurityStatus()
        removeMask()
        if (stateMachine.isUnlocked()) {
            _uiState.value = StartupUiState.UNLOCKED
        } else if (hasUnlockedOnce) {
            if (_pinSurfaceState.value.relockUsingPin) {
                refreshPinCooldown()
            } else {
                scheduleAuthentication()
            }
        } else if (vaultOpenJob?.isActive == true) {
            _uiState.value = StartupUiState.OPENING
        } else if (_uiState.value == StartupUiState.BACKUP_PIN_SETUP || _uiState.value == StartupUiState.BACKUP_PIN_UNLOCK) {
            refreshPinCooldown()
        } else {
            scheduleAuthentication()
        }
    }

    override fun onResume() {
        super.onResume()
        if (vaultOpenJob?.isActive != true && stateMachine.isUnlocked() && !isFinishing) {
            isPausingForBackground = false
            _uiState.value = StartupUiState.UNLOCKED
            removeMask()
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        if (!isFinishing && !isDestroyed && !stateMachine.isUnlocked() &&
            vaultOpenJob?.isActive != true && startupMode != StartupMode.FIRST_RUN &&
            _uiState.value != StartupUiState.BACKUP_PIN_UNLOCK &&
            _uiState.value != StartupUiState.BLOCKED &&
            _uiState.value != StartupUiState.NO_SECURE_DEVICE &&
            !_pinSurfaceState.value.relockUsingPin &&
            !_pinSurfaceState.value.relockPromptDismissed
        ) {
            val promptIsActive = startupAuthPrompt?.isPromptActive() == true
            if (!promptIsActive) promptActive.set(false)
            if (!promptIsActive) {
                if (!hasUnlockedOnce) _uiState.value = StartupUiState.AUTHENTICATING
                scheduleAuthentication()
            }
        }
    }

    override fun onPause() {
        isPausingForBackground = true
        if (::sensitiveActionGate.isInitialized) {
            sensitiveActionGate.onLifecyclePause()
        }
        authSchedulerJob?.cancel()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        showMask()
        sessionManager.onAppBackgrounded(lockAfterMillis = 30_000L)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        saveShellState(outState)
    }

    override fun onDestroy() {
        if (::sensitiveActionGate.isInitialized) {
            sensitiveActionGate.onLifecycleDestroy()
        }
        SensitiveActionAccess.clear()
        vaultOpenJob?.cancel()
        super.onDestroy()
        sessionManager.lock()
        VaultAccess.release(sessionManager)
        scope.cancel()
    }

    internal fun onIntroContinue() {
        introContinueRequested = true
        _uiState.value = StartupUiState.AUTHENTICATING
        scheduleAuthentication()
    }

    internal fun enterBackupPinSetup() {
        _pinSurfaceState.update { it.copy(submitting = false, setupError = null) }
        _uiState.value = StartupUiState.BACKUP_PIN_SETUP
    }

    private fun refreshPinCooldown() {
        val rem = sessionManager.remainingBackupPinCooldownSeconds()
        _pinSurfaceState.update { it.copy(cooldownSeconds = rem) }
    }

    private fun enterBackupPinUnlock(fallbackNotice: Boolean = false) {
        val rem = sessionManager.remainingBackupPinCooldownSeconds()
        if (hasUnlockedOnce) {
            _pinSurfaceState.update {
                it.copy(
                    submitting = false,
                    wrongPin = false,
                    cooldownSeconds = rem,
                    fallbackNotice = fallbackNotice,
                    relockUsingPin = true,
                    relockPromptDismissed = false,
                )
            }
            _uiState.value = StartupUiState.UNLOCKED
        } else {
            _pinSurfaceState.update {
                it.copy(
                    submitting = false,
                    wrongPin = false,
                    cooldownSeconds = rem,
                    fallbackNotice = fallbackNotice,
                )
            }
            _uiState.value = StartupUiState.BACKUP_PIN_UNLOCK
        }
    }

    internal fun submitFirstRunBackupPin(pin: String) {
        if (vaultOpenJob?.isActive == true || _pinSurfaceState.value.submitting) return
        _pinSurfaceState.update { it.copy(submitting = true, setupError = null) }
        val branch = if (startupMode == StartupMode.FIRST_RUN) {
            firstRunPinMode.createBranchForPinSubmission
        } else {
            FirstRunCreationBranch.BACKUP_PIN_ONLY
        }
        val operation: () -> VaultUnlockOutcome = when (branch) {
            FirstRunCreationBranch.DUAL_SLOT -> ({ sessionManager.createVaultWithSystemAndBackupPinAndOpen(pin) })
            FirstRunCreationBranch.BACKUP_PIN_ONLY -> ({ sessionManager.createVaultWithBackupPinAndOpen(pin) })
            FirstRunCreationBranch.SYSTEM_ONLY -> throw IllegalStateException("PIN submission must not use system-only creation")
        }
        val applyOutcome: (VaultUnlockOutcome) -> Unit = { outcome ->
            _pinSurfaceState.update { it.copy(submitting = false) }
            if (outcome is VaultUnlockOutcome.Success) {
                outcome.freshFirstRunKey?.fill(0)
                startupMode = StartupMode.UNLOCK
                handleVaultUnlockOutcome(outcome)
            } else {
                // Fail closed: no partial slot, no silent downgrade; remain on the PIN page.
                _uiState.value = StartupUiState.BACKUP_PIN_SETUP
                _pinSurfaceState.update { it.copy(setupError = getString(R.string.startup_pin_setup_failed)) }
            }
        }
        if (sessionManagerFactory != null) {
            applyOutcome(operation())
        } else {
            vaultOpenJob = scope.launch(Dispatchers.IO) {
                val outcome = operation()
                withContext(Dispatchers.Main.immediate) {
                    vaultOpenJob = null
                    applyOutcome(outcome)
                }
            }
        }
    }

    internal fun submitFirstRunSetUpLater() {
        if (vaultOpenJob?.isActive == true || _pinSurfaceState.value.submitting) return
        _pinSurfaceState.update { it.copy(submitting = true, setupError = null) }
        val applyOutcome: (VaultUnlockOutcome) -> Unit = { outcome ->
            _pinSurfaceState.update { it.copy(submitting = false) }
            if (outcome is VaultUnlockOutcome.Success) {
                outcome.freshFirstRunKey?.fill(0)
                startupMode = StartupMode.UNLOCK
                handleVaultUnlockOutcome(outcome)
            } else {
                _uiState.value = StartupUiState.BACKUP_PIN_SETUP
                _pinSurfaceState.update { it.copy(setupError = getString(R.string.startup_pin_setup_failed)) }
            }
        }
        if (sessionManagerFactory != null) {
            applyOutcome(sessionManager.createVaultAndOpen())
        } else {
            vaultOpenJob = scope.launch(Dispatchers.IO) {
                val outcome = sessionManager.createVaultAndOpen()
                withContext(Dispatchers.Main.immediate) {
                    vaultOpenJob = null
                    applyOutcome(outcome)
                }
            }
        }
    }

    internal fun submitBackupPinUnlock(pin: String) {
        if (vaultOpenJob?.isActive == true || _pinSurfaceState.value.submitting) return
        _pinSurfaceState.update { it.copy(submitting = true, wrongPin = false) }
        val applyResult: (BackupPinUnlockResult) -> Unit = { result ->
            _pinSurfaceState.update { it.copy(submitting = false) }
            when (result) {
                BackupPinUnlockResult.Success -> {
                    _pinSurfaceState.update {
                        it.copy(
                            wrongPin = false,
                            cooldownSeconds = 0,
                            fallbackNotice = false,
                            relockUsingPin = false,
                            relockPromptDismissed = false,
                        )
                    }
                    handleVaultUnlockOutcome(VaultUnlockOutcome.Success(null))
                }
                is BackupPinUnlockResult.WrongPin -> {
                    _pinSurfaceState.update {
                        it.copy(wrongPin = true, cooldownSeconds = result.cooldownSeconds)
                    }
                }
                is BackupPinUnlockResult.Cooldown -> {
                    _pinSurfaceState.update {
                        it.copy(wrongPin = false, cooldownSeconds = result.remainingSeconds)
                    }
                }
                BackupPinUnlockResult.InvalidPinFormat -> {
                    _pinSurfaceState.update { it.copy(wrongPin = true) }
                }
                BackupPinUnlockResult.CorruptOrMissing -> {
                    blockedIsKeyInvalidated = false
                    _uiState.value = StartupUiState.BLOCKED
                }
            }
        }
        if (sessionManagerFactory != null) {
            applyResult(sessionManager.unlockWithBackupPin(pin))
        } else {
            vaultOpenJob = scope.launch(Dispatchers.IO) {
                val result = sessionManager.unlockWithBackupPin(pin)
                withContext(Dispatchers.Main.immediate) {
                    vaultOpenJob = null
                    applyResult(result)
                }
            }
        }
    }

    private fun rebindSystemUnlockFromSettings() {
        val prompt = startupAuthPrompt ?: return
        if (prompt.resolveAvailableAuthenticators() == null) return
        prompt.tryStart { result ->
            if (result is StartupAuthResult.Success) {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        sessionManager.rebindSystemKeySlot()
                    }
                }
            }
        }
    }

    internal fun resetVaultToFirstRun() {
        vaultOpenJob?.cancel()
        vaultOpenJob = null
        sessionManager.resetLockedOutVault()
        hasUnlockedOnce = false
        introContinueRequested = false
        blockedIsKeyInvalidated = false
        startupMode = StartupMode.FIRST_RUN
        _pinSurfaceState.value = PinSurfaceState()
        _uiState.value = StartupUiState.BACKUP_PIN_SETUP
    }

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
                if (authPromptPending) {
                    requestAuthentication()
                }
            }
        }
    }

    internal fun authenticatingInvariantHolds(): Boolean {
        if (_uiState.value != StartupUiState.AUTHENTICATING) return true
        return promptActive.get() || authPromptPending
    }

    internal fun isShellComposed(): Boolean = _uiState.value == StartupUiState.UNLOCKED

    internal fun isBackupPinSetupVisible(): Boolean = _uiState.value == StartupUiState.BACKUP_PIN_SETUP

    private fun defaultFirstRunPinMode(): FirstRunPinMode = FirstRunPinMode(
        showSetUpLater = false,
        mustSetPinBeforeContinuing = false,
        copyVariant = FirstRunPinMode.Copy.STANDARD,
        createBranchForPinSubmission = FirstRunCreationBranch.BACKUP_PIN_ONLY,
        createBranchForSetUpLater = FirstRunCreationBranch.SYSTEM_ONLY,
    )


    internal fun saveShellState(outState: Bundle) {
        outState.putBoolean("shell_unlocked", hasUnlockedOnce)
    }

    internal fun restoreShellState(savedInstanceState: Bundle?) {
        if (savedInstanceState?.getBoolean("shell_unlocked") == true && !sessionManager.needsFirstRunSetup()) {
            hasUnlockedOnce = true
            _uiState.value = StartupUiState.UNLOCKED
        }
    }

    internal fun isLockOverlayVisible(): Boolean =
        isShellComposed() && !stateMachine.isUnlocked()

    private fun requestAuthentication() {
        authPromptPending = false
        if (isFinishing || isDestroyed) {
            authPromptPending = true
            return
        }
        if (stateMachine.isUnlocked()) {
            _uiState.value = StartupUiState.UNLOCKED
            return
        }
        if (startupMode == StartupMode.FIRST_RUN) {
            _uiState.value = StartupUiState.BACKUP_PIN_SETUP
            return
        }
        // Existing vault created with Backup PIN only (or whose system slot already failed):
        // route directly to Backup PIN unlock without needing a system prompt.
        if (startupMode != StartupMode.FIRST_RUN && !sessionManager.hasSystemKeySlot && sessionManager.hasBackupPin) {
            enterBackupPinUnlock(fallbackNotice = sessionManager.isSystemKeyInvalidated)
            return
        }

        if (!hasUnlockedOnce) _uiState.value = StartupUiState.AUTHENTICATING
        if (!promptActive.compareAndSet(false, true)) return

        val prompt = startupAuthPrompt
        if (prompt == null) {
            promptActive.set(false)
            if (startupMode != StartupMode.FIRST_RUN && sessionManager.hasBackupPin) {
                enterBackupPinUnlock()
            } else {
                _uiState.value = StartupUiState.BLOCKED
            }
            return
        }

        val authenticators = prompt.resolveAvailableAuthenticators()
        if (authenticators == null) {
            promptActive.set(false)
            if (startupMode != StartupMode.FIRST_RUN && sessionManager.hasBackupPin) {
                enterBackupPinUnlock(fallbackNotice = true)
            } else {
                _uiState.value = StartupUiState.NO_SECURE_DEVICE
            }
            return
        }

        _pinSurfaceState.update { it.copy(relockPromptDismissed = false) }
        val launched = prompt.tryStart { result ->
            promptActive.set(false)
            when (result) {
                StartupAuthResult.Success -> {
                    authRaceCount.set(0)
                    onAuthenticationSucceeded()
                }
                StartupAuthResult.Cancelled -> {
                    if (isPausingForBackground) {
                        promptActive.set(false)
                        scheduleAuthentication()
                    } else if (hasUnlockedOnce) {
                        // Relocked shell: surface the Unlock / Backup PIN action buttons
                        // on the lock overlay so the user is never stranded.
                        _pinSurfaceState.update { it.copy(relockPromptDismissed = true) }
                    } else {
                        _uiState.value = StartupUiState.INIT
                        finish()
                    }
                }
                StartupAuthResult.Failed -> {
                    if (!hasUnlockedOnce) _uiState.value = StartupUiState.AUTHENTICATING
                    scheduleAuthentication()
                }
                StartupAuthResult.Unavailable -> {
                    if (startupMode != StartupMode.FIRST_RUN && sessionManager.hasBackupPin) {
                        enterBackupPinUnlock(fallbackNotice = true)
                    } else {
                        _uiState.value = StartupUiState.NO_SECURE_DEVICE
                    }
                }
            }
        }
        if (!launched) {
            promptActive.set(false)
            scheduleAuthentication()
        }
    }

    private fun onAuthenticationSucceeded() {
        val operation: () -> VaultUnlockOutcome = when {
            !sessionManager.needsFirstRunSetup() -> sessionManager::unlock
            startupMode == StartupMode.FIRST_RUN -> sessionManager::createVaultAndOpen
            else -> sessionManager::unlock
        }
        if (vaultOpenJob?.isActive == true) return
        if (!hasUnlockedOnce) _uiState.value = StartupUiState.OPENING
        if (sessionManagerFactory != null) {
            handleVaultUnlockOutcome(operation())
        } else {
            vaultOpenJob = scope.launch(Dispatchers.IO) {
                val outcome = operation()
                withContext(Dispatchers.Main.immediate) {
                    vaultOpenJob = null
                    handleVaultUnlockOutcome(outcome)
                }
            }
        }
    }

    private fun handleVaultUnlockOutcome(outcome: VaultUnlockOutcome) {
        when (outcome) {
            is VaultUnlockOutcome.Success -> {
                outcome.freshFirstRunKey?.fill(0)
                startupMode = StartupMode.UNLOCK
                hasUnlockedOnce = true
                _pinSurfaceState.update {
                    it.copy(relockPromptDismissed = false, relockUsingPin = false, wrongPin = false)
                }
                if (!isPausingForBackground &&
                    !isFinishing &&
                    !isDestroyed &&
                    lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                ) {
                    _uiState.value = StartupUiState.UNLOCKED
                    removeMask()
                } else if (!hasUnlockedOnce) {
                    _uiState.value = StartupUiState.OPENING
                    showMask()
                }
            }
            VaultUnlockOutcome.AuthRequired -> {
                if (authRaceCount.incrementAndGet() > maxAuthRaceRetries) {
                    if (sessionManager.hasBackupPin) {
                        enterBackupPinUnlock()
                    } else {
                        blockedIsKeyInvalidated = false
                        _uiState.value = StartupUiState.BLOCKED
                    }
                    return
                }
                if (!hasUnlockedOnce) _uiState.value = StartupUiState.AUTHENTICATING
                scheduleAuthentication()
            }
            VaultUnlockOutcome.AuthCancelled -> {
                if (hasUnlockedOnce) {
                    _pinSurfaceState.update { it.copy(relockPromptDismissed = true) }
                } else {
                    _uiState.value = StartupUiState.INIT
                    finish()
                }
            }
            VaultUnlockOutcome.AuthFailed -> {
                if (!hasUnlockedOnce) _uiState.value = StartupUiState.AUTHENTICATING
                scheduleAuthentication()
            }
            VaultUnlockOutcome.NoSecureDevice -> {
                if (startupMode != StartupMode.FIRST_RUN && sessionManager.hasBackupPin) {
                    enterBackupPinUnlock(fallbackNotice = true)
                } else {
                    _uiState.value = StartupUiState.NO_SECURE_DEVICE
                }
            }
            VaultUnlockOutcome.KeyInvalidated -> {
                if (sessionManager.hasBackupPin) {
                    enterBackupPinUnlock(fallbackNotice = true)
                } else {
                    blockedIsKeyInvalidated = true
                    _uiState.value = StartupUiState.BLOCKED
                }
            }
            VaultUnlockOutcome.KeystoreUnavailable,
            VaultUnlockOutcome.VaultCorrupt,
            -> {
                if (sessionManager.hasBackupPin && outcome == VaultUnlockOutcome.KeystoreUnavailable) {
                    enterBackupPinUnlock(fallbackNotice = true)
                } else {
                    blockedIsKeyInvalidated = false
                    _uiState.value = StartupUiState.BLOCKED
                }
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