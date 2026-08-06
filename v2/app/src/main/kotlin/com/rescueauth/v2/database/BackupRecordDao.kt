package com.rescueauth.v2.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BackupRecordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: BackupRecordEntity)

    @Query("SELECT * FROM backup_record ORDER BY createdAt DESC LIMIT 1")
    suspend fun latest(): BackupRecordEntity?

    @Query("SELECT * FROM backup_record WHERE status = 'SUCCESS' ORDER BY createdAt DESC LIMIT 1")
    suspend fun latestSuccess(): BackupRecordEntity?

    @Query("SELECT COUNT(*) FROM backup_record WHERE status = 'SUCCESS'")
    suspend fun successCount(): Int

    @Query("DELETE FROM backup_record WHERE id = :id")
    suspend fun deleteById(id: String)
}
