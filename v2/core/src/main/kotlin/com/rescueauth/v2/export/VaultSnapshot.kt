package com.rescueauth.v2.export

import kotlinx.serialization.Serializable

/**
 * Logical snapshot of a v2 vault (Phase 3A).
 *
 * This is the pure, database-agnostic representation of the user vault data
 * that an Export Package carries. It deliberately contains NO Android / Room
 * objects:
 *
 * - Phase 3B encrypts a [VaultPackagePayload] (metadata + snapshot) into the
 *   portable package bytes.
 * - Phase 3C maps a decrypted snapshot onto the Room entities inside a single
 *   transaction, using the Merge Planner.
 * - Legacy import (Phase 0/1) already produces its own
 *   `LegacyImportBundle` → mapper → Room; the new foundation is what a future
 *   legacy importer integration will funnel through (see docs/LEGACY_IMPORT.md
 *   and docs/PACKAGE_FORMAT.md §Legacy).
 *
 * ## Identity model (see docs/PACKAGE_FORMAT.md §Identity)
 *
 * Every record carries a [stableId] — a stable logical record ID that is
 * independent of the Room primary key:
 *
 * - The Room primary key is a per-install random UUID and is NOT stable across
 *   devices (two phones that independently scan the same TOTP QR get
 *   different Room primary keys).
 * - [stableId] is assigned when the logical record is first created and is
 *   preserved through Export → Import → Export (it is carried inside the
 *   package payload).
 * - Two devices that independently scanned the SAME TOTP have DIFFERENT
 *   stableIds; they are recognised as the same credential by the semantic
 *   fingerprint instead (see [Canonicalization]).
 */
@Serializable
data class VaultSnapshot(
    val accounts: List<VaultAccount> = emptyList(),
)

@Serializable
data class VaultAccount(
    val stableId: String,
    val serviceName: String,
    val accountName: String,
    val favorite: Boolean = false,
    val notes: String? = null,
    val sortOrder: Long,
    val createdAt: String,
    val updatedAt: String,
    val totpCredentials: List<VaultTotpCredential> = emptyList(),
    val recoveryCodeSets: List<VaultRecoveryCodeSet> = emptyList(),
)

@Serializable
data class VaultTotpCredential(
    val stableId: String,
    val secretBase32: String,
    val algorithm: String,
    val digits: Int,
    val periodSeconds: Int,
    val createdAt: String,
)

@Serializable
data class VaultRecoveryCodeSet(
    val stableId: String,
    val title: String,
    val createdAt: String,
    val codes: List<VaultRecoveryCode> = emptyList(),
)

@Serializable
data class VaultRecoveryCode(
    val stableId: String,
    val value: String,
    /** UNUSED | USED */
    val status: String,
    val usedAt: String? = null,
    val sortOrder: Int,
)
