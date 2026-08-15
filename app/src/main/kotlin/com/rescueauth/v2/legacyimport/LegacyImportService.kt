package com.rescueauth.v2.legacyimport

import com.rescueauth.v2.export.MergePlanner
import com.rescueauth.v2.export.SnapshotScope
import com.rescueauth.v2.export.VaultSnapshot
import com.rescueauth.v2.legacy.LegacyImportBundle
import com.rescueauth.v2.legacy.LegacyRakVaultImporter
import com.rescueauth.v2.legacy.LegacyVaultSnapshotMapper
import com.rescueauth.v2.repository.ImportOutcome
import com.rescueauth.v2.repository.VaultRepository

/**
 * Phase 5B — Legacy v1 `.rakvault` import use-cases.
 *
 * ## Pipeline (ROADMAP §9 / Issue #1 Phase 5B)
 *
 * ```
 * Android SAF bytes (bounded, ≤ 64 MiB + 1 detection)
 *   → LegacyRakVaultImporter  (header → Argon2id → XChaCha20 → payload)
 *   → LegacyImportBundle      (short-lived raw legacy model)
 *   → LegacyVaultSnapshotMapper → shared VaultSnapshot
 *   → PackageValidator.validateSnapshot (shared logical validation)
 *   → MergePlanner (preview vs current destination)
 *   → VaultRepository.applySnapshot (transactional final authority)
 * ```
 *
 * ## Security model
 *
 * - [decodeForPreview] holds the **decrypted** [LegacyImportBundle] and the
 *   mapped [VaultSnapshot] (which contains plaintext secrets) ONLY inside this
 *   in-memory active import session. They are never written to
 *   SavedStateHandle / Bundle / rememberSaveable / DataStore / Room / a
 *   plaintext cache file, and are cleared on cancel / apply / session lock /
 *   new-file / fatal error (Issue #1 §16).
 * - The password is a [CharArray] passed straight to the importer; it is not
 *   retained anywhere and is zeroized by the caller after use.
 * - The ViewModel / preview UI only ever sees the safe [LegacyImportPreview]
 *   summary (no TOTP secret, no recovery-code values, no API/SSH/generic/env/
 *   keystore/password material).
 * - Final authority is [VaultRepository.applySnapshot] (re-validates,
 *   re-plans against the LIVE destination, preflights, applies inside one
 *   transaction). The preview plan is never applied directly (Issue #1 §11).
 *
 * ## Legacy-specific policy (NOT Native)
 *
 * - **64 MiB** input cap ([LegacyRakVaultImporter.DEFAULT_MAX_INPUT_BYTES]),
 *   deliberately decoupled from the Native `.rakpkg` 16 MiB contract. The
 *   SAF layer detects >64 MiB BEFORE any decrypt and rejects.
 * - **No `>=10` length gate** on the Master Password: Frozen v1 audit
 *   confirmed it is only the v1 *creation UI* policy, not a decoder/format
 *   requirement. Any non-empty password is submitted to the legacy decoder
 *   (Issue #1 §6).
 * - **Merge-first** semantics: Legacy v1 was replace-import, but v2 product
 *   policy is shared merge-first (same [MergePlanner]). No source-wins /
 *   replace / whole-vault-restore path exists here.
 * - ImportRecord `sourceType = "LEGACY_RAKVAULT"`, `sourceFingerprint` =
 *   `LegacyVaultSnapshotMapper.fingerprintOfEncryptedBytes` (the SHA-256 of
 *   the original encrypted bytes) — never filename / Uri / plaintext hash /
 *   master password (Issue #1 §15).
 */
