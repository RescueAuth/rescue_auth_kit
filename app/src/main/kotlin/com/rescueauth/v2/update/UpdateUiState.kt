package com.rescueauth.v2.update

/**
 * Single, clear UI state machine for the update check (Issue #20 §15) — no
 * scattered booleans.
 *
 * Error taxonomy (Issue #20 §15):
 * - [ErrorType.NETWORK]
 * - [ErrorType.TIMEOUT]
 * - [ErrorType.INVALID_SIGNATURE]
 * - [ErrorType.INVALID_MANIFEST]
 * - [ErrorType.UNSUPPORTED_SCHEMA]
 * - [ErrorType.NOT_CONFIGURED]
 *
 * User copy for [ErrorType.INVALID_SIGNATURE] must say "cannot verify update
 * information", never "no update". No stack trace / raw response is shown.
 */
sealed interface UpdateUiState {

    object Idle : UpdateUiState

    object Checking : UpdateUiState

    data class UpToDate(
        val current: AppVersion,
    ) : UpdateUiState

    data class UpdateAvailable(
        val current: AppVersion,
        val latest: UpdateManifest,
        val severity: Severity,
        val minSupportedExceeded: Boolean,
    ) : UpdateUiState

    data class Error(
        val type: ErrorType,
    ) : UpdateUiState

    enum class ErrorType {
        NETWORK,
        TIMEOUT,
        INVALID_SIGNATURE,
        INVALID_MANIFEST,
        UNSUPPORTED_SCHEMA,
        NOT_CONFIGURED,
    }

    data class AppVersion(
        val versionName: String,
        val versionCode: Long,
    )
}
