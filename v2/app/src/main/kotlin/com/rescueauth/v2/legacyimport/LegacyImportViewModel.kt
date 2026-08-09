package com.rescueauth.v2.legacyimport

import android.content.Context
import android.net.Uri
import com.rescueauth.v2.legacy.LegacyRakVaultImporter
import com.rescueauth.v2.legacy.LegacyVaultSnapshotMapper
import com.rescueauth.v2.repository.ImportOutcome
import com.rescueauth.v2.repository.VaultRepository
import com.rescueauth.v2.session.SecureSessionStateMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Phase 5B — Legacy v1 `.rakvault` import coordinator (Issue #1 §22–§24).
 *
 * Plain class (not androidx ViewModel) so it is trivially JVM-testable; the
 * Compose layer provides the [scope].
 *
 * ## State machine
 *
 * ```
 * Idle
 *  → FileSelected        (SAF OpenDocument returned a Uri; bounded read done)
 *  → AwaitingPassword    (file validated / bounded; password entry)
 *  → Decrypting          (decode + map + plan)
 *  → Preview             (safe summary; may be blocked)
 *  → Applying            (final re-plan + transactional apply)
 *  → Success | Error
 * ```
 *
 * Invalid state combinations are impossible by construction (a single sealed
 * [State] value; each transition replaces the previous state and clears stale
 * plaintext state).
 *
 * ## Plaintext lifecycle (Issue #1 §16/§17)
 *
 * - Password is a transient [CharArray] passed straight to the decoder; it is
 *   zeroized by the caller after each submit and never stored here.
 * - The decrypted bundle + mapped snapshot live ONLY inside
 *   [LegacyImportService]'s active session (never in this ViewModel / Compose
 *   state). [dispose] / cancel / apply / lock / new-file / error clear it.
 * - [LegacyImportPreview] (the only thing the UI holds) carries no secrets.
 */
