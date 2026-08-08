package com.rescueauth.v2.exportimport

import android.content.Context
import android.net.Uri
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
 * Phase 3D Export / Import coordinator (Issue #1 §1, §10, §11, §28).
 *
 * Plain class (not androidx ViewModel) so it is trivially JVM-testable; the
 * Compose layer provides the [scope]. It holds **no secrets** in state: the
 * per-export PIN is a transient [CharArray] passed directly to the codec, the
 * decrypted plaintext payload lives only inside [ExportImportService]'s active
 * import session, and this class keeps only the safe [ImportPreview] summary
 * for the UI.
 *
 * ## Export orchestration (single entry point for a future re-auth gate)
 *
 * [beginExport] is the **single** entry point where Phase 4 P4's
 * fresh Biometric/Device-credential re-auth gate will be inserted BEFORE
 * [finishExportWithPin] builds the snapshot / encodes / writes (Issue #1 §10).
 * Phase 3D intentionally does not fake a re-auth subsystem.
 *
 * ## Flow steps
 *
 * Export: [beginExport] → PIN dialog → SAF CreateDocument → [exportToUri]
 *   (build snapshot → encode → write) → success / failure.
 * Import: SAF OpenDocument → [handlePickedDocument] (bounded read + identify)
 *   → PIN dialog → [decodeImportWithPin] → [ImportPreview] → [confirmImport]
 *   → result. Cancelling any step clears the session and produces no error.
 */
class ExportImportViewModel(
    private val context: Context,
    private val serviceProvider: () -> ExportImportService?,
    private val fileIo: PackageFileIo,
    private val sessionState: StateFlow<SecureSessionStateMachine.State>,
    private val scope: CoroutineScope,
) {

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    sealed interface ExportState {
        object Idle : ExportState
        /** Waiting for the user to enter + confirm the per-export PIN. */
        object AwaitingPin : ExportState
        /** Encrypting + writing the package to SAF. */
        object Working : ExportState
        data class Success(val packageId: String, val scopeLabel: String) : ExportState
        data class Error(val message: String) : ExportState
    }

    sealed interface ImportState {
        object Idle : ImportState
        /** Waiting for the user to enter the package PIN. */
        object AwaitingPin : ImportState
        /** Decoding + validating + planning. */
        object Decoding : ImportState
        /** Safe preview is ready. */
        data class Preview(val preview: ImportPreview) : ImportState
        /** Applying the import transactionally. */
        object Applying : ImportState
        data class Result(
            val imported: Int,
            val duplicates: Int,
            val conflicts: Int,
            val stateDivergences: Int,
            val developerImported: Int,
            val blocked: Boolean,
        ) : ImportState
        data class Error(val message: String) : ImportState
    }

    private val _exportState = MutableStateFlow<ExportState>(ExportState.Idle)
    val exportState: StateFlow<ExportState> = _exportState.asStateFlow()

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    /** Pending SAF document Uri selected by OpenDocument (cleared on cancel). */
    @Volatile
    private var pendingImportUri: Uri? = null

    /** Pending SAF destination Uri selected by CreateDocument (cleared on cancel). */
    @Volatile
    private var pendingExportUri: Uri? = null

    /**
     * The service instance used by the current import/export operation.
     *
     * Resolved once per operation so the import session (which lives inside
     * the service) is never split across two instances: `decodeForPreview`
     * stores the plaintext payload in the service, and `confirmImport` must
     * read it from the SAME instance (Issue #1 §15). Cleared on cancel / apply
     * / session lock.
     */
    @Volatile
    private var activeService: ExportImportService? = null

    /**
     * The active import session held by the current operation's service
     * (test-visible accessor). Returns null when idle/locked.
     */
    fun activeImportSession(): com.rescueauth.v2.exportimport.ImportSession? =
        activeService?.activeImportSession()

    private fun resolveService(): ExportImportService {
        val svc = activeService ?: serviceProvider()?.also { activeService = it }
        return svc ?: throw VaultRepository.SessionLockedException()
    }

    private var sessionJob: Job? = null

    init {
        // Clear any active import session when the Vault locks or invalidates
        // (Issue #1 §16): a decrypted package must never survive a lock.
        sessionJob = scope.launch {
            sessionState.collect { state ->
                if (state != SecureSessionStateMachine.State.UNLOCKED) {
                    activeService?.clearImportSession()
                    activeService = null
                    pendingImportUri = null
                    pendingExportUri = null
                    if (_importState.value is ImportState.Preview ||
                        _importState.value is ImportState.AwaitingPin ||
                        _importState.value is ImportState.Decoding
                    ) {
                        _importState.value = ImportState.Idle
                    }
                    if (_exportState.value is ExportState.AwaitingPin ||
                        _exportState.value is ExportState.Working
                    ) {
                        _exportState.value = ExportState.Idle
                    }
                }
            }
        }
    }

    /** Releases the session-collection job (called from Compose onDispose). */
    fun dispose() {
        sessionJob?.cancel()
    }

    // ------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------

    /**
     * Records the SAF destination chosen via CreateDocument and asks for the
     * per-export PIN. The PIN is therefore never held across the picker
     * callback. Phase 4 P4 will insert a fresh re-auth gate here, before any
     * snapshot / encode / write work (Issue #1 §10).
     */
    fun onExportDestinationPicked(uri: Uri) {
        if (_exportState.value is ExportState.Working) return
        pendingExportUri = uri
        _exportState.value = ExportState.AwaitingPin
    }

    /** Cancels the export flow — no error state, nothing written. */
    fun cancelExport() {
        pendingExportUri = null
        _exportState.value = ExportState.Idle
    }

    /**
     * Finalises the export to the previously chosen SAF destination with the
     * user-entered [pin]. Builds a consistent FULL_VAULT snapshot, encodes with
     * the per-export PIN, writes ONLY the encrypted package bytes, and reports
     * success/failure.
     */
    fun exportToUri(pin: CharArray) {
        val uri = pendingExportUri
        if (uri == null) {
            pin.fill('\u0000')
            _exportState.value = ExportState.Error(toExportMessage(ExportImportError.WriteFailed))
            return
        }
        _exportState.value = ExportState.Working
        scope.launch {
            try {
                val service = resolveService()
                val encoded = service.encodeFullVaultExport(pin)
                fileIo.write(context, uri, encoded.bytes)
                pendingExportUri = null
                _exportState.value = ExportState.Success(
                    packageId = encoded.packageId,
                    scopeLabel = "FULL_VAULT",
                )
            } catch (e: VaultRepository.SessionLockedException) {
                pendingExportUri = null
                _exportState.value = ExportState.Error(
                    "Your vault is locked. Unlock it and try again.",
                )
            } catch (e: ExportImportError) {
                pendingExportUri = null
                _exportState.value = ExportState.Error(toExportMessage(e))
            } catch (e: Exception) {
                pendingExportUri = null
                _exportState.value = ExportState.Error(toExportMessage(ExportImportError.Unknown))
            } finally {
                pin.fill('\u0000')
            }
        }
    }

    /** Clears an export result/error so the user can start again. */
    fun resetExport() {
        pendingExportUri = null
        _exportState.value = ExportState.Idle
    }

    // ------------------------------------------------------------------
    // Import
    // ------------------------------------------------------------------

    /**
     * Handles a document picked via SAF OpenDocument. Reads a bounded prefix,
     * identifies the package, and requests the package PIN for a native v2
     * package. A non-native / empty document produces a safe message.
     */
    fun handlePickedDocument(uri: Uri) {
        if (_importState.value is ImportState.Decoding) return
        pendingImportUri = uri
        _importState.value = ImportState.Decoding
        scope.launch {
            try {
                val prefix = fileIo.readPrefix(context, uri)
                when (val id = com.rescueauth.v2.export.PackageIdentifier.identify(prefix)) {
                    is com.rescueauth.v2.export.PackageIdentifier.Result.NativeV2Package -> {
                        _importState.value = ImportState.AwaitingPin
                    }
                    com.rescueauth.v2.export.PackageIdentifier.Result.EmptyDocument -> {
                        pendingImportUri = null
                        _importState.value = ImportState.Error(
                            "This document is empty. Choose a Rescue Auth package file.",
                        )
                    }
                    is com.rescueauth.v2.export.PackageIdentifier.Result.NotNativePackage -> {
                        pendingImportUri = null
                        _importState.value = ImportState.Error(
                            "This does not look like a Rescue Auth package. " +
                                "If it is a legacy .rakvault file, legacy import is a separate flow.",
                        )
                    }
                }
            } catch (e: ExportImportError) {
                pendingImportUri = null
                _importState.value = ImportState.Error(toImportMessage(e))
            } catch (e: Exception) {
                pendingImportUri = null
                _importState.value = ImportState.Error(toImportMessage(ExportImportError.ReadFailed))
            }
        }
    }

    /** Cancels the import flow — clears the active session, no error. */
    fun cancelImport() {
        pendingImportUri = null
        activeService?.clearImportSession()
        activeService = null
        _importState.value = ImportState.Idle
    }

    /**
     * Reads the full bounded package bytes and decodes with [pin], producing a
     * safe [ImportPreview]. The plaintext payload stays in the service's active
     * import session only (never in this ViewModel / Compose state).
     */
    fun decodeImportWithPin(pin: CharArray) {
        val uri = pendingImportUri
        if (uri == null) {
            pin.fill('\u0000')
            _importState.value = ImportState.Error("Choose a package file first.")
            return
        }
        _importState.value = ImportState.Decoding
        scope.launch {
            try {
                val bytes = fileIo.readBounded(context, uri)
                val service = resolveService()
                val preview = service.decodeForPreview(bytes, pin)
                _importState.value = ImportState.Preview(preview)
            } catch (e: VaultRepository.SessionLockedException) {
                _importState.value = ImportState.Error(
                    "Your vault is locked. Unlock it and try again.",
                )
            } catch (e: Exception) {
                // Wrong PIN and corrupted package both surface as a single
                // safe message (Issue #1 §11).
                _importState.value = ImportState.Error(toImportMessage(ExportImportError.fromCodecOrRead(e)))
            } finally {
                pin.fill('\u0000')
            }
        }
    }

    /**
     * Confirms the import. Phase 3C is the final authority: the service calls
     * [VaultRepository.applyMergePlan], which re-validates / re-plans /
     * preflights / applies against the LIVE destination in one transaction.
     * The preview plan is never applied directly.
     */
    fun confirmImport() {
        _importState.value = ImportState.Applying
        scope.launch {
            try {
                val service = resolveService()
                val outcome = service.confirmImport()
                when (outcome) {
                    is ImportOutcome.Applied -> {
                        _importState.value = ImportState.Result(
                            imported = outcome.result.insertedTotal,
                            duplicates = outcome.result.duplicates,
                            conflicts = outcome.result.conflicts,
                            stateDivergences = outcome.result.stateDivergences,
                            developerImported = outcome.result.insertedDeveloperEntries,
                            blocked = false,
                        )
                    }
                    is ImportOutcome.Blocked -> {
                        _importState.value = ImportState.Result(
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
                _importState.value = ImportState.Error(
                    "Your vault is locked. Unlock it and try again.",
                )
            } catch (e: Exception) {
                _importState.value = ImportState.Error(
                    toImportMessage(ExportImportError.Unknown),
                )
            }
        }
    }

    /** Clears the import result/error so the user can start again. */
    fun resetImport() {
        pendingImportUri = null
        activeService?.clearImportSession()
        activeService = null
        _importState.value = ImportState.Idle
    }

    // ------------------------------------------------------------------
    // Message mapping
    // ------------------------------------------------------------------

    private fun toExportMessage(e: ExportImportError): String = when (e) {
        ExportImportError.UserCancelled -> ""
        ExportImportError.CannotOpenDocument -> "Could not create the export document."
        ExportImportError.ReadFailed -> "Could not read the export document."
        ExportImportError.PackageTooLarge -> "The exported package is too large."
        ExportImportError.WriteFailed -> "Could not write the package file. The export was not completed."
        ExportImportError.SessionLocked -> "Your vault is locked. Unlock it and try again."
        ExportImportError.ExportSnapshotFailed -> "Could not build a consistent snapshot of your vault."
        is ExportImportError.UnsupportedFormat -> "This is not a Rescue Auth package."
        ExportImportError.UnsupportedCrypto -> "This package uses an unsupported encryption version."
        ExportImportError.MalformedPackage -> "This package file is damaged."
        ExportImportError.InvalidKdfParameters -> "This package uses unsupported protection settings."
        ExportImportError.AuthenticationFailed -> "Wrong PIN or corrupted package."
        ExportImportError.LogicalPayloadInvalid -> "This package contains invalid data."
        ExportImportError.Unknown -> "Something went wrong during export."
    }

    private fun toImportMessage(e: ExportImportError): String = when (e) {
        ExportImportError.UserCancelled -> ""
        ExportImportError.CannotOpenDocument -> "Could not open the document."
        ExportImportError.ReadFailed -> "Could not read the document."
        ExportImportError.PackageTooLarge -> "This package is larger than the 16 MB limit."
        ExportImportError.WriteFailed -> "Could not write the package file."
        ExportImportError.SessionLocked -> "Your vault is locked. Unlock it and try again."
        ExportImportError.ExportSnapshotFailed -> "Could not build a consistent snapshot."
        is ExportImportError.UnsupportedFormat -> "This is not a Rescue Auth package."
        ExportImportError.UnsupportedCrypto -> "This package uses an unsupported encryption version."
        ExportImportError.MalformedPackage -> "This package file is damaged."
        ExportImportError.InvalidKdfParameters -> "This package uses unsupported protection settings."
        ExportImportError.AuthenticationFailed -> "Wrong PIN or corrupted package."
        ExportImportError.LogicalPayloadInvalid -> "This package contains invalid data."
        ExportImportError.Unknown -> "Something went wrong during import."
    }
}

/**
 * Maps an arbitrary exception from the codec/bounded-reader to the safe
 * [ExportImportError] taxonomy.
 */
internal fun ExportImportError.Companion.fromCodecOrRead(e: Exception): ExportImportError = when (e) {
    is com.rescueauth.v2.export.codec.PackageCodecException -> fromCodec(e)
    is com.rescueauth.v2.export.BoundedPackageReader.ReadFailure -> ExportImportError.ReadFailed
    is ExportImportError -> e
    else -> ExportImportError.Unknown
}