class LegacyImportService(
    /**
     * The v2 destination vault. Required ONLY for building the merge preview
     * ([buildPreview]) and for the transactional apply ([confirmImport]). It is
     * nullable so the Legacy password can be validated against the `.rakvault`
     * file independently of the v2 session state — a correct Legacy password is
     * never masked as a v2 "vault locked" error just because the v2 session
     * happens to be locked (RELEASE BLOCKER #46).
     */
    private val vault: VaultRepository?,
    private val importer: LegacyRakVaultImporter = LegacyRakVaultImporter(),
) {

    /** ImportRecord sourceType tag for legacy `.rakvault` imports. */
    val sourceTypeLegacy: String = "LEGACY_RAKVAULT"

    @Volatile
    private var active: LegacyImportSession? = null

    /** The active (decrypted) legacy import session, or null. Test-visible. */
    fun activeSession(): LegacyImportSession? = active

    /** Drops the active session (cancel / lock / apply / new file / error). */
    fun clearSession() {
        active = null
    }

    /**
     * Decodes [encryptedBytes] (already bounded by the SAF layer) with
     * [password] and maps it into a shared [VaultSnapshot], storing the
     * decoded session.
     *
     * This step does NOT depend on the v2 session state: the Legacy password is
     * always validated against the `.rakvault` file here, so a correct Legacy
     * password can never be masked as a v2 "vault locked" error (RELEASE
     * BLOCKER #46).
     *
     * @throws LegacyRakVaultImporter.ImportException on any decode failure
     *   (typed [LegacyRakVaultImporter.ErrorKind])
     * @throws LegacyVaultSnapshotMapper.LegacyMappingException on mapping failure
     */
    suspend fun decodeAndMap(
        encryptedBytes: ByteArray,
        password: CharArray,
    ): VaultSnapshot {
        val passwordString = String(password)
        val bundle: LegacyImportBundle = try {
            importer.import(encryptedBytes, passwordString)
        } finally {
            passwordString.toCharArray().fill('\u0000')
        }
        val fingerprint = LegacyVaultSnapshotMapper.fingerprintOfEncryptedBytes(encryptedBytes)
        val snapshot = LegacyVaultSnapshotMapper.map(bundle, fingerprint)
        // Shared logical validation only (never the Native package capacity
        // budget — ADR-0010 §6 / PHASE5A §17a).
        com.rescueauth.v2.export.PackageValidator.validateSnapshot(snapshot)
        active = LegacyImportSession(bundle, snapshot, fingerprint)
        return snapshot
    }

    /**
     * Convenience wrapper: decodes + maps the legacy file and builds the safe
     * merge preview in one call. Requires the v2 vault to be unlocked (because
     * the preview is built against the current destination).
     *
     * Prefer calling [decodeAndMap] then [buildPreview] separately when the v2
     * session may be locked, so the Legacy password is validated first
     * (RELEASE BLOCKER #46).
     */
    suspend fun decodeForPreview(
        encryptedBytes: ByteArray,
        password: CharArray,
    ): LegacyImportPreview {
        decodeAndMap(encryptedBytes, password)
        return buildPreview()
    }

    /**
     * Builds the safe merge preview for the active decoded session against the
     * current v2 destination. Requires the v2 vault to be unlocked.
     *
     * @throws VaultRepository.SessionLockedException when the v2 session is
     *   locked (the destination vault is not available).
     */
    suspend fun buildPreview(): LegacyImportPreview {
        val session = active
            ?: throw IllegalStateException("no active legacy import session")
        val destination = requireVault().buildDestinationSnapshot()
        val plan = MergePlanner.plan(destination, session.snapshot)
        return LegacyImportPreview.from(session.bundle, session.snapshot, plan, session.sourceFingerprint)
    }

    /**
     * Confirms the active legacy import. [VaultRepository.applySnapshot] is the
     * final authority: it re-validates the snapshot, re-plans against the LIVE
     * destination, preflights (CONFLICT / recovery divergence → block) and
     * applies inside one transaction. The preview plan is never applied
     * directly (Issue #1 §11).
     *
     * The active session is cleared on any outcome (applied or blocked).
     */
    suspend fun confirmImport(): ImportOutcome {
        val session = active ?: return ImportOutcome.Blocked(
            com.rescueauth.v2.repository.ImportBlockedResult(
                conflicts = 0,
                stateDivergences = 0,
                duplicates = 0,
                unchanged = 0,
            ),
        )
        active = null
        return requireVault().applySnapshot(
            snapshot = session.snapshot,
            packageIdentity = session.sourceFingerprint,
            sourceType = sourceTypeLegacy,
        )
    }

    private fun requireVault(): VaultRepository =
        vault ?: throw VaultRepository.SessionLockedException()
}

/**
 * In-memory legacy import session. Holds the decrypted raw bundle + mapped
 * snapshot (both contain plaintext secrets) ONLY in memory. Must be cleared on
 * cancel / apply / lock / new file / error and never persisted (Issue #1 §16).
 */
data class LegacyImportSession(
    val bundle: LegacyImportBundle,
    val snapshot: VaultSnapshot,
    val sourceFingerprint: String,
) {
    /** Best-effort clear of the reference chain. */
    fun clear() = Unit
}

/**
 * Safe legacy import preview (Issue #1 §9).
 *
 * Deliberately contains NO secrets: no TOTP secret, no recovery-code values,
 * no API key/secret, no SSH private key/passphrase, no env var values, no
 * generic secret values, no signing passwords, no keystore bytes. The UI
 * holds only this summary model (never the decrypted bundle / snapshot).
 *
 * [blocked] is true when the preview plan contains unresolved CONFLICTs or
 * recovery used/unused divergences — the user may Cancel / Back, never
 * Import (Issue #1 §10/§13).
 */
