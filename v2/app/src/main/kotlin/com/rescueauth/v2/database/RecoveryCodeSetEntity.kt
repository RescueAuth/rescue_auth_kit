package com.rescueauth.v2.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "recovery_code_set",
    foreignKeys = [
        ForeignKey(
            entity = AuthAccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("accountId"), Index("legacySourceId"), Index(value = ["stableId"], unique = true)],
)
data class RecoveryCodeSetEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val title: String,
    val createdAt: String,
    val legacySourceId: String? = null,
    /** Stable logical record ID — survives export/import across devices (Phase 3A).
     *  Backfilled to the Room `id` for pre-Phase-3A rows. */
    val stableId: String = id,
)
