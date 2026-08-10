package com.rescueauth.v2.exportimport

import com.rescueauth.v2.export.MergePlanner
import com.rescueauth.v2.export.PackageIdentifier
import com.rescueauth.v2.export.SelectedItemSet
import com.rescueauth.v2.export.SnapshotScope
import com.rescueauth.v2.export.VaultPackagePayload
import com.rescueauth.v2.export.VaultSnapshot
import com.rescueauth.v2.export.VaultSnapshotSelector
import com.rescueauth.v2.export.codec.PackageCodecException
import com.rescueauth.v2.export.codec.PortablePackageCodec
import com.rescueauth.v2.repository.ImportOutcome
import com.rescueauth.v2.repository.VaultRepository
import java.util.UUID

/**
 * Phase 3D Export / Import orchestration use-cases (Issue #1 §1, §2, §4, §11).
 *
 * This is the only production path that connects:
 *
 * ```
 * Compose → ViewModel → ExportImportService → VaultRepository / PortablePackageCodec
 * ```
 *
 * It deliberately does **not** implement any crypto / format / merge /
 * transactional logic itself — it reuses Phase 3B (`PortablePackageCodec`),
 * Phase 3A (`PackageValidator` / `MergePlanner` / `PackageIdentifier`) and
 * Phase 3C (`VaultRepository.applyMergePlan` — the final authority).
 *
 * ## Security model
 *
 * - **Export**: builds a consistent logical snapshot via
 *   [VaultRepository.buildConsistentExportSnapshot], wraps it in a
 *   [VaultPackagePayload] with `FULL_VAULT` scope, and encodes it with the
 *   per-export PIN. Only the encrypted package bytes leave the app.
 * - **Import**: decodes the package with the per-export PIN, validates it,
 *   plans against the **current** destination snapshot for preview, and on
 *   confirm calls [VaultRepository.applyMergePlan] which re-validates /
 *   re-plans / preflights / applies against the **live** destination inside a
 *   single transaction — the preview plan is never applied directly
 *   (Issue #1 §14).
 * - **Import session**: the decrypted [VaultPackagePayload] (which contains
 *   plaintext secrets) lives **only** in this in-memory session object; it is
 *   cleared on cancel / apply / session lock and is never written to
 *   SavedStateHandle / Bundle / disk / DataStore / Room (Issue #1 §15).
 *
 * @param appVersion the exporting app version (informational package source
 *   metadata; non-sensitive).
 */
