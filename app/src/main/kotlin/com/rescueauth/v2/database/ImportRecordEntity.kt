package com.rescueauth.v2.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "import_record",
    indices = [Index("sourceFingerprint"), Index(value = ["stableId"], unique = true)],
)
data class ImportRecordEntity(
    @PrimaryKey val id: String,
    /** LEGACY_RAKVAULT | V2_PACKAGE | OTPAUTH | QR */
    val sourceType: String,
    val sourceFingerprint: String,
    val importedAt: String,
    val itemCount: Int,
    val warningCount: Int,
    /** Stable logical record ID — survives export/import across devices (Phase 3A).
     *  Backfilled to the Room `id` for pre-Phase-3A rows. */
    val stableId: String = id,
)
