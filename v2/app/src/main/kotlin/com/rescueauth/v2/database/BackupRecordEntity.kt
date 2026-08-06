package com.rescueauth.v2.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "backup_record",
    indices = [Index("createdAt")],
)
data class BackupRecordEntity(
    @PrimaryKey val id: String,
    val createdAt: String,
    /** MANUAL | CHANGE | DAILY | WEEKLY | MONTHLY | PRE_IMPORT */
    val reason: String,
    val uri: String,
    val sizeBytes: Long,
    val sha256: String,
    /** SUCCESS | FAILED */
    val status: String,
    val errorCode: String? = null,
)