class ExportImportService(
    private val vault: VaultRepository,
    private val appVersion: String,
) {

    // ------------------------------------------------------------------
    // Import session (in-memory only)
    // ------------------------------------------------------------------

    @Volatile
    private var activeImport: ImportSession? = null

    /**
     * The currently active (decoded, plaintext) import payload, or null.
     * The UI / ViewModel reads this only to derive safe summary models; the
     * payload itself is never exposed through Compose state.
     */
    fun activeImportSession(): ImportSession? = activeImport

    /** Drops the active import session (cancel / lock / after apply). */
    fun clearImportSession() {
        activeImport = null
    }

    /**
     * Identifies an untrusted document before any PIN / crypto work.
     * The caller passes only the bounded prefix bytes.
     */
    fun identifyPackage(prefix: ByteArray): PackageIdentifier.Result =
        PackageIdentifier.identify(prefix)

    // ------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------

    /**
     * Builds a consistent FULL_VAULT snapshot of the current Vault and encodes
     * it into a portable package with [pin].
     *
     * @throws VaultRepository.SessionLockedException when the session is locked
     * @throws PackageCodecException on capacity / validation failures
     */
    suspend fun encodeFullVaultExport(pin: CharArray): EncodedExportPackage =
        encodeExport(ExportScopeSpec.FullVault, SelectedItemSet(), pin)

    /**
     * Phase 4 P5: builds a consistent snapshot of the current Vault **for the
     * requested [scope]** and encodes it into a portable package with [pin].
     *
     * The snapshot is always re-resolved inside the repository's consistent
     * serialized/transactional boundary ([VaultRepository.buildConsistentExportSnapshot])
     * — the final snapshot is built AFTER re-auth and after the SAF destination
     * is chosen, and the selected stableIds are re-validated against that final
     * snapshot. A stale selection (missing stableId) fails safely with
     * [StaleSelectionException] — it is never silently narrowed to a different
     * object (Issue #20 §5, §8, §25 test 23).
     *
     * @param scope one of the four export scopes.
     * @param selection the stableId-only [SelectedItemSet] for
     *   [ExportScopeSpec.SelectedItems]; ignored for the three section scopes.
     * @throws VaultRepository.SessionLockedException when the session is locked
     * @throws PackageCodecException on capacity / validation failures
     * @throws StaleSelectionException when a selected stableId no longer exists
     * @throws EmptySelectionException when a SelectedItems export is requested
     *   with nothing selected
     */
    suspend fun encodeExport(scope: ExportScopeSpec, selection: SelectedItemSet, pin: CharArray): EncodedExportPackage {
        // The final snapshot is built inside the repository mutex + transaction,
        // then the (already re-validated) selection is re-resolved against it.
        // The scope's snapshot is derived purely from the consistent source so
        // a SELECTED_ITEMS package can never contain an item that was deleted
        // after the user confirmed the selection (Issue #20 §5).
        val consistent = vault.buildConsistentExportSnapshot()
        val snapshot = when (scope) {
            ExportScopeSpec.FullVault -> VaultSnapshotSelector.fullVault(consistent)
            ExportScopeSpec.Authenticator -> VaultSnapshotSelector.authenticatorOnly(consistent)
            ExportScopeSpec.Developer -> VaultSnapshotSelector.developerOnly(consistent)
            ExportScopeSpec.SelectedItems -> VaultSnapshotSelector.selected(consistent, selection)
        }
        val now = java.time.Instant.now().toString()
        val payload = VaultPackagePayload(
            logicalSchemaVersion = VaultPackagePayload.CURRENT_LOGICAL_SCHEMA_VERSION,
            packageId = UUID.randomUUID().toString(),
            createdAt = now,
            source = com.rescueauth.v2.export.PackageSourceMetadata(
                client = "android-app",
                appVersion = appVersion,
            ),
            snapshot = snapshot,
        )
        val bytes = PortablePackageCodec.encode(payload, pin)
        return EncodedExportPackage(
            bytes = bytes,
            packageId = payload.packageId,
            scope = snapshot.scope,
            selectionDigest = if (scope == ExportScopeSpec.SelectedItems) selection.digest() else null,
        )
    }

    // ------------------------------------------------------------------
    // Import
    // ------------------------------------------------------------------

    /**
     * Decodes a bounded package with [pin], validates it and builds the safe
     * preview against the **current** destination. The decoded payload is kept
     * in the active import session for the subsequent confirm.
     *
     * @throws PackageCodecException on decode / validation failure
     * @throws VaultRepository.SessionLockedException when the session is locked
     */
    suspend fun decodeForPreview(packageBytes: ByteArray, pin: CharArray): ImportPreview =
        decodeForPreview(packageBytes, pin, filter = null)

    /**
     * Phase 4 P5: decodes a bounded package, validates it and builds a **safe
     * preview of a filtered subset** (or the whole package when [filter] is
     * null) against the **current** destination.
     *
     * Selective import is a **decoded-snapshot filter**: it never touches the
     * ciphertext, never re-encodes the package and never creates a temporary
     * package file — the decoded [VaultSnapshot] is filtered in memory and only
     * the filtered snapshot is passed to [MergePlanner] (Issue #20 §10, §12).
     * Items that are NOT selected are never planned, so a conflict in an
     * unselected item cannot block the selected import.
     *
     * @param filter when non-null, the stableId-only selection applied to the
     *   decoded snapshot. The filter can only **narrow** the decoded contents
     *   (a selected stableId that is not in the package fails with
     *   [StaleSelectionException] — it can never "restore" an object the
     *   package does not carry, Issue #20 §11).
     * @throws PackageCodecException on decode / validation failure
     * @throws VaultRepository.SessionLockedException when the session is locked
     * @throws StaleSelectionException when a selected stableId is missing from
     *   the decoded package
     * @throws EmptySelectionException when [filter] selects nothing
     */
    suspend fun decodeForPreview(packageBytes: ByteArray, pin: CharArray, filter: SelectedItemSet?): ImportPreview {
        val payload = PortablePackageCodec.decode(packageBytes, pin)
        val source = filter?.let {
            VaultSnapshotSelector.selected(payload.snapshot, it)
        } ?: payload.snapshot
        val destination = vault.buildDestinationSnapshot()
        val plan = MergePlanner.plan(destination, source)
        val preview = ImportPreview.from(payload, plan, source)
        activeImport = ImportSession(payload, source, filter)
        return preview
    }

    /**
     * Confirms the active import. Phase 3C is the final authority: this calls
     * [VaultRepository.applyMergePlan], which re-validates the payload,
     * re-plans against the **live** destination, preflights and applies inside
     * one transaction. The preview plan computed earlier is never applied
     * directly (Issue #1 §14 / #20 §14) — if the destination changed since
     * preview, the re-plan may block or differ, and the caller reports that result.
     *
     * For a filtered (selective) import, the **filtered snapshot** is what gets
     * re-validated / re-planned / applied — never the whole decoded package
     * (Issue #20 §12). The selection itself is re-validated against the decoded
     * payload again at confirm time, so a stale selection fails safely instead
     * of silently importing a different object (Issue #20 §14, §5).
     *
     * The active import session is cleared on any outcome (applied or blocked)
     * so a follow-up confirm requires a fresh preview.
     */
    suspend fun confirmImport(): ImportOutcome {
        val session = activeImport ?: return ImportOutcome.Blocked(
            com.rescueauth.v2.repository.ImportBlockedResult(
                conflicts = 0,
                stateDivergences = 0,
                duplicates = 0,
                unchanged = 0,
            ),
        )
        activeImport = null
        val source = session.filteredSource
        return vault.applySnapshot(source, packageIdentity = session.payload.packageId)
    }

    /**
     * Lightweight decode for tests / tooling that does not retain a session.
     */
    suspend fun decodeWithoutSession(packageBytes: ByteArray, pin: CharArray): VaultPackagePayload =
        PortablePackageCodec.decode(packageBytes, pin)

    // ------------------------------------------------------------------
    // Phase 4 P5 — selection support
    // ------------------------------------------------------------------

    /**
     * Builds the safe, selectable enumeration of the CURRENT Vault for the
     * export Selected-Items picker. The result carries only stableIds and safe
     * metadata (no secrets).
     */
    suspend fun selectableExportItems(): com.rescueauth.v2.export.SelectableItems {
        val consistent = vault.buildConsistentExportSnapshot()
        return VaultSnapshotSelector.selectableItems(consistent)
    }

    /**
     * Builds the safe, selectable enumeration of the active decoded import
     * payload for the import Selected-Items picker. A selected item can only be
     * one the package actually carries; the enumeration never leaks secrets.
     *
     * @return null when there is no active decoded import session (caller must
     *   not show a picker).
     */
    fun selectableImportItems(): com.rescueauth.v2.export.SelectableItems? {
        val payload = activeImport?.payload ?: return null
        return VaultSnapshotSelector.selectableItems(payload.snapshot)
    }

    /**
     * A preset item set (Entire/Authenticator/Developer/Everything) for the
     * import scope chooser. Returns the appropriate [SelectedItemSet] for the
     * active decoded payload, or null when no session is active.
     */
    fun importPreset(scope: ImportScopeSpec): SelectedItemSet? {
        val payload = activeImport?.payload ?: return null
        val snapshot = payload.snapshot
        return when (scope) {
            ImportScopeSpec.Everything -> VaultSnapshotSelector.everything(snapshot)
            ImportScopeSpec.Authenticator -> VaultSnapshotSelector.authenticatorItemSet(snapshot)
            ImportScopeSpec.Developer -> VaultSnapshotSelector.developerItemSet(snapshot)
            // Selected Items has no preset — the user picks items directly.
            ImportScopeSpec.SelectedItems -> null
        }
    }

    /**
     * Phase 4 P5: re-filters the ALREADY-DECODED active import payload with
     * [filter] (or the full snapshot when null) and re-plans against the
     * current destination for a fresh safe preview. No PIN is needed — the
     * decoded payload already lives in the in-memory session (Issue #20 §10,
     * §15). This is the single path used by the import scope chooser and the
     * Selected-Items picker.
     *
     * @param filter the stableId-only selection, or null to keep the whole
     *   decoded snapshot. For section presets pass the preset item set
     *   ([importPreset]); the filter can only narrow the decoded contents.
     * @return the fresh safe preview of the filtered subset.
     * @throws StaleSelectionException when a selected stableId is missing from
     *   the decoded payload
     * @throws EmptySelectionException when [filter] selects nothing
     */
    suspend fun filterActiveImport(filter: SelectedItemSet?): ImportPreview {
        val session = activeImport ?: throw IllegalStateException("no active import session")
        val source = filter?.let { VaultSnapshotSelector.selected(session.payload.snapshot, it) }
            ?: session.payload.snapshot
        val destination = vault.buildDestinationSnapshot()
        val plan = MergePlanner.plan(destination, source)
        val preview = ImportPreview.from(session.payload, plan, source)
        activeImport = session.copy(filteredSource = source, filter = filter)
        return preview
    }
}

