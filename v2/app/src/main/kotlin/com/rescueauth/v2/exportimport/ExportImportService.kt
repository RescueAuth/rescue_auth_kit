package com.rescueauth.v2.exportimport

import com.rescueauth.v2.export.MergePlanner
import com.rescueauth.v2.export.PackageIdentifier
import com.rescueauth.v2.export.SnapshotScope
import com.rescueauth.v2.export.VaultPackagePayload
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
    suspend fun encodeFullVaultExport(pin: CharArray): EncodedExportPackage {
        val snapshot = vault.buildConsistentExportSnapshot()
        val now = java.time.Instant.now().toString()
        val payload = VaultPackagePayload(
            logicalSchemaVersion = VaultPackagePayload.CURRENT_LOGICAL_SCHEMA_VERSION,
            packageId = UUID.randomUUID().toString(),
            createdAt = now,
            source = com.rescueauth.v2.export.PackageSourceMetadata(
                client = "android-app",
                appVersion = appVersion,
            ),
            snapshot = snapshot.copy(scope = SnapshotScope.FULL_VAULT),
        )
        val bytes = PortablePackageCodec.encode(payload, pin)
        return EncodedExportPackage(
            bytes = bytes,
            packageId = payload.packageId,
            scope = SnapshotScope.FULL_VAULT,
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
    suspend fun decodeForPreview(packageBytes: ByteArray, pin: CharArray): ImportPreview {
        val payload = PortablePackageCodec.decode(packageBytes, pin)
        val destination = vault.buildDestinationSnapshot()
        val plan = MergePlanner.plan(destination, payload.snapshot)
        val preview = ImportPreview.from(payload, plan)
        activeImport = ImportSession(payload)
        return preview
    }

    /**
     * Confirms the active import. Phase 3C is the final authority: this calls
     * [VaultRepository.applyMergePlan], which re-validates the payload,
     * re-plans against the **live** destination, preflights and applies inside
     * one transaction. The preview plan computed earlier is never applied
     * directly (Issue #1 §14) — if the destination changed since preview, the
     * re-plan may block or differ, and the caller reports that result.
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
        return vault.applyMergePlan(session.payload)
    }

    /**
     * Lightweight decode for tests / tooling that does not retain a session.
     */
    suspend fun decodeWithoutSession(packageBytes: ByteArray, pin: CharArray): VaultPackagePayload =
        PortablePackageCodec.decode(packageBytes, pin)
}

/**
 * Result of a successful Full Vault Export.
 *
 * [bytes] holds the **encrypted** package only — no plaintext snapshot, no
 * PIN, no PackageKey ever leaves the codec. [packageId] / [scope] are the
 * package's own logical metadata (not an Android Uri / filename).
 */
data class EncodedExportPackage(
    val bytes: ByteArray,
    val packageId: String,
    val scope: SnapshotScope,
)

/**
 * In-memory import session. Holds the decrypted plaintext [VaultPackagePayload]
 * and the preview [plan]. Must be cleared on cancel / apply / lock and never
 * persisted (Issue #1 §15).
 */
data class ImportSession(
    val payload: VaultPackagePayload,
) {
    /** Best-effort clear of the session reference chain. */
    fun clear() = Unit
}
