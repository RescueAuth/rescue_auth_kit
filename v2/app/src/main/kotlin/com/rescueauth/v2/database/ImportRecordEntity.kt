package com.rescueauth.v2.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "import_record",
    indices = [Index("sourceFingerprint")],
)
data class ImportRecordEntity(
    @PrimaryKey val id: String,
    /** LEGACY_RAKVAULT | V2_BACKUP | OTPAUTH | QR */
    val sourceType: String,
    val sourceFingerprint: String,
    val importedAt: String,
    val itemCount: Int,
    val warningCount: Int,
)