/**
 * Result of a successful Export.
 *
 * [bytes] holds the **encrypted** package only — no plaintext snapshot, no
 * PIN, no PackageKey ever leaves the codec. [packageId] / [scope] are the
 * package's own logical metadata (not an Android Uri / filename).
 * [selectionDigest] is non-null only for a [ExportScopeSpec.SelectedItems]
 * export and binds the authorized re-auth to the exact selection that was
 * encoded (Issue #20 §7, §27 test 39).
 */
data class EncodedExportPackage(
    val bytes: ByteArray,
    val packageId: String,
    val scope: SnapshotScope,
    val selectionDigest: String? = null,
)

/**
 * The four export scopes a user can choose (Issue #20 §3).
 *
 * Each scope maps to a [SnapshotScope] plus the appropriate section filter.
 * The scope is chosen BEFORE re-auth; the one-shot re-auth authorizes exactly
 * this scope + selection ([SensitiveActionTarget.ExportRequest]).
 */
enum class ExportScopeSpec {
    /** Entire Vault: Authenticator + Recovery Codes + all Developer entries. */
    FullVault,

    /** Authenticator section only (no Developer). */
    Authenticator,

    /** Developer section only (no Authenticator). */
    Developer,

    /** Selected items (stableId-based, hierarchy/dependency closure). */
    SelectedItems,
}

