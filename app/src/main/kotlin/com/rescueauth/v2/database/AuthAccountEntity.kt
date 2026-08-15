package com.rescueauth.v2.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * v2 AuthAccount (database schema v1).
 *
 * Flat, UI-friendly model: serviceName is a grouping/filter field only —
 * entries with the same serviceName stay separate accounts (phase1-fix
 * contract). `legacySourceId` links back to the imported `.rakvault` item.
 */
@Entity(
    tableName = "auth_account",
    indices = [Index("serviceName"), Index("legacySourceId"), Index(value = ["stableId"], unique = true)],
)
data class AuthAccountEntity(
    @PrimaryKey val id: String,
    val serviceName: String,
    val accountName: String,
    val favorite: Boolean = false,
    val notes: String? = null,
    val sortOrder: Long,
    val createdAt: String,
    val updatedAt: String,
    val legacySourceId: String? = null,
    /** Stable logical record ID — survives export/import across devices (Phase 3A).
     *  Backfilled to the Room `id` for pre-Phase-3A rows. */
    val stableId: String = id,
)