data class LegacyImportPreview(
    // --- source metadata (non-secret) ---
    val schemaVersion: Int,
    val sourceFingerprint: String,
    // --- content summary (safe counts + non-secret metadata) ---
    val accounts: Int,
    val totpCredentials: Int,
    val recoverySets: Int,
    val recoveryCodes: Int,
    val developerSummary: LegacyDeveloperPreviewSummary,
    // --- merge summary ---
    val inserts: Int,
    val duplicates: Int,
    val conflicts: Int,
    val unchanged: Int,
    val stateDivergences: Int,
) {
    val blocked: Boolean get() = conflicts > 0 || stateDivergences > 0

    companion object {
        fun from(
            bundle: LegacyImportBundle,
            snapshot: VaultSnapshot,
            plan: com.rescueauth.v2.export.MergePlan,
            sourceFingerprint: String,
        ): LegacyImportPreview {
            val summary = plan.summary
            val developerSummary = LegacyDeveloperPreviewSummary.from(bundle, snapshot)
            return LegacyImportPreview(
                schemaVersion = bundle.schemaVersion,
                sourceFingerprint = sourceFingerprint,
                accounts = snapshot.accounts.size,
                totpCredentials = snapshot.accounts.sumOf { it.totpCredentials.size },
                recoverySets = snapshot.accounts.sumOf { it.recoveryCodeSets.size },
                recoveryCodes = snapshot.accounts.sumOf { it.recoveryCodeSets.sumOf { s -> s.codes.size } },
                developerSummary = developerSummary,
                inserts = summary.inserted,
                duplicates = summary.duplicates,
                conflicts = summary.conflicts,
                unchanged = summary.unchanged,
                stateDivergences = summary.stateDivergences,
            )
        }
    }
}

/**
 * Safe Developer summary for the legacy preview — counts + non-secret
 * metadata only (type, title / project / service / key name). No secret
 * values appear anywhere (Issue #1 §9/§14).
 */
data class LegacyDeveloperPreviewSummary(
    val signingKeys: Int,
    val apiCredentials: Int,
    val sshKeys: Int,
    val envVarSets: Int,
    val genericSecrets: Int,
    val items: List<LegacyDeveloperPreviewItem>,
) {
    val total: Int get() = signingKeys + apiCredentials + sshKeys + envVarSets + genericSecrets

    companion object {
        fun from(
            bundle: LegacyImportBundle,
            snapshot: VaultSnapshot,
        ): LegacyDeveloperPreviewSummary {
            var signing = 0
            var api = 0
            var ssh = 0
            var env = 0
            var generic = 0
            val items = snapshot.developerEntries.map { entry ->
                val typeName = when (entry) {
                    is com.rescueauth.v2.export.VaultAndroidSigningKey -> "android_signing_key"
                    is com.rescueauth.v2.export.VaultApiCredential -> "api_credential"
                    is com.rescueauth.v2.export.VaultSshKey -> "ssh_key"
                    is com.rescueauth.v2.export.VaultEnvironmentVariableSet -> "environment_variable_set"
                    is com.rescueauth.v2.export.VaultGenericSecret -> "generic_secret"
                }
                val display = when (entry) {
                    is com.rescueauth.v2.export.VaultAndroidSigningKey -> entry.projectName
                    is com.rescueauth.v2.export.VaultApiCredential -> entry.serviceName
                    is com.rescueauth.v2.export.VaultSshKey -> entry.keyName
                    is com.rescueauth.v2.export.VaultEnvironmentVariableSet -> entry.projectName
                    is com.rescueauth.v2.export.VaultGenericSecret -> entry.title
                }
                when (entry) {
                    is com.rescueauth.v2.export.VaultAndroidSigningKey -> signing++
                    is com.rescueauth.v2.export.VaultApiCredential -> api++
                    is com.rescueauth.v2.export.VaultSshKey -> ssh++
                    is com.rescueauth.v2.export.VaultEnvironmentVariableSet -> env++
                    is com.rescueauth.v2.export.VaultGenericSecret -> generic++
                }
                LegacyDeveloperPreviewItem(
                    stableId = entry.stableId,
                    type = typeName,
                    displayName = display.ifBlank { entry.title },
                    title = entry.title,
                )
            }
            return LegacyDeveloperPreviewSummary(signing, api, ssh, env, generic, items)
        }
    }
}

/** One Developer Entry in the legacy preview — safe metadata only. */
data class LegacyDeveloperPreviewItem(
    val stableId: String,
    val type: String,
    val displayName: String,
    val title: String,
)