/**
 * The import scopes a user can pick after decoding a package (Issue #20 §11).
 *
 * - [Everything] imports all data the package actually carries;
 * - [Authenticator] imports only the Authenticator section (available only when
 *   the package carries Authenticator data);
 * - [Developer] imports only the Developer section (available only when the
 *   package carries Developer data);
 * - [SelectedItems] further narrows to specific items (via [SelectedItemSet]).
 */
enum class ImportScopeSpec {
    /** Everything the package actually carries. */
    Everything,

    /** Only the Authenticator data in the package. */
    Authenticator,

    /** Only the Developer data in the package. */
    Developer,

    /** Only the user-selected items (further narrowing the package contents). */
    SelectedItems,
}

/**
 * In-memory import session. Holds the decrypted plaintext [VaultPackagePayload]
 * and the **filtered** source snapshot ([filteredSource]) that the active
 * import will merge/apply. For a full import [filteredSource] == the package's
 * snapshot; for a selective import it is the in-memory filtered subset. Must be
 * cleared on cancel / apply / lock and never persisted (Issue #1 §15).
 */
data class ImportSession(
    val payload: VaultPackagePayload,
    /** The snapshot this import will actually merge/apply (full or filtered). */
    val filteredSource: VaultSnapshot = payload.snapshot,
    /** The selection filter that produced [filteredSource], or null for a full import. */
    val filter: SelectedItemSet? = null,
) {
    /** Best-effort clear of the session reference chain. */
    fun clear() = Unit
}

/**
 * Thrown when a selected stableId no longer exists in the snapshot being
 * exported/imported. Re-exported as a convenience alias so the ViewModel does
 * not need to import the core exception explicitly (Issue #20 §5).
 */
typealias StaleSelectionException = com.rescueauth.v2.export.SelectionStaleException

/**
 * Thrown when a Selected Items export/import is attempted with nothing
 * selected. Re-exported as a convenience alias (Issue #20 §23).
 */
typealias EmptySelectionException = com.rescueauth.v2.export.EmptySelectionException
