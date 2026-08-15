package com.rescueauth.v2.export

import kotlinx.serialization.Serializable

/**
 * Logical payload of a v2 Export Package (Phase 3A contract).
 *
 * This is what the Phase 3B codec will place inside the AEAD-encrypted
 * payload of the portable package file. It is a plain, versioned value object;
 * it contains NO encryption material, NO Export PIN, and NO per-export secrets.
 *
 * ## What lives here vs. what lives in the Phase 3B encryption envelope
 *
 * | Item | Location |
 * | --- | --- |
 * | logical schema version, source metadata, creation time | this payload |
 * | `VaultSnapshot` (accounts/credentials/recovery codes + Developer Entries + scope) | this payload |
 * | magic, `formatVersion`, `cryptoVersion`, KDF id/params, salt, wrapped PackageKey, AEAD nonce, ciphertext | Phase 3B envelope (plaintext header) |
 * | per-export Export PIN | NEVER stored — only used in memory to derive the wrapping key |
 * | Export PIN salt | Phase 3B header (random per export) |
 * | PackageKey | Phase 3B header, wrapped by the PIN-derived key |
 *
 * ## Schema version contract
 *
 * `logicalSchemaVersion` is the version of THIS payload structure. Readers
 * must refuse unknown newer versions instead of guessing (same policy as
 * BACKUP_FORMAT / PACKAGE_FORMAT §Versioning).
 */
@Serializable
data class VaultPackagePayload(
    val logicalSchemaVersion: Int,
    val packageId: String,
    val createdAt: String,
    val source: PackageSourceMetadata = PackageSourceMetadata(),
    val snapshot: VaultSnapshot,
) {
    companion object {
        const val CURRENT_LOGICAL_SCHEMA_VERSION = 1
    }
}

/**
 * Stable, non-sensitive metadata about the package origin.
 *
 * All sensitive business metadata (account list, issuer, account names,
 * source information) belongs INSIDE the encrypted payload — see
 * PACKAGE_FORMAT.md §Header. [PackageSourceMetadata] carries only
 * non-sensitive technical fields that are safe to authenticate but not
 * required to be secret.
 */
@Serializable
data class PackageSourceMetadata(
    /** e.g. "android-app" — filled by the exporting client (Phase 3D). */
    val client: String = "unknown",
    /** free-form app version that produced the package (informational). */
    val appVersion: String = "unknown",
    /** set to the stable vault identity if the exporting vault knows one. */
    val vaultInstanceId: String? = null,
)
