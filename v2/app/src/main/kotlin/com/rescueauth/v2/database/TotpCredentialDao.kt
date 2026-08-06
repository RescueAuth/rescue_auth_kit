package com.rescueauth.v2.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TotpCredentialDao {

    @Query("SELECT * FROM totp_credential WHERE accountId = :accountId ORDER BY createdAt")
    fun observeByAccount(accountId: String): Flow<List<TotpCredentialEntity>>

    @Query("SELECT * FROM totp_credential WHERE accountId = :accountId ORDER BY createdAt")
    suspend fun listByAccount(accountId: String): List<TotpCredentialEntity>

    @Query("SELECT * FROM totp_credential")
    suspend fun listAll(): List<TotpCredentialEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(credentials: List<TotpCredentialEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(credential: TotpCredentialEntity)

    @Query("DELETE FROM totp_credential WHERE id = :id")
    suspend fun deleteById(id: String)
}
