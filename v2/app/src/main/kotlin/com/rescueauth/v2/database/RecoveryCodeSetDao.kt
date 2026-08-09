package com.rescueauth.v2.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RecoveryCodeSetDao {

    @Query("SELECT * FROM recovery_code_set WHERE accountId = :accountId ORDER BY createdAt")
    fun observeByAccount(accountId: String): Flow<List<RecoveryCodeSetEntity>>

    @Query("SELECT * FROM recovery_code_set ORDER BY accountId, createdAt")
    fun observeAll(): Flow<List<RecoveryCodeSetEntity>>

    @Query("SELECT * FROM recovery_code_set WHERE accountId = :accountId ORDER BY createdAt")
    suspend fun listByAccount(accountId: String): List<RecoveryCodeSetEntity>

    @Query("SELECT * FROM recovery_code_set WHERE id = :id")
    suspend fun getById(id: String): RecoveryCodeSetEntity?

    @Query("UPDATE recovery_code_set SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: String, title: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(sets: List<RecoveryCodeSetEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(set: RecoveryCodeSetEntity)

    @Query("DELETE FROM recovery_code_set WHERE id = :id")
    suspend fun deleteById(id: String)
}
