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
)

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

/** Pure UI model for a recovery-code set. */
data class RecoveryCodeSetUi(
    val id: String,
    val title: String,
    val usedCount: Int,
    val totalCount: Int,
    val codes: List<RecoveryCodeUi> = emptyList(),
)

/** Pure UI model for a single recovery code. */
data class RecoveryCodeUi(
    val id: String,
    val value: String,
    val isUsed: Boolean,
)
