package com.rescueauth.v2.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RecoveryCodeDao {

    @Query("SELECT * FROM recovery_code WHERE setId = :setId ORDER BY sortOrder")
    suspend fun listBySet(setId: String): List<RecoveryCodeEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(codes: List<RecoveryCodeEntity>)

    @Query("UPDATE recovery_code SET status = 'USED', usedAt = :usedAt WHERE id = :id")
    suspend fun markUsed(id: String, usedAt: String)

    @Query("UPDATE recovery_code SET status = 'UNUSED', usedAt = NULL WHERE id = :id")
    suspend fun markUnused(id: String)
}
