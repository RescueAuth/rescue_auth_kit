package com.rescueauth.v2.security

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UI state of an active in-app Backup PIN verification prompt for a sensitive
 * action (ADR-0015 §4 — E1 fallback when system authentication is unavailable
 * or the system Keystore slot is invalidated).
 */
data class BackupPinReauthDialogState(
    val title: CharSequence,
    val subtitle: CharSequence?,
    val verifying: Boolean = false,
    val cooldownSeconds: Int = 0,
    val failedAttempts: Int = 0,
    val wrongPin: Boolean = false,
)

/**
 * Composite [SensitiveActionPrompt] that prioritizes the platform
 * [BiometricPrompt] ([systemPrompt]) when system authentication + Keystore slot
 * are usable, and falls back to an in-app Backup PIN verification prompt when
 * a Backup PIN is configured (ADR-0015 §4).
 *
 * Preserves [SensitiveActionGate]'s exact one-shot target-bound contract
 * (ADR-0011) with zero changes to upstream ViewModels.
 */
class BackupPinFallbackPrompt(
    private val systemPrompt: SensitiveActionPrompt,
    private val canUseSystemPrompt: () -> Boolean,
    private val hasBackupPin: () -> Boolean,
    private val initialCooldownSeconds: () -> Int,
    private val verifyPin: suspend (String) -> BackupPinVaultSlot.PinAttemptOutcome,
    private val scope: CoroutineScope,
    private val verifyDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default,
) : SensitiveActionPrompt {

    private val _dialogState = MutableStateFlow<BackupPinReauthDialogState?>(null)
    val dialogState: StateFlow<BackupPinReauthDialogState?> = _dialogState.asStateFlow()

    private var activeRequest: SensitiveActionRequest? = null
    private var activeCallback: ((SensitiveActionResult) -> Unit)? = null
    private var usingSystemPrompt = false
    private var verifyJob: Job? = null

    override fun tryStart(
        request: SensitiveActionRequest,
        title: CharSequence,
        subtitle: CharSequence?,
        onResult: (SensitiveActionResult) -> Unit,
    ): Boolean {
        if (activeCallback != null || usingSystemPrompt) return false

        if (canUseSystemPrompt()) {
            usingSystemPrompt = true
            val started = systemPrompt.tryStart(request, title, subtitle) { result ->
                usingSystemPrompt = false
                onResult(result)
            }
            if (!started) {
                usingSystemPrompt = false
            }
            return started
        }

        if (!hasBackupPin()) {
            onResult(SensitiveActionResult.Unavailable)
            return true
        }

        activeRequest = request
        activeCallback = onResult
        _dialogState.value = BackupPinReauthDialogState(
            title = title,
            subtitle = subtitle,
            cooldownSeconds = initialCooldownSeconds(),
        )
        return true
    }

    fun submitPin(pin: String) {
        val current = _dialogState.value ?: return
        val request = activeRequest ?: return
        if (current.verifying) return
        if (pin.isEmpty()) return

        _dialogState.update { it?.copy(verifying = true, wrongPin = false) }
        verifyJob?.cancel()
        verifyJob = scope.launch {
            val outcome = withContext(verifyDispatcher) { verifyPin(pin) }
            if (activeRequest != request) return@launch
            when (outcome) {
                BackupPinVaultSlot.PinAttemptOutcome.Verified,
                is BackupPinVaultSlot.PinAttemptOutcome.Unlocked,
                -> {
                    if (outcome is BackupPinVaultSlot.PinAttemptOutcome.Unlocked) {
                        outcome.vaultKey.fill(0)
                    }
                    deliverPinResult(SensitiveActionResult.Success(request))
                }
                is BackupPinVaultSlot.PinAttemptOutcome.WrongPin -> {
                    _dialogState.update {
                        it?.copy(
                            verifying = false,
                            wrongPin = true,
                            failedAttempts = outcome.failedAttempts,
                            cooldownSeconds = outcome.cooldownSeconds,
                        )
                    }
                }
                is BackupPinVaultSlot.PinAttemptOutcome.CooldownActive -> {
                    _dialogState.update {
                        it?.copy(
                            verifying = false,
                            wrongPin = false,
                            cooldownSeconds = outcome.remainingSeconds,
                        )
                    }
                }
                BackupPinVaultSlot.PinAttemptOutcome.MissingSlot,
                BackupPinVaultSlot.PinAttemptOutcome.CorruptSlot,
                -> {
                    deliverPinResult(SensitiveActionResult.Failed)
                }
            }
        }
    }

    fun updateCooldownTick() {
        val current = _dialogState.value ?: return
        if (current.verifying) return
        val rem = initialCooldownSeconds()
        if (rem != current.cooldownSeconds) {
            _dialogState.update { it?.copy(cooldownSeconds = rem) }
        }
    }

    fun dismissByUser() {
        if (activeCallback != null) {
            deliverPinResult(SensitiveActionResult.Cancelled)
        }
    }

    override fun cancel() {
        if (usingSystemPrompt) {
            usingSystemPrompt = false
            systemPrompt.cancel()
        }
        if (activeCallback != null) {
            deliverPinResult(SensitiveActionResult.Cancelled)
        } else {
            clearPinState()
        }
    }

    override fun onLifecyclePause() {
        if (usingSystemPrompt) {
            usingSystemPrompt = false
            systemPrompt.onLifecyclePause()
        }
        if (activeCallback != null) {
            deliverPinResult(SensitiveActionResult.Cancelled)
        }
    }

    override fun onLifecycleDestroy() {
        if (usingSystemPrompt) {
            usingSystemPrompt = false
            systemPrompt.onLifecycleDestroy()
        }
        if (activeCallback != null) {
            deliverPinResult(SensitiveActionResult.Cancelled)
        }
    }

    private fun deliverPinResult(result: SensitiveActionResult) {
        val cb = activeCallback
        clearPinState()
        cb?.invoke(result)
    }

    private fun clearPinState() {
        verifyJob?.cancel()
        verifyJob = null
        activeRequest = null
        activeCallback = null
        _dialogState.value = null
    }
}
