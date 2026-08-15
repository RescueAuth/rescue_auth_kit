package com.rescueauth.v2.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ImportRecordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: ImportRecordEntity)

    @Query("SELECT * FROM import_record WHERE sourceFingerprint = :fingerprint ORDER BY importedAt DESC LIMIT 1")
    suspend fun findLatestByFingerprint(fingerprint: String): ImportRecordEntity?

    @Query("SELECT * FROM import_record ORDER BY importedAt DESC")
    suspend fun listAll(): List<ImportRecordEntity>
}
