package com.rescueauth.v2.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "recovery_code",
    foreignKeys = [
        ForeignKey(
            entity = RecoveryCodeSetEntity::class,
            parentColumns = ["id"],
            childColumns = ["setId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("setId"), Index(value = ["stableId"], unique = true)],
)
data class RecoveryCodeEntity(
    @PrimaryKey val id: String,
    val setId: String,
    val value: String,
    /** UNUSED | USED */
    val status: String,
    val usedAt: String? = null,
    val sortOrder: Int,
    /** Stable logical record ID — survives export/import across devices (Phase 3A).
     *  Backfilled to the Room `id` for pre-Phase-3A rows. */
    val stableId: String = id,
)
