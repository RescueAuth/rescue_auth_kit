package com.rescueauth.v2.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AuthAccountDao {

    @Query("SELECT * FROM auth_account ORDER BY sortOrder")
    fun observeAll(): Flow<List<AuthAccountEntity>>

    @Query("SELECT * FROM auth_account ORDER BY sortOrder")
    suspend fun listAll(): List<AuthAccountEntity>

    @Query("SELECT * FROM auth_account WHERE id = :id")
    suspend fun getById(id: String): AuthAccountEntity?

    @Query("SELECT * FROM auth_account WHERE stableId = :stableId")
    suspend fun getByStableId(stableId: String): AuthAccountEntity?

    @Query("SELECT * FROM auth_account WHERE serviceName = :serviceName AND accountName = :accountName LIMIT 1")
    suspend fun findByServiceAndAccount(serviceName: String, accountName: String): AuthAccountEntity?

    @Query("SELECT * FROM auth_account WHERE serviceName = :serviceName ORDER BY sortOrder")
    fun observeByService(serviceName: String): Flow<List<AuthAccountEntity>>

    @Query("SELECT * FROM auth_account WHERE serviceName = :serviceName ORDER BY sortOrder")
    suspend fun listByServiceName(serviceName: String): List<AuthAccountEntity>

    @Query("SELECT COUNT(*) FROM auth_account WHERE serviceName = :serviceName")
    suspend fun countByServiceName(serviceName: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(accounts: List<AuthAccountEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: AuthAccountEntity)

    @Query("UPDATE auth_account SET favorite = :favorite, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean, updatedAt: String)

    @Query("UPDATE auth_account SET accountName = :accountName, serviceName = :serviceName, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateName(id: String, accountName: String, serviceName: String, updatedAt: String)

    @Query("UPDATE auth_account SET serviceName = :serviceName, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateServiceName(id: String, serviceName: String, updatedAt: String)

    @Query("UPDATE auth_account SET serviceName = :newServiceName, updatedAt = :updatedAt WHERE serviceName = :oldServiceName")
    suspend fun updateServiceNameForAll(oldServiceName: String, newServiceName: String, updatedAt: String)

    @Query("UPDATE auth_account SET accountName = :accountName, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateAccountName(id: String, accountName: String, updatedAt: String)

    @Query("DELETE FROM auth_account WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM auth_account")
    suspend fun count(): Int

    @Query("SELECT id FROM auth_account ORDER BY sortOrder")
    suspend fun listAllIds(): List<String>

    @Query("SELECT id, stableId FROM auth_account")
    suspend fun listIdAndStableId(): List<IdStableIdRow>

    /** Lightweight row projection used by the merge applicator parent mapping. */
    data class IdStableIdRow(val id: String, val stableId: String)
}
