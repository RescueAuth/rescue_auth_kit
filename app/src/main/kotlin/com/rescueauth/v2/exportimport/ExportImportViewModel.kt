package com.rescueauth.v2.exportimport

import android.content.Context
import android.net.Uri
import com.rescueauth.v2.export.SelectedItemSet
import com.rescueauth.v2.export.SelectableItems
import com.rescueauth.v2.repository.ImportOutcome
import com.rescueauth.v2.repository.VaultRepository
import com.rescueauth.v2.security.SensitiveAction
import com.rescueauth.v2.security.SensitiveActionGate
import com.rescueauth.v2.security.SensitiveActionRequest
import com.rescueauth.v2.security.SensitiveActionResult
import com.rescueauth.v2.security.SensitiveActionTarget
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
 * Export: [beginExport] → PIN + confirm → SAF CreateDocument →
 *   [onExportDestinationPicked] (build snapshot → encode → write) →
 *   success / failure. **PIN comes before CreateDocument** so cancelling the
 *   PIN never creates a document (and cancelling the SAF picker creates
 *   nothing either) — ordinary cancellation leaves no empty `.rakpkg` file.
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
    private val sensitiveActionGate: SensitiveActionGate? = null,
) {

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    /**
     * The export scope the user chose on the Export intro (Issue #20 §3).
     * The scope is fixed BEFORE re-auth; the one-shot re-auth authorizes
     * exactly this scope + selection.
     */
    data class PendingExportScope(
        val scope: ExportScopeSpec,
        val selection: SelectedItemSet = SelectedItemSet(),
    ) {
        val selectionDigest: String?
            get() = if (scope == ExportScopeSpec.SelectedItems) selection.digest() else null

        /** The exact request target used to bind the re-auth. */
        fun requestTarget(): SensitiveActionTarget = SensitiveActionTarget.ExportRequest(
            scopeName = scope.name,
            selectionDigest = selectionDigest,
        )
    }

    sealed interface ExportState {
        /** Choosing the export scope (Entire Vault / Authenticator / Developer / Selected Items). */
        object Idle : ExportState
        /** Picking the items for a Selected-Items export (safe metadata only). */
        data class SelectingItems(val items: SelectableItems) : ExportState
        /** Re-auth is required before the PIN flow (fresh Biometric/Device Credential). */
        object AwaitingReauth : ExportState
        /** Waiting for the user to enter + confirm the per-export PIN (before CreateDocument). */
        object AwaitingPin : ExportState
        /** PIN accepted; waiting for the SAF CreateDocument destination. */
        object AwaitingDestination : ExportState
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
        /** Choosing what to import from the decoded package (safe summary + picker). */
        data class ChoosingScope(val preview: ImportPreview) : ImportState
        /** Picking items for a Selected-Items import (safe metadata only). */
        data class SelectingItems(val items: SelectableItems) : ImportState
        /** Safe preview of the chosen subset is ready. */
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
     * The export scope + selection chosen on the Export intro (Issue #20 §3,
     * §5). Fixed BEFORE re-auth; the one-shot re-auth authorizes exactly this
     * scope + selection ([SensitiveActionTarget.ExportRequest]) and the final
     * snapshot is re-resolved against the live Vault at encode time.
     */
    @Volatile
    private var pendingExportScope: PendingExportScope? = null

    /**
     * Transient per-export PIN held ONLY between PIN confirmation and the SAF
     * destination picker. It lives in an in-memory field (not
     * SavedStateHandle / Bundle / rememberSaveable / disk / DataStore / Room),
     * is zeroized on cancel / apply / lock / dispose, and is never exposed
     * through Compose state (Issue #1 §9, §15).
     */
    @Volatile
    private var pendingExportPin: CharArray? = null

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

    /** Resolves the current service WITHOUT throwing when the session is locked. */
    private fun resolveServiceOrNull(): ExportImportService? =
        activeService ?: serviceProvider()?.also { activeService = it }

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
                    pendingExportScope = null
                    pendingExportPin?.fill('\u0000')
                    pendingExportPin = null
                    if (_importState.value is ImportState.Preview ||
                        _importState.value is ImportState.AwaitingPin ||
                        _importState.value is ImportState.Decoding ||
                        _importState.value is ImportState.ChoosingScope ||
                        _importState.value is ImportState.SelectingItems
                    ) {
                        _importState.value = ImportState.Idle
                    }
                    if (_exportState.value is ExportState.AwaitingReauth ||
                        _exportState.value is ExportState.AwaitingPin ||
                        _exportState.value is ExportState.AwaitingDestination ||
                        _exportState.value is ExportState.Working ||
                        _exportState.value is ExportState.SelectingItems
                    ) {
                        _exportState.value = ExportState.Idle
                    }
                    // The gate (if injected) invalidates its own pending
                    // action via its session wiring; also reset any local
                    // re-auth expectation.
                }
            }
        }
    }

    /** Releases the session-collection job and zeroizes any held PIN (called from Compose onDispose). */
    fun dispose() {
        sessionJob?.cancel()
        pendingExportPin?.fill('\u0000')
        pendingExportPin = null
    }

    // ------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------

    /**
     * Starts the export flow from the scope intro.
     *
     * Phase 4 P5: the user first picks a scope (Entire Vault / Authenticator /
     * Developer / Selected Items) on the intro ([ExportState.Idle]).
     * [beginExport] then runs the SAME fresh re-auth gate for EVERY scope —
     * all `.rakpkg` exports carry long-term secrets, so none may skip re-auth
     * (Issue #20 §7). The request is bound to the exact scope + selection
     * ([SensitiveActionTarget.ExportRequest]); an auth result for scope A can
     * never authorize scope B (tests 38/39).
     */
    fun beginExport(scopeSpec: ExportScopeSpec = ExportScopeSpec.FullVault) {
        if (_exportState.value is ExportState.Working) return
        if (scopeSpec == ExportScopeSpec.SelectedItems) {
            // Enter the selection screen first (safe metadata), then re-auth
            // with the committed selection (Issue #20 §6).
            _exportState.value = ExportState.SelectingItems(items = SelectableItems())
            loadExportSelectable()
            return
        }
        beginExportForScope(PendingExportScope(scope = scopeSpec))
    }

    private fun beginExportForScope(pending: PendingExportScope) {
        if (_exportState.value is ExportState.Working) return
        val gate = sensitiveActionGate
        if (gate == null) {
            // No gate registered (locked session / tests without re-auth
            // wiring): stay Idle; the UI remains on the export intro.
            return
        }
        pendingExportScope = pending
        _exportState.value = ExportState.AwaitingReauth
        val request = SensitiveActionRequest(
            action = SensitiveAction.EXPORT_PACKAGE,
            target = pending.requestTarget(),
        )
        gate.authorize(
            request,
            onResult = { result -> onExportReauthResult(result, request) },
        )
    }

    /**
     * Loads the safe, selectable enumeration of the CURRENT Vault for the
     * Selected-Items export picker. Fails safe (returns to Idle) when the
     * Vault cannot be read / is locked. The loaded items only replace the
     * placeholder if the user has NOT already confirmed the selection and
     * moved past this screen (otherwise a late load would overwrite the
     * re-auth state — Issue #20 §6 race).
     */
    private fun loadExportSelectable() {
        scope.launch {
            try {
                val service = resolveService()
                val items = service.selectableExportItems()
                if (_exportState.value is ExportState.SelectingItems) {
                    _exportState.value = ExportState.SelectingItems(items = items)
                }
            } catch (e: VaultRepository.SessionLockedException) {
                if (_exportState.value is ExportState.SelectingItems) {
                    _exportState.value = ExportState.Error(
                        "Your vault is locked. Unlock it and try again.",
                    )
                }
            } catch (e: Exception) {
                if (_exportState.value is ExportState.SelectingItems) {
                    _exportState.value = ExportState.Error(
                        "Could not load your vault for selection.",
                    )
                }
            }
        }
    }

    /**
     * Commits the Selected-Items selection and moves to re-auth. Empty
     * selection cannot continue (the UI also disables the button; this is
     * defense-in-depth).
     */
    fun confirmExportSelection(selection: SelectedItemSet) {
        if (_exportState.value !is ExportState.SelectingItems) return
        if (selection.isEmpty) {
            _exportState.value = ExportState.Error("Select at least one item to export.")
            return
        }
        beginExportForScope(
            PendingExportScope(scope = ExportScopeSpec.SelectedItems, selection = selection),
        )
    }

    private fun onExportReauthResult(
        result: SensitiveActionResult,
        request: SensitiveActionRequest,
    ) {
        when (result) {
            is SensitiveActionResult.Success -> {
                // Consume the one-shot authorization ONLY when the authorized
                // request matches the original export request (Issue #20
                // §7/§8): the token is immediately spent. A follow-up Export
                // request requires a fresh re-auth.
                if (result.request == request) {
                    val gate = sensitiveActionGate
                    if (gate != null && gate.executePending(request) { }) {
                        beginPinFlow()
                    } else {
                        _exportState.value = ExportState.Idle
                    }
                } else {
                    _exportState.value = ExportState.Idle
                }
            }
            SensitiveActionResult.Cancelled,
            SensitiveActionResult.Failed,
            SensitiveActionResult.Unavailable,
            -> {
                // Export must NOT continue: no PIN flow, no document, no
                // snapshot (Issue #20 §5).
                pendingExportScope = null
                _exportState.value = ExportState.Idle
            }
        }
    }

    /** Moves to the per-export PIN step (after a successful fresh re-auth). */
    private fun beginPinFlow() {
        _exportState.value = ExportState.AwaitingPin
    }

    /**
     * Validates the entered per-export PIN + confirmation. On success the PIN
     * is retained in a transient in-memory field while the SAF destination is
     * chosen (PIN + confirm FIRST, CreateDocument SECOND — cancelling the PIN
     * never creates a document). On rejection returns the [PinPolicy.Reason]
     * so the UI can show it; returns `null` when the flow may proceed.
     */
    fun submitExportPin(pin: CharArray, confirm: CharArray): PinPolicy.Reason? {
        when (val v = PinPolicy.validateExportPin(pin, confirm)) {
            is PinPolicy.Validation.Valid -> {
                confirm.fill('\u0000')
                pendingExportPin?.fill('\u0000')
                pendingExportPin = pin
                _exportState.value = ExportState.AwaitingDestination
                return null
            }
            is PinPolicy.Validation.Invalid -> {
                pin.fill('\u0000')
                confirm.fill('\u0000')
                return v.reason
            }
        }
    }

    /**
     * Called after the user picks (or the system created) a SAF destination.
     * Builds a consistent snapshot for the chosen scope, encodes with the
     * retained PIN, writes ONLY the encrypted package bytes, and reports
     * success/failure. On any failure the partially-created document is
     * best-effort deleted (never crashes if the provider does not support
     * deletion).
     *
     * Phase 4 P5: the FINAL snapshot is built here (after re-auth and after
     * the destination is chosen) and the selection is re-resolved against the
     * live Vault. A stale selection (missing stableId) fails safely instead of
     * silently exporting a different object (Issue #20 §5, §8).
     */
    fun onExportDestinationPicked(uri: Uri) {
        if (_exportState.value !is ExportState.AwaitingDestination) return
        val pin = pendingExportPin
        if (pin == null) {
            _exportState.value = ExportState.AwaitingPin
            return
        }
        val pending = pendingExportScope
        if (pending == null) {
            // No scope was ever committed (defense-in-depth): cannot export.
            pendingExportUri = null
            scope.launch { bestEffortDelete(uri) }
            _exportState.value = ExportState.Idle
            return
        }
        pendingExportUri = uri
        _exportState.value = ExportState.Working
        scope.launch {
            try {
                val service = resolveService()
                val encoded = service.encodeExport(pending.scope, pending.selection, pin)
                fileIo.write(context, uri, encoded.bytes)
                pendingExportUri = null
                _exportState.value = ExportState.Success(
                    packageId = encoded.packageId,
                    scopeLabel = encoded.scope.name,
                )
            } catch (e: VaultRepository.SessionLockedException) {
                pendingExportUri = null
                bestEffortDelete(uri)
                _exportState.value = ExportState.Error(
                    "Your vault is locked. Unlock it and try again.",
                )
            } catch (e: StaleSelectionException) {
                pendingExportUri = null
                bestEffortDelete(uri)
                _exportState.value = ExportState.Error(
                    "The selected item(s) changed since you chose them. Go back and re-select.",
                )
            } catch (e: EmptySelectionException) {
                pendingExportUri = null
                bestEffortDelete(uri)
                _exportState.value = ExportState.Error(
                    "Select at least one item to export.",
                )
            } catch (e: ExportImportError) {
                pendingExportUri = null
                bestEffortDelete(uri)
                _exportState.value = ExportState.Error(toExportMessage(e))
            } catch (e: Exception) {
                pendingExportUri = null
                bestEffortDelete(uri)
                _exportState.value = ExportState.Error(toExportMessage(ExportImportError.Unknown))
            } finally {
                pin.fill('\u0000')
                pendingExportPin?.fill('\u0000')
                pendingExportPin = null
                pendingExportScope = null
            }
        }
    }

    /** Best-effort cleanup of a partially written SAF document. Never crashes. */
    private suspend fun bestEffortDelete(uri: Uri) {
        try {
            fileIo.deleteIfPossible(context, uri)
        } catch (_: Exception) {
            // Cleanup is best-effort: a provider that cannot delete must not
            // crash the export or turn a normal cancel into a failure.
        }
    }

    /** Cancels the export flow — no document created, no error state. */
    fun cancelExport() {
        if (_exportState.value is ExportState.AwaitingReauth) {
            // A prompt may be showing: cancel the gate's pending action.
            sensitiveActionGate?.cancelPending()
        }
        pendingExportUri = null
        pendingExportScope = null
        pendingExportPin?.fill('\u0000')
        pendingExportPin = null
        _exportState.value = ExportState.Idle
    }

    /**
     * The SAF CreateDocument picker was cancelled after PIN confirmation.
     * Nothing was written; return to PIN entry so the user can re-enter.
     */
    fun cancelExportDestination() {
        pendingExportUri = null
        _exportState.value = ExportState.AwaitingPin
    }

    /** Clears an export result/error so the user can start again. */
    fun resetExport() {
        pendingExportUri = null
        pendingExportScope = null
        pendingExportPin?.fill('\u0000')
        pendingExportPin = null
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
     *
     * Phase 4 P5: after decode the user chooses an import scope
     * ([ImportState.ChoosingScope]) instead of going straight to the final
     * preview (Issue #20 §11).
     */
    fun decodeImportWithPin(pin: CharArray) {
        val uri = pendingImportUri
        if (uri == null) {
            pin.fill('\u0000')
            _importState.value = ImportState.Error("Choose a package file first.")
            return
        }
        // Defense-in-depth: the UI already prevents an empty submit, but the
        // ViewModel also rejects it so a useless decode is never attempted.
        // Length / charset rules are deliberately NOT applied here — any
        // codec-legal package PIN must be decodable (Issue #1 §9).
        if (PinPolicy.validateImportPin(pin) !is PinPolicy.Validation.Valid) {
            pin.fill('\u0000')
            _importState.value = ImportState.AwaitingPin
            return
        }
        _importState.value = ImportState.Decoding
        scope.launch {
            try {
                val bytes = fileIo.readBounded(context, uri)
                val service = resolveService()
                val preview = service.decodeForPreview(bytes, pin)
                _importState.value = ImportState.ChoosingScope(preview)
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
     * Phase 4 P5: user picks an import scope from the decoded package
     * (Everything / Authenticator / Developer / Selected Items). A section
     * option is only available when the package actually carries that section
     * (the UI derives availability from the safe preview counts).
     */
    fun chooseImportScope(importScope: ImportScopeSpec) {
        if (_importState.value !is ImportState.ChoosingScope) return
        when (importScope) {
            ImportScopeSpec.SelectedItems -> {
                _importState.value = ImportState.SelectingItems(items = SelectableItems())
                loadImportSelectable()
            }
            ImportScopeSpec.Everything,
            ImportScopeSpec.Authenticator,
            ImportScopeSpec.Developer,
            -> {
                val preset = resolveServiceOrNull()?.importPreset(importScope)
                if (preset == null) {
                    _importState.value = ImportState.Error("No package is loaded for import.")
                    return
                }
                _importState.value = ImportState.Decoding
                scope.launch {
                    try {
                        val preview = resolveService().filterActiveImport(preset)
                        _importState.value = ImportState.Preview(preview)
                    } catch (e: VaultRepository.SessionLockedException) {
                        _importState.value = ImportState.Error(
                            "Your vault is locked. Unlock it and try again.",
                        )
                    } catch (e: StaleSelectionException) {
                        _importState.value = ImportState.Error(
                            "A selected item is not present in this package. Go back and re-select.",
                        )
                    } catch (e: EmptySelectionException) {
                        _importState.value = ImportState.Error(
                            "Select at least one item to import.",
                        )
                    } catch (e: Exception) {
                        _importState.value = ImportState.Error(
                            toImportMessage(ExportImportError.fromCodecOrRead(e)),
                        )
                    }
                }
            }
        }
    }

    private fun loadImportSelectable() {
        val service = resolveServiceOrNull()
        val items = service?.selectableImportItems()
        if (items == null) {
            _importState.value = ImportState.Error("No package is loaded for import.")
            return
        }
        _importState.value = ImportState.SelectingItems(items = items)
    }

    /**
     * Phase 4 P5: applies the user's Selected-Items import filter and builds
     * the final safe preview of the filtered subset (MergePlanner runs only on
     * the filtered snapshot — Issue #20 §12).
     */
    fun confirmImportSelection(selection: SelectedItemSet) {
        if (_importState.value !is ImportState.SelectingItems) return
        if (selection.isEmpty) {
            _importState.value = ImportState.Error("Select at least one item to import.")
            return
        }
        _importState.value = ImportState.Decoding
        scope.launch {
            try {
                val preview = resolveService().filterActiveImport(selection)
                _importState.value = ImportState.Preview(preview)
            } catch (e: VaultRepository.SessionLockedException) {
                _importState.value = ImportState.Error(
                    "Your vault is locked. Unlock it and try again.",
                )
            } catch (e: StaleSelectionException) {
                _importState.value = ImportState.Error(
                    "A selected item is not present in this package. Go back and re-select.",
                )
            } catch (e: EmptySelectionException) {
                _importState.value = ImportState.Error(
                    "Select at least one item to import.",
                )
            } catch (e: Exception) {
                _importState.value = ImportState.Error(
                    toImportMessage(ExportImportError.fromCodecOrRead(e)),
                )
            }
        }
    }

    /**
     * Confirms the import. Phase 3C is the final authority: the service calls
     * [VaultRepository.applySnapshot], which re-validates the filtered source,
     * re-plans against the LIVE destination, preflights and applies inside one
     * transaction. The preview plan is never applied directly.
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
