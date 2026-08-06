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
    indices = [Index("setId")],
)
data class RecoveryCodeEntity(
    @PrimaryKey val id: String,
    val setId: String,
    val value: String,
    /** UNUSED | USED */
    val status: String,
    val usedAt: String? = null,
    val sortOrder: Int,
)
