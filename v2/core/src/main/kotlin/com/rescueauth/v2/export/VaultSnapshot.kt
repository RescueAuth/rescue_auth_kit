package com.rescueauth.v2.export

import kotlinx.serialization.SerialName
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
 *
 * ## Vault coverage — Developer Vault is a first-class asset
 *
 * The snapshot carries BOTH the Authenticator section ([accounts]) and the
 * Developer Vault section ([developerEntries]). The five Developer Entry
 * types (Android Signing Key / API Credential / SSH Key / Environment
 * Variable Set / Generic Secret) are formal v2 assets and MUST be expressible
 * by the portable logical schema NOW (ROADMAP §8.2 / §17) — the logical
 * model is not TOTP + Recovery Codes only.
 *
 * ## Selective snapshots ([scope])
 *
 * A snapshot is NOT required to be a complete vault. [scope] declares which
 * sections it carries:
 *
 * - [SnapshotScope.FULL_VAULT] — the entire vault;
 * - [SnapshotScope.AUTHENTICATOR_ONLY] — Authenticator section only;
 * - [SnapshotScope.DEVELOPER_ONLY] — Developer Vault section only;
 * - [SnapshotScope.SELECTED_ITEMS] — an arbitrary subset of items.
 *
 * [PackageValidator] enforces that the declared scope is consistent with the
 * actual content, so partial / selective packages are first-class citizens
 * (ROADMAP §8.4 / §17) and the import path stays on one Merge Engine.
 *
 * ## Android Signing Key binary keystore
 *
 * [VaultAndroidSigningKey.keystoreBase64] carries the raw keystore bytes in
 * base64 (a binary asset, see ROADMAP §6/§8.2). The keystore is secret
 * material and must NEVER be exposed in a plaintext package header — the
 * whole [VaultPackagePayload] (including the keystore) lives inside the
 * Phase 3B AEAD-encrypted payload.
 */
@Serializable
data class VaultSnapshot(
    val accounts: List<VaultAccount> = emptyList(),
    val developerEntries: List<VaultDeveloperEntry> = emptyList(),
    val scope: SnapshotScope = SnapshotScope.FULL_VAULT,
)

/** Declares which sections of the vault a [VaultSnapshot] carries. */
@Serializable
enum class SnapshotScope {
    /** The package carries the entire vault (all sections). */
    FULL_VAULT,

    /** Authenticator section only (Provider/Account/TOTP/Recovery Codes). */
    AUTHENTICATOR_ONLY,

    /** Developer Vault section only (the five Developer Entry types). */
    DEVELOPER_ONLY,

    /** An arbitrary subset of items selected by the user (Selective Export). */
    SELECTED_ITEMS,
}

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

// ---------------------------------------------------------------------------
// Developer Vault — five formal Developer Entry types (ROADMAP §6, all KEEP)
// ---------------------------------------------------------------------------

/**
 * A Developer Vault entry (formal v2 asset).
 *
 * Each subtype models one of the five Developer Entry types from
 * PRODUCT.md §Developer 数据策略 / ROADMAP §6. The common [stableId] / [title]
 * / [notes] / [createdAt] / [updatedAt] surface is shared by all five types.
 *
 * The merge semantics are deliberately conservative (ROADMAP §8.3):
 * - same stableId + same canonical sensitive payload → DUPLICATE;
 * - same stableId + different sensitive payload → CONFLICT;
 * - different stableId → INSERT / keep both (identical sensitive payloads do
 *   NOT prove the same logical asset; silently dropping one would lose
 *   service/account/project/keyName/label semantics).
 * Metadata (title / projectName / serviceName / keyName / notes) is NEVER
 * part of the semantic fingerprint — two entries that merely share a title
 * are never auto-deduplicated.
 */
@Serializable
sealed interface VaultDeveloperEntry {
    val stableId: String
    val title: String
    val notes: String?
    val createdAt: String
    val updatedAt: String
}

/** Android Signing Key (ROADMAP §6). */
@Serializable
@SerialName("android_signing_key")
data class VaultAndroidSigningKey(
    override val stableId: String,
    val projectName: String,
    val packageName: String,
    val keystoreFileName: String,
    /**
     * Binary keystore contents, base64-encoded (RFC 4648, no line breaks).
     *
     * SECURITY: the keystore is a binary secret asset. It must only ever be
     * carried inside the encrypted payload (Phase 3B AEAD) and must never be
     * placed in a plaintext package header (ROADMAP §8.2 / PACKAGE_FORMAT §Header).
     * [PackageValidator] enforces a base64 validity check + size upper bound.
     */
    val keystoreBase64: String,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
    override val title: String = "",
    override val notes: String? = null,
    override val createdAt: String,
    override val updatedAt: String,
) : VaultDeveloperEntry

/** API Credential (ROADMAP §6). */
@Serializable
@SerialName("api_credential")
data class VaultApiCredential(
    override val stableId: String,
    val serviceName: String,
    val accountName: String,
    val apiKey: String,
    val apiSecret: String,
    override val title: String = "",
    override val notes: String? = null,
    override val createdAt: String,
    override val updatedAt: String,
) : VaultDeveloperEntry

/** SSH Key (ROADMAP §6). */
@Serializable
@SerialName("ssh_key")
data class VaultSshKey(
    override val stableId: String,
    val keyName: String,
    val publicKey: String,
    val privateKey: String,
    val passphrase: String,
    override val title: String = "",
    override val notes: String? = null,
    override val createdAt: String,
    override val updatedAt: String,
) : VaultDeveloperEntry

/** Environment Variable Set (ROADMAP §6) — a list of KEY=VALUE pairs. */
@Serializable
@SerialName("environment_variable_set")
data class VaultEnvironmentVariableSet(
    override val stableId: String,
    val projectName: String,
    val variables: List<VaultKeyValue> = emptyList(),
    override val title: String = "",
    override val notes: String? = null,
    override val createdAt: String,
    override val updatedAt: String,
) : VaultDeveloperEntry

/** Generic Secret (ROADMAP §6) — arbitrary label=value fields. */
@Serializable
@SerialName("generic_secret")
data class VaultGenericSecret(
    override val stableId: String,
    val fields: List<VaultKeyValue> = emptyList(),
    override val title: String = "",
    override val notes: String? = null,
    override val createdAt: String,
    override val updatedAt: String,
) : VaultDeveloperEntry

/** One key/value pair used by Environment Variable Set and Generic Secret. */
@Serializable
data class VaultKeyValue(
    val key: String,
    val value: String,
)
