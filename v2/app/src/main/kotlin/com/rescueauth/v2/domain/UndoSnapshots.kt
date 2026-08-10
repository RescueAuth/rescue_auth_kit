package com.rescueauth.v2.domain

/**
 * In-memory Undo snapshots (Issue #20 P8).
 *
 * ## Security contract (P8 §5, §13)
 *
 * Account / Developer Undo payloads can carry real secrets (TOTP secret,
 * recovery-code plaintext, API key/secret, SSH private key/passphrase, env
 * values, generic values). These snapshots are therefore **in-memory only**:
 *
 * - they MUST NEVER enter `SavedStateHandle` / `rememberSaveable` / `Bundle` /
 *   DataStore / Room undo table / file / cache / clipboard / logs / analytics /
 *   navigation route;
 * - process death / Activity process recreation may lose the Undo opportunity —
 *   that is an accepted security trade-off (never persist a secret snapshot to
 *   restore a Snackbar Undo);
 * - on Vault session lock the pending snapshot is cleared and no Undo token is
 *   restored after unlock (P8 §6).
 *
 * These are plain value objects with no serialization annotations so they
 * cannot be accidentally persisted.
 */

/**
 * An exact, restorable snapshot of a deleted Account and its ENTIRE subtree
 * (TOTP credentials + Recovery Code Sets/Codes). Captured inside the delete
 * transaction so restore is a faithful, atomic rollback of the delete.
 */
data class DeletedAccountSnapshot(
    val account: AuthAccount,
    val totps: List<TotpCredential>,
    val recoverySets: List<RecoveryCodeSet>,
) {
    /** Safe display label (never includes TOTP secrets / recovery plaintext). */
    val safeLabel: String get() = "${account.serviceName} · ${account.accountName}"
}

/**
 * An exact, restorable snapshot of a deleted ordinary Developer Entry
 * (API Credential / SSH Key / Environment Variable Set / Generic Secret).
 *
 * The payload (which may carry long-lived secrets) is held in-memory only.
 * Android Signing Key is deliberately NOT an ordinary entry and has no P8 Undo
 * requirement (P8 §14) — it keeps destructive confirmation.
 */
data class DeletedDeveloperEntrySnapshot(
    val stableId: String,
    val type: DeveloperEntryType,
    val title: String,
    val notes: String?,
    val createdAt: String,
    val updatedAt: String,
    val sortOrder: Long,
    /** Typed opaque payload as produced by the repository (may be null when a
     *  non-ordinary entry is passed; ordinary entries always carry it). */
    val logicalPayload: com.rescueauth.v2.export.VaultDeveloperEntry?,
) {
    /** Safe display label (title only — never a secret value). */
    val safeLabel: String get() = title.ifBlank { type.name }
}

/**
 * Outcome of an Undo restore attempt.
 *
 * [Restored] means the object was re-inserted atomically with exact
 * stableIds/states. [Blocked] means the destination changed in a way that
 * makes an exact restore unsafe — nothing was written and the pending secret
 * snapshot must be cleared (P8 §10). Never source-wins overwrite.
 */
sealed interface UndoRestoreOutcome {
    data object Restored : UndoRestoreOutcome
    /** The vault changed since the delete; exact restore is no longer safe. */
    data object Blocked : UndoRestoreOutcome
}