class LegacyImportViewModel(
    private val context: Context,
    private val serviceProvider: () -> LegacyImportService?,
    private val fileIo: LegacyFileIo,
    private val sessionState: StateFlow<SecureSessionStateMachine.State>,
    private val scope: CoroutineScope,
) {

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    sealed interface State {
        /** Nothing selected yet. */
        object Idle : State

        /**
         * A file was selected and the bounded read succeeded (≤ 64 MiB).
         * The password has not been entered yet.
         */
        data class FileSelected(val fileName: String?, val sizeBytes: Long?) : State

        /** Waiting for the user to enter the Legacy Master Password. */
        object AwaitingPassword : State

        /** Decoding + mapping + planning. */
        object Decrypting : State

        /** Safe preview is ready (may be [LegacyImportPreview.blocked]). */
        data class Preview(val preview: LegacyImportPreview) : State

        /** Applying transactionally (final re-plan against live destination). */
        object Applying : State

        data class Success(
            val imported: Int,
            val duplicates: Int,
            val conflicts: Int,
            val stateDivergences: Int,
            val developerImported: Int,
            val blocked: Boolean,
        ) : State

        /** A safe, user-actionable error. */
        data class Error(
            val message: String,
            /**
             * Which recovery the user is offered:
             * RETRY_PASSWORD — stay on password entry;
             * RESTART — return to file selection (clears all plaintext);
             * BLOCKED — merge blocked; go back to file selection.
             */
            val action: ErrorAction,
        ) : State
    }

    enum class ErrorAction { RETRY_PASSWORD, RESTART, BLOCKED }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Pending SAF Uri (cleared on cancel / error / new file). */
    @Volatile
    private var pendingUri: Uri? = null

    /** Selected file display name (UX hint only — never a security gate). */
    @Volatile
    private var pendingFileName: String? = null

    /** Selected file size claim (UX hint only — never a security gate). */
    @Volatile
    private var pendingSizeBytes: Long? = null

    /**
     * Monotonic generation counter for async operations. Each file selection /
     * password submit bumps it; a stale async completion (from a previous file
     * selection or decode) whose generation does not match the current one is
     * discarded so A-file state can never be consumed by B-file (Issue #1 §23).
     */
    @Volatile
    private var operationGeneration = 0L

    /**
     * The service instance used by the current operation. The decoded legacy
     * bundle + snapshot live inside it and are cleared on cancel / apply /
     * lock / new-file / error (Issue #1 §16).
     */
    @Volatile
    private var activeService: LegacyImportService? = null

    private fun resolveService(): LegacyImportService {
        val svc = activeService ?: serviceProvider()?.also { activeService = it }
        return svc ?: throw VaultRepository.SessionLockedException()
    }

    /** Test-visible accessor for the active legacy import session (or null). */
    fun activeSession(): LegacyImportSession? = activeService?.activeSession()

    private var sessionJob: Job? = null

    init {
        // Session lock: clear password-independent decoded state, the active
        // legacy bundle/snapshot, the preview and block Apply (Issue #1 §17).
        // The user must redo the Legacy decode/import flow after unlock.
        sessionJob = scope.launch {
            sessionState.collect { state ->
                if (state != SecureSessionStateMachine.State.UNLOCKED) {
                    clearSensitiveState()
                }
            }
        }
    }

    private fun clearSensitiveState() {
        // Invalidate any in-flight async operation (bounded read / decode /
        // apply) so a stale completion can never resurrect old-file state.
        ++operationGeneration
        activeService?.clearSession()
        activeService = null
        pendingUri = null
        pendingFileName = null
        pendingSizeBytes = null
        if (_state.value is State.FileSelected ||
            _state.value is State.AwaitingPassword ||
            _state.value is State.Decrypting ||
            _state.value is State.Preview ||
            _state.value is State.Applying
        ) {
            _state.value = State.Idle
        }
    }

    /** Releases the session-collection job and clears any decoded state. */
    fun dispose() {
        sessionJob?.cancel()
        activeService?.clearSession()
        activeService = null
    }

    // ------------------------------------------------------------------
    // Flow
    // ------------------------------------------------------------------

    /**
     * Called after the user picks a document via SAF OpenDocument.
     *
     * The bounded read enforces the Legacy 64 MiB + 1 detection policy BEFORE
     * any decrypt (Issue #1 §5). The filename/extension/MIME are UX hints
     * only — actual authenticity is verified by the legacy importer/envelope
     * later. On success we move to [State.AwaitingPassword].
     */
    fun handlePickedDocument(uri: Uri, fileName: String?, sizeBytes: Long?) {
        if (_state.value is State.Decrypting || _state.value is State.Applying) return
        // Choosing a new file must clear all old-file plaintext state
        // (password / decoded bundle / snapshot / preview / source fingerprint)
        // so A-file state can never be consumed by B-file (Issue #1 §23).
        clearSensitiveState()
        val generation = ++operationGeneration
        pendingUri = uri
        pendingFileName = fileName
        pendingSizeBytes = sizeBytes
        _state.value = State.FileSelected(fileName, sizeBytes)
        scope.launch {
            try {
                val bytes = fileIo.readBounded(context, uri)
                if (generation != operationGeneration) return@launch
                pendingUri = uri
                _state.value = State.AwaitingPassword
            } catch (e: LegacyFileError) {
                if (generation != operationGeneration) return@launch
                pendingUri = null
                _state.value = State.Error(toFileMessage(e), ErrorAction.RESTART)
            } catch (e: Exception) {
                if (generation != operationGeneration) return@launch
                pendingUri = null
                _state.value = State.Error(
                    "Could not read the selected file.",
                    ErrorAction.RESTART,
                )
            }
        }
    }

    /**
     * The user cancelled the SAF picker — no error, back to Idle.
     */
    fun cancelFilePick() {
        clearSensitiveState()
        _state.value = State.Idle
    }

    /**
     * Submits the Legacy Master Password for decode.
     *
     * Deliberately NO `>=10` gate: Frozen v1 audit confirmed the length rule is
     * a v1 *creation UI* policy, not a decoder requirement. Any non-empty
     * password is submitted to the legacy decoder (Issue #1 §6). Returns true
     * if the flow may proceed (the decode runs async); false when the field is
     * empty (the UI keeps focus without a useless decode attempt).
     */
    fun submitPassword(password: CharArray): Boolean {
        val uri = pendingUri
        if (uri == null) {
            password.fill('\u0000')
            _state.value = State.Error(
                "Choose a legacy vault file first.",
                ErrorAction.RESTART,
            )
            return false
        }
        if (password.isEmpty()) {
            password.fill('\u0000')
            return false
        }
        _state.value = State.Decrypting
        val generation = ++operationGeneration
        scope.launch {
            try {
                val bytes = fileIo.readBounded(context, uri)
                if (generation != operationGeneration) return@launch
                val service = resolveService()
                val preview = service.decodeForPreview(bytes, password)
                if (generation != operationGeneration) return@launch
                _state.value = State.Preview(preview)
            } catch (e: VaultRepository.SessionLockedException) {
                if (generation != operationGeneration) return@launch
                _state.value = State.Error(
                    "Your vault is locked. Unlock it and try again.",
                    ErrorAction.RESTART,
                )
            } catch (e: LegacyRakVaultImporter.ImportException) {
                if (generation != operationGeneration) return@launch
                _state.value = State.Error(toImportMessage(e), toErrorAction(e))
            } catch (e: LegacyVaultSnapshotMapper.LegacyMappingException) {
                if (generation != operationGeneration) return@launch
                _state.value = State.Error(
                    "This legacy vault could not be read. It may use an unsupported format.",
                    ErrorAction.RESTART,
                )
            } catch (e: LegacyFileError) {
                if (generation != operationGeneration) return@launch
                _state.value = State.Error(toFileMessage(e), ErrorAction.RESTART)
            } catch (e: Exception) {
                if (generation != operationGeneration) return@launch
                _state.value = State.Error(
                    "Something went wrong during import.",
                    ErrorAction.RESTART,
                )
            } finally {
                password.fill('\u0000')
            }
        }
        return true
    }

    /** User cancelled the password entry — no error, back to Idle. */
    fun cancelPassword() {
        clearSensitiveState()
        _state.value = State.Idle
    }

    /**
     * After a wrong-password / corrupted-file error ([ErrorAction.RETRY_PASSWORD])
     * the user may retry with a different password on the SAME file. The
     * pending Uri is retained; the decoded session stays cleared.
     */
    fun retryPassword() {
        if (pendingUri == null) {
            _state.value = State.Idle
            return
        }
        _state.value = State.AwaitingPassword
    }

    /**
     * Confirms the import. The service calls [VaultRepository.applySnapshot],
     * which re-validates / re-plans against the LIVE destination / preflights /
     * applies in one transaction. The preview plan is never applied directly
     * (Issue #1 §11).
     */
    fun confirmImport() {
        val current = _state.value
        if (current !is State.Preview) return
        if (current.preview.blocked) return
        val generation = ++operationGeneration
        _state.value = State.Applying
        scope.launch {
            try {
                val service = resolveService()
                val outcome = service.confirmImport()
                if (generation != operationGeneration) return@launch
                when (outcome) {
                    is ImportOutcome.Applied -> {
                        _state.value = State.Success(
                            imported = outcome.result.insertedTotal,
                            duplicates = outcome.result.duplicates,
                            conflicts = outcome.result.conflicts,
                            stateDivergences = outcome.result.stateDivergences,
                            developerImported = outcome.result.insertedDeveloperEntries,
                            blocked = false,
                        )
                    }
                    is ImportOutcome.Blocked -> {
                        _state.value = State.Success(
                            imported = 0,
                            duplicates = outcome.result.duplicates,
                            conflicts = outcome.result.conflicts,
                            stateDivergences = outcome.result.stateDivergences,
                            developerImported = 0,
                            blocked = true,
                        )
                    }
                }
            } catch (e: VaultRepository.SessionLockedException) {
                if (generation != operationGeneration) return@launch
                _state.value = State.Error(
                    "Your vault is locked. Unlock it and try again.",
                    ErrorAction.RESTART,
                )
            } catch (e: Exception) {
                if (generation != operationGeneration) return@launch
                _state.value = State.Error(
                    "Something went wrong during import.",
                    ErrorAction.RESTART,
                )
            }
        }
    }

    /** Clears a result/error so the user can start again. */
    fun reset() {
        clearSensitiveState()
        _state.value = State.Idle
    }

    // ------------------------------------------------------------------
    // Error mapping (safe — never shows secrets / stack traces)
    // ------------------------------------------------------------------

    private fun toFileMessage(e: LegacyFileError): String = when (e) {
        LegacyFileError.UserCancelled -> ""
        LegacyFileError.CannotOpenDocument -> "Could not open the document."
        LegacyFileError.ReadFailed -> "Could not read the document."
        LegacyFileError.FileEmpty -> "This file is empty. Choose a legacy vault (.rakvault) file."
        LegacyFileError.FileTooLarge -> "This file is larger than the 64 MB legacy limit."
    }

    private fun toErrorAction(e: LegacyRakVaultImporter.ImportException): ErrorAction =
        when (e.kind) {
            LegacyRakVaultImporter.ErrorKind.AUTHENTICATION_FAILED,
            LegacyRakVaultImporter.ErrorKind.ARGON2_FAILED,
            -> ErrorAction.RETRY_PASSWORD
            LegacyRakVaultImporter.ErrorKind.UNSUPPORTED_SCHEMA,
            LegacyRakVaultImporter.ErrorKind.UNSUPPORTED_FORMAT,
            LegacyRakVaultImporter.ErrorKind.MALFORMED_PAYLOAD,
            LegacyRakVaultImporter.ErrorKind.INVALID_ENVELOPE,
            LegacyRakVaultImporter.ErrorKind.FILE_EMPTY,
            LegacyRakVaultImporter.ErrorKind.FILE_TOO_LARGE,
            LegacyRakVaultImporter.ErrorKind.PAYLOAD_EMPTY,
            LegacyRakVaultImporter.ErrorKind.PAYLOAD_TOO_LARGE,
            -> ErrorAction.RESTART
            null -> ErrorAction.RESTART
        }

    private fun toImportMessage(e: LegacyRakVaultImporter.ImportException): String =
        when (e.kind) {
            LegacyRakVaultImporter.ErrorKind.AUTHENTICATION_FAILED ->
                "Unable to decrypt this legacy vault. " +
                    "The password may be incorrect or the file may be corrupted."
            LegacyRakVaultImporter.ErrorKind.UNSUPPORTED_SCHEMA ->
                "This legacy vault uses an unsupported schema version and cannot be imported."
            LegacyRakVaultImporter.ErrorKind.UNSUPPORTED_FORMAT ->
                "This is not a legacy Rescue Auth vault file, or it uses unsupported protection settings."
            LegacyRakVaultImporter.ErrorKind.INVALID_ENVELOPE ->
                "This legacy vault file is damaged and cannot be read."
            LegacyRakVaultImporter.ErrorKind.MALFORMED_PAYLOAD ->
                "This legacy vault contains invalid data and cannot be imported."
            LegacyRakVaultImporter.ErrorKind.FILE_EMPTY ->
                "This file is empty. Choose a legacy vault (.rakvault) file."
            LegacyRakVaultImporter.ErrorKind.FILE_TOO_LARGE ->
                "This file is larger than the 64 MB legacy limit."
            LegacyRakVaultImporter.ErrorKind.PAYLOAD_EMPTY,
            LegacyRakVaultImporter.ErrorKind.PAYLOAD_TOO_LARGE,
            -> "This legacy vault contains invalid data and cannot be imported."
            LegacyRakVaultImporter.ErrorKind.ARGON2_FAILED ->
                "Unable to decrypt this legacy vault. The password may be incorrect or the file may be corrupted."
            null -> "Something went wrong during import."
        }
}
