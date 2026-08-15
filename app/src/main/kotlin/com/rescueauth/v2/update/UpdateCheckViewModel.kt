package com.rescueauth.v2.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Orchestrates the manual, verified update check (Issue #20 Phase 6 L2).
 *
 * ## Core security contract (fail closed for update data, fail open for app)
 *
 * - Update data validation **fails closed**: an unverifiable manifest is never
 *   trusted, its version / URLs are never shown as a trusted update, and
 *   "Open Release Page" is never offered with an unverified URL.
 * - The app **fails open / offline**: update-check failure never locks the
 *   Vault, never blocks unlock / TOTP / recovery / export / offline use. The
 *   update state never enters the SecureSession state machine.
 *
 * ## State machine
 *
 * Uses a single sealed state (no scattered booleans) — [UpdateUiState].
 */
class UpdateCheckViewModel(
    private val transport: UpdateTransport,
    private val trustConfig: UpdateTrustConfig,
    private val versionIdentity: () -> UpdateVersionDecision.VersionIdentity,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private var activeJob: kotlinx.coroutines.Job? = null

    /** The last verified, trusted release-notes URL (only set after successful verification). */
    private var verifiedReleaseNotesUrl: String? = null

    fun versionName(): String = versionIdentity().versionName

    fun versionCode(): Long = versionIdentity().versionCode

    /**
     * Manual check. The ONLY trigger is the user tapping "Check for Updates"
     * (Issue #20 §3). No background / periodic / launch-time checks.
     */
    fun check() {
        if (_state.value is UpdateUiState.Checking) return
        activeJob?.cancel()
        activeJob = scope.launch {
            _state.value = UpdateUiState.Checking
            verifiedReleaseNotesUrl = null
            _state.value = runCheck()
        }
    }

    /** Cancels an in-flight check (called on scope cancellation). */
    fun cancel() {
        activeJob?.cancel()
        activeJob = null
    }

    /**
     * Returns the verified HTTPS release-notes URL for external open, or null
     * when there is no verified update available (Issue #20 §16).
     */
    fun verifiedOpenUrl(): String? {
        val s = _state.value
        return if (s is UpdateUiState.UpdateAvailable) {
            if (UrlPolicy.isSecureHttpUrl(s.latest.releaseNotesUrl)) s.latest.releaseNotesUrl else null
        } else {
            null
        }
    }

    private suspend fun runCheck(): UpdateUiState {
        val config = trustConfig.rawPublicKey()
        if (config == null) {
            return UpdateUiState.Error(UpdateUiState.ErrorType.NOT_CONFIGURED)
        }

        val manifestBytes = try {
            transport.fetchManifest()
        } catch (e: UpdateNetworkException) {
            return when (e.type) {
                UpdateNetworkException.Type.TIMEOUT -> UpdateUiState.Error(UpdateUiState.ErrorType.TIMEOUT)
                UpdateNetworkException.Type.TOO_LARGE -> UpdateUiState.Error(UpdateUiState.ErrorType.INVALID_MANIFEST)
                UpdateNetworkException.Type.CANCELLED -> UpdateUiState.Idle
                UpdateNetworkException.Type.NETWORK -> UpdateUiState.Error(UpdateUiState.ErrorType.NETWORK)
            }
        }

        // Bound the manifest before allocation (already enforced by transport,
        // defense-in-depth here).
        if (manifestBytes.size > UpdateSizeLimits.MAX_MANIFEST_BYTES) {
            return UpdateUiState.Error(UpdateUiState.ErrorType.INVALID_MANIFEST)
        }

        val signature = try {
            transport.fetchSignature()
        } catch (e: UpdateNetworkException) {
            return when (e.type) {
                UpdateNetworkException.Type.TIMEOUT -> UpdateUiState.Error(UpdateUiState.ErrorType.TIMEOUT)
                UpdateNetworkException.Type.TOO_LARGE -> UpdateUiState.Error(UpdateUiState.ErrorType.INVALID_SIGNATURE)
                UpdateNetworkException.Type.CANCELLED -> UpdateUiState.Idle
                UpdateNetworkException.Type.NETWORK -> UpdateUiState.Error(UpdateUiState.ErrorType.NETWORK)
            }
        }

        if (signature.length > UpdateSizeLimits.MAX_SIGNATURE_BYTES) {
            return UpdateUiState.Error(UpdateUiState.ErrorType.INVALID_SIGNATURE)
        }

        // VERIFY FIRST — only after the exact raw manifest bytes are verified
        // may we parse/trust it (Issue #20 §6).
        val verifier = UpdateManifestVerifier(config)
        when (val v = verifier.verify(manifestBytes, signature)) {
            is UpdateManifestVerifier.Result.Invalid -> {
                return UpdateUiState.Error(UpdateUiState.ErrorType.INVALID_SIGNATURE)
            }
            is UpdateManifestVerifier.Result.Valid -> { /* verified; fall through */ }
        }

        val manifest = try {
            UpdateManifestParser.parse(manifestBytes)
        } catch (e: ManifestParseException) {
            return when (e.kind) {
                ManifestParseException.Kind.UNSUPPORTED_SCHEMA,
                -> UpdateUiState.Error(UpdateUiState.ErrorType.UNSUPPORTED_SCHEMA)
                else -> UpdateUiState.Error(UpdateUiState.ErrorType.INVALID_MANIFEST)
            }
        }

        val identity = versionIdentity()
        return when (val decision = UpdateVersionDecision.decide(identity, manifest)) {
            is UpdateVersionDecision.Decision.UpdateAvailable -> {
                verifiedReleaseNotesUrl = manifest.releaseNotesUrl
                UpdateUiState.UpdateAvailable(
                    current = UpdateUiState.AppVersion(identity.versionName, identity.versionCode),
                    latest = manifest,
                    severity = manifest.severity,
                    minSupportedExceeded = decision.minSupportedExceeded,
                )
            }
            UpdateVersionDecision.Decision.UpToDate -> {
                UpdateUiState.UpToDate(
                    current = UpdateUiState.AppVersion(identity.versionName, identity.versionCode),
                )
            }
        }
    }
}
