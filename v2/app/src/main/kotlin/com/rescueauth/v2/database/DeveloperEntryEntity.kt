package com.rescueauth.v2.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Developer Vault entry (schema v3, Phase 3C).
 *
 * This is a SINGLE-table, type-tagged persistence model for the five formal
 * Developer Entry types (ROADMAP §6 / PRODUCT.md §Developer 数据策略):
 *
 * - ANDROID_SIGNING_KEY
 * - API_CREDENTIAL
 * - SSH_KEY
 * - ENVIRONMENT_VARIABLE_SET
 * - GENERIC_SECRET
 *
 * ## Design: one table + typed payload (option A)
 *
 * The portable logical model ([VaultDeveloperEntry]) is a sealed hierarchy
 * with only ~3-6 type-specific fields per entry. A base table + per-type
 * tables (option B) would need five extra FKs, five DAOs and five
 * mapper/transaction passes for zero data-model benefit; every import is a
 * full-snapshot merge and every entry already shares the common surface
 * ([stableId] / [title] / [notes] / [createdAt] / [updatedAt]).
 *
 * We therefore persist the type-specific payload as one JSON blob
 * ([payloadJson]). The blob is produced exclusively by the Phase 3C mapper
 * from the validated portable logical model; it is never shown to the UI and
 * never parsed for merge decisions (the shared MergePlanner works on
 * [VaultDeveloperEntry] objects, not on this table). No Room entity ever
 * leaks into the shared package/merge core — the mapper is the only bridge
 * (ROADMAP §9 / AGENTS 架构边界 3).
 *
 * ## Security
 *
 * - All secret fields (storePassword / keyPassword / keystoreBase64 /
 *   apiKey / apiSecret / privateKey / passphrase / env values / generic
 *   values) live ONLY inside the SQLCipher-encrypted database — the whole
 *   database file is encrypted (Phase 2 VaultKey model).
 * - The keystore binary is stored as its original base64 in [payloadJson]
 *   (exact byte round-trip). No separate plaintext sidecar file is ever
 *   created; nothing here writes logs or debug output.
 * - The `entryType` column is a plain enum name (non-sensitive).
 */
@Entity(
    tableName = "developer_entry",
    indices = [
        Index(value = ["stableId"], unique = true),
        Index("entryType"),
    ],
)
data class DeveloperEntryEntity(
    @PrimaryKey val id: String,
    /** Stable logical record ID — survives export/import across devices. */
    val stableId: String,
    /** ANDROID_SIGNING_KEY | API_CREDENTIAL | SSH_KEY | ENVIRONMENT_VARIABLE_SET | GENERIC_SECRET */
    val entryType: String,
    val title: String,
    val notes: String? = null,
    /**
     * JSON encoding of the type-specific logical payload (all user-meaningful
     * fields incl. secrets). Only produced by the Phase 3C mapper from a
     * validated [VaultDeveloperEntry]; never consumed by the merge core.
     */
    val payloadJson: String,
    val createdAt: String,
    val updatedAt: String,
    /** DB insert ordering; stable for display while keeping payload stable. */
    val sortOrder: Long,
)
