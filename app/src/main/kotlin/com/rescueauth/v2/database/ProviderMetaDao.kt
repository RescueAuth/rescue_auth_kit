package com.rescueauth.v2.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProviderMetaDao {

    @Query("SELECT * FROM provider_meta")
    fun observeAll(): Flow<List<ProviderMetaEntity>>

    @Query("SELECT * FROM provider_meta")
    suspend fun listAll(): List<ProviderMetaEntity>

    @Query("SELECT * FROM provider_meta WHERE providerName = :providerName")
    suspend fun get(providerName: String): ProviderMetaEntity?

    @Upsert
    suspend fun upsert(entity: ProviderMetaEntity)

    @Query("DELETE FROM provider_meta WHERE providerName = :providerName")
    suspend fun delete(providerName: String)

    @Query("UPDATE provider_meta SET providerName = :newName WHERE providerName = :oldName")
    suspend fun rename(oldName: String, newName: String)

    @Query("DELETE FROM provider_meta")
    suspend fun deleteAll()
}
