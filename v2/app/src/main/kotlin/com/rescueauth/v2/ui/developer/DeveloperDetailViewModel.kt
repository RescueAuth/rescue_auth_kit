package com.rescueauth.v2.ui.developer

import com.rescueauth.v2.export.VaultApiCredential
import com.rescueauth.v2.export.VaultGenericSecret
import com.rescueauth.v2.export.VaultSshKey
import com.rescueauth.v2.repository.DeveloperRepository
import com.rescueauth.v2.security.SensitiveAction
import com.rescueauth.v2.security.SensitiveActionGate
import com.rescueauth.v2.security.SensitiveActionRequest
import com.rescueauth.v2.security.SensitiveActionResult
import com.rescueauth.v2.security.SensitiveActionTarget
import com.rescueauth.v2.session.SecureSessionStateMachine
import com.rescueauth.v2.ui.model.DeveloperDetailUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Developer detail UI state (Phase 4 P4).
 *
 * [detail] carries only non-secret metadata. Revealed plaintext secrets live
 * in [revealedValues] — an in-memory map keyed by a stable non-secret field
 * key — and are cleared on leaving the screen / session lock / manual hide
 * (Issue #20 §14, §21). Copy requests are one-shot events carrying the secret
 * to the clipboard layer.
 */
data class DeveloperDetailUiState(
    val loading: Boolean = true,
    val detail: DeveloperDetailUi? = null,
    val error: String? = null,
    val authPending: Boolean = false,
    val authUnavailable: Boolean = false,
    val authCancelled: Boolean = false,
) {
    val isEmpty: Boolean
        get() = !loading && detail == null && error == null
}

/** One-shot events for the Developer detail screen (clipboard side effects). */
sealed interface DeveloperDetailEvent {
    data class CopySecret(val value: String, val label: String) : DeveloperDetailEvent
    data class Deleted(val label: String) : DeveloperDetailEvent
    data class AuthUnavailable(val message: String) : DeveloperDetailEvent
}

/**
 * ViewModel for the Developer entry detail screen (Phase 4 P4).
 *
 * The single consumer of the [SensitiveActionGate] for Developer reveals /
 * copies:
 *
 * - Reveal of a secret (apiSecret / apiKey / privateKey / passphrase /
 *   generic field value) requires a fresh [SensitiveActionGate.authorize] and
 *   the plaintext is loaded from the repository only after success.
 * - Copy of any sensitive value is a high-risk action and requires its OWN
 *   fresh re-auth (Issue #20 §15) — a currently-revealed value does NOT
 *   bypass the copy gate.
 * - Non-secret metadata edits (title / notes / serviceName / accountName /
 *   keyName / field labels) do NOT require fresh re-auth in an unlocked Vault
 *   (Issue #20 §16).
 * - Reveal state and the in-memory secret map are cleared on session lock /
 *   leaving the screen / manual hide (Issue #20 §14).
 */
class DeveloperDetailViewModel(
    private val developerRepositoryProvider: () -> DeveloperRepository?,
    private val sensitiveActionGate: SensitiveActionGate?,
    sessionState: StateFlow<SecureSessionStateMachine.State>,
    private val stableId: String,
    private val scope: CoroutineScope,
) {

    private val _uiState = MutableStateFlow(DeveloperDetailUiState())
    val uiState: StateFlow<DeveloperDetailUiState> = _uiState.asStateFlow()

    private val _events = MutableStateFlow<DeveloperDetailEvent?>(null)
    val events: StateFlow<DeveloperDetailEvent?> = _events.asStateFlow()

    /** Revealed plaintext secret values (in-memory only). */
    private val revealedValues = mutableMapOf<String, String>()

    private var loadJob: Job? = null

    init {
        scope.launch {
            sessionState.collect { state ->
                if (state == SecureSessionStateMachine.State.UNLOCKED) {
                    if (loadJob == null) {
                        loadJob = scope.launch { load() }
                    }
                } else {
                    loadJob?.cancel()
                    loadJob = null
                    clearRevealed()
                    _uiState.value = DeveloperDetailUiState(loading = false)
                }
            }
        }
    }

    private suspend fun load() {
        val repo = developerRepositoryProvider() ?: return
        try {
            val entry = repo.getByStableId(stableId)
            if (entry == null) {
                _uiState.value = DeveloperDetailUiState(loading = false, error = "not_found")
                return
            }
            _uiState.value = DeveloperDetailUiState(
                loading = false,
                detail = entry.toDetailUi(),
            )
        } catch (e: Exception) {
            _uiState.value = DeveloperDetailUiState(loading = false, error = "load_failed")
        }
    }

    fun onEventShown() {
        _events.value = null
    }

    /** Clears all revealed state (leaving the screen / manual hide / lock). */
    fun clearRevealed() {
        revealedValues.clear()
        _uiState.value = _uiState.value.copy(
            authPending = false,
            authUnavailable = false,
            authCancelled = false,
        )
    }

    // ------------------------------------------------------------------
    // Sensitive reveal
    // ------------------------------------------------------------------

    /**
     * Reveals a secret field. Requires a fresh re-auth for [action] bound to
     * THIS entry's [stableId] + [fieldKey]; on success the plaintext is loaded
     * from the repository and stored in memory. Repeated reveal of the SAME
     * field still requires a new re-auth after the field is hidden again
     * (Issue #20 §14).
     *
     * The authorization is bound to the original target (stableId + fieldKey
     * + operation), so a success can never reveal a different entry / field
     * (Issue #20 P4 security-boundary CR §2).
     */
    fun reveal(action: SensitiveAction, fieldKey: String) {
        if (revealedValues.containsKey(fieldKey)) return
        if (_uiState.value.authPending) return
        val gate = sensitiveActionGate ?: return
        val request = SensitiveActionRequest(
            action = action,
            target = SensitiveActionTarget.DeveloperField(stableId = stableId, fieldKey = fieldKey),
        )
        _uiState.value = _uiState.value.copy(authPending = true, authUnavailable = false, authCancelled = false)
        val accepted = gate.authorize(request) { result -> onAuthResult(result, request) }
        if (!accepted) {
            _uiState.value = _uiState.value.copy(authPending = false)
        }
    }

    /**
     * Copies a secret field. Copy is a high-risk action and requires its own
     * fresh re-auth even if the value is currently revealed (Issue #20 §15).
     * The authorization is bound to THIS entry's [stableId] + [fieldKey] and
     * to the exact COPY action, so a reveal authorization can never authorize
     * a copy and vice versa (Issue #20 P4 security-boundary CR §1/§2).
     */
    fun copySecret(action: SensitiveAction, fieldKey: String, label: String) {
        // Even a currently-revealed value goes through a fresh re-auth — the
        // copy gate is never bypassed by reveal state.
        val gate = sensitiveActionGate ?: return
        if (_uiState.value.authPending) return
        val request = SensitiveActionRequest(
            action = action,
            target = SensitiveActionTarget.DeveloperField(stableId = stableId, fieldKey = fieldKey),
        )
        _uiState.value = _uiState.value.copy(authPending = true, authUnavailable = false, authCancelled = false)
        val accepted = gate.authorize(request) { result ->
            onCopyAuthResult(result, request, label)
        }
        if (!accepted) {
            _uiState.value = _uiState.value.copy(authPending = false)
        }
    }

    private fun onAuthResult(result: SensitiveActionResult, request: SensitiveActionRequest) {
        when (result) {
            is SensitiveActionResult.Success -> {
                _uiState.value = _uiState.value.copy(authPending = false)
                // Consume the one-shot authorization ONLY when the authorized
                // request matches the original target exactly (action +
                // stableId + fieldKey) (Issue #20 §2/§8/§14).
                if (result.request == request) {
                    val gate = sensitiveActionGate
                    if (gate != null && gate.executePending(request) {
                        scope.launch { loadSecretInto(request) }
                    }) {
                        // executed
                    }
                }
            }
            SensitiveActionResult.Cancelled -> {
                _uiState.value = _uiState.value.copy(authPending = false, authCancelled = true)
            }
            SensitiveActionResult.Failed -> {
                _uiState.value = _uiState.value.copy(authPending = false, authCancelled = true)
            }
            SensitiveActionResult.Unavailable -> {
                _uiState.value = _uiState.value.copy(authPending = false, authUnavailable = true)
                _events.value = DeveloperDetailEvent.AuthUnavailable("unavailable")
            }
        }
    }

    private fun onCopyAuthResult(
        result: SensitiveActionResult,
        request: SensitiveActionRequest,
        label: String,
    ) {
        when (result) {
            is SensitiveActionResult.Success -> {
                _uiState.value = _uiState.value.copy(authPending = false)
                // Consume the one-shot authorization ONLY when the authorized
                // request matches the original target (action + stableId +
                // fieldKey) (Issue #20 §15).
                if (result.request == request) {
                    val gate = sensitiveActionGate
                    if (gate != null && gate.executePending(request) {
                        scope.launch {
                            val value = loadSecretValue(request.target as SensitiveActionTarget.DeveloperField)
                            if (value != null) {
                                _events.value = DeveloperDetailEvent.CopySecret(value, label)
                            }
                        }
                    }) {
                        // executed
                    }
                }
            }
            SensitiveActionResult.Cancelled,
            SensitiveActionResult.Failed,
            -> {
                _uiState.value = _uiState.value.copy(authPending = false, authCancelled = true)
            }
            SensitiveActionResult.Unavailable -> {
                _uiState.value = _uiState.value.copy(authPending = false, authUnavailable = true)
                _events.value = DeveloperDetailEvent.AuthUnavailable("unavailable")
            }
        }
    }

    private suspend fun loadSecretInto(request: SensitiveActionRequest) {
        val target = request.target as? SensitiveActionTarget.DeveloperField ?: return
        val value = loadSecretValue(target) ?: return
        revealedValues[target.fieldKey] = value
    }

    /** Loads the plaintext for a field key (repository read, in-memory only). */
    private suspend fun loadSecretValue(target: SensitiveActionTarget.DeveloperField): String? {
        val repo = developerRepositoryProvider() ?: return null
        val entry = repo.getByStableId(target.stableId) ?: return null
        return when (val d = _uiState.value.detail) {
            is DeveloperDetailUi.ApiCredential -> when (target.fieldKey) {
                "apiKey" -> (entry as? VaultApiCredential)?.apiKey
                "apiSecret" -> (entry as? VaultApiCredential)?.apiSecret
                else -> null
            }
            is DeveloperDetailUi.SshKey -> when (target.fieldKey) {
                "privateKey" -> (entry as? VaultSshKey)?.privateKey
                "passphrase" -> (entry as? VaultSshKey)?.passphrase
                else -> null
            }
            is DeveloperDetailUi.GenericSecret -> {
                val generic = entry as? VaultGenericSecret ?: return null
                generic.fields.firstOrNull { "field:${it.key}" == target.fieldKey }?.value
            }
            null -> null
        }
    }

    /** Returns the currently-revealed plaintext (null if not revealed). */
    fun revealedValue(fieldKey: String): String? = revealedValues[fieldKey]

    fun isRevealed(fieldKey: String): Boolean = revealedValues.containsKey(fieldKey)

    // ------------------------------------------------------------------
    // Delete (destructive confirmation; P4 no Undo — Issue #20 §17)
    // ------------------------------------------------------------------

    suspend fun delete(): Boolean {
        val repo = developerRepositoryProvider() ?: return false
        val title = _uiState.value.detail?.title ?: return false
        return try {
            repo.delete(stableId)
            _events.value = DeveloperDetailEvent.Deleted(title)
            true
        } catch (e: Exception) {
            false
        }
    }

    // ------------------------------------------------------------------
    // Mappers
    // ------------------------------------------------------------------

    private fun com.rescueauth.v2.export.VaultDeveloperEntry.toDetailUi(): DeveloperDetailUi? =
        when (this) {
            is VaultApiCredential -> DeveloperDetailUi.ApiCredential(
                stableId = stableId,
                title = title.ifEmpty { serviceName },
                serviceName = serviceName,
                accountName = accountName,
                notes = notes,
            )
            is VaultSshKey -> DeveloperDetailUi.SshKey(
                stableId = stableId,
                title = title.ifEmpty { keyName },
                keyName = keyName,
                publicKeyPresent = publicKey.isNotBlank(),
                notes = notes,
            )
            is VaultGenericSecret -> DeveloperDetailUi.GenericSecret(
                stableId = stableId,
                title = title,
                fieldLabels = fields.map { it.key },
                notes = notes,
            )
            else -> null // P6 types not editable in P4
        }
}
