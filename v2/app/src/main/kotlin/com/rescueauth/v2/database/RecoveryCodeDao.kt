package com.rescueauth.v2.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RecoveryCodeDao {

    @Query("SELECT * FROM recovery_code WHERE setId = :setId ORDER BY sortOrder")
    suspend fun listBySet(setId: String): List<RecoveryCodeEntity>

    @Query("SELECT * FROM recovery_code WHERE setId = :setId ORDER BY sortOrder")
    fun observeBySet(setId: String): Flow<List<RecoveryCodeEntity>>

    @Query("SELECT * FROM recovery_code ORDER BY setId, sortOrder")
    fun observeAll(): Flow<List<RecoveryCodeEntity>>

    @Query("SELECT * FROM recovery_code WHERE id = :id")
    suspend fun getById(id: String): RecoveryCodeEntity?

    @Query("SELECT * FROM recovery_code WHERE stableId = :stableId")
    suspend fun getByStableId(stableId: String): RecoveryCodeEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(codes: List<RecoveryCodeEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(code: RecoveryCodeEntity)

    @Query("DELETE FROM recovery_code WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE recovery_code SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: String, sortOrder: Int)

    @Query("UPDATE recovery_code SET status = 'USED', usedAt = :usedAt WHERE id = :id")
    suspend fun markUsed(id: String, usedAt: String)

    @Query("UPDATE recovery_code SET status = 'UNUSED', usedAt = NULL WHERE id = :id")
    suspend fun markUnused(id: String)
}
