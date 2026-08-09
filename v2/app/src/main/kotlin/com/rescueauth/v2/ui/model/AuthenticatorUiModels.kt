package com.rescueauth.v2.ui.model

/**
 * Pure UI model for an Authenticator provider (service) — **not** a Room
 * entity and **not** a package domain record. Kept deliberately small and
 * persistence-agnostic so UI components only depend on primitives.
 *
 * Future slices (Phase 4 P1/P3) will map domain records into this model.
 */
data class ProviderUi(
    val id: String,
    val serviceName: String,
    val accounts: List<AccountUi> = emptyList(),
)

/**
 * Pure UI model for an Authenticator account.
 *
 * - [providerName] is denormalised for display convenience.
 * - [totpCredentials] / [recoverySets] are UI-level lists (not domain
 *   snapshot types).
 */
data class AccountUi(
    val id: String,
    val providerName: String,
    val accountName: String,
    val isPinned: Boolean = false,
    val totpCredentials: List<TotpCredentialUi> = emptyList(),
    val recoverySets: List<RecoveryCodeSetUi> = emptyList(),
) {
    val remainingRecoveryCount: Int get() = recoverySets.sumOf { it.remainingCount }
    val totalRecoveryCount: Int get() = recoverySets.sumOf { it.totalCount }
}

/**
 * Pure UI model for a TOTP credential. Contains display metadata only — the
 * Base32 secret is deliberately **not** present in this model (the production
 * code-generation path will belong to a later slice and must not flow through
 * the UI as plaintext unless explicitly revealed).
 */
data class TotpCredentialUi(
    val id: String,
    val issuer: String,
    val accountName: String,
    val digits: Int = 6,
    val periodSeconds: Int = 30,
    val currentCode: String? = null,
)

/**
 * Pure UI model for a recovery-code set.
 *
 * The code values are secret-like data: this model is produced by the
 * ViewModel from the real repository and never appears in logs / previews with
 * real credentials. `remainingCount` drives the collapsed card subtitle;
 * `usedAt` is surfaced so the user can see when a code was consumed.
 */
data class RecoveryCodeSetUi(
    val id: String,
    val title: String,
    val usedCount: Int,
    val totalCount: Int,
    val codes: List<RecoveryCodeUi> = emptyList(),
) {
    val remainingCount: Int get() = totalCount - usedCount
}

/** Pure UI model for a single recovery code. */
data class RecoveryCodeUi(
    val id: String,
    val value: String,
    val isUsed: Boolean,
    val usedAt: String? = null,
)
