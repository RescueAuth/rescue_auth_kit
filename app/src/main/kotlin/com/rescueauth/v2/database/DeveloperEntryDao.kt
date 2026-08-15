package com.rescueauth.v2.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DeveloperEntryDao {

    @Query("SELECT * FROM developer_entry ORDER BY sortOrder")
    fun observeAll(): Flow<List<DeveloperEntryEntity>>

    @Query("SELECT * FROM developer_entry ORDER BY sortOrder")
    suspend fun listAll(): List<DeveloperEntryEntity>

    @Query("SELECT * FROM developer_entry WHERE stableId = :stableId LIMIT 1")
    suspend fun getByStableId(stableId: String): DeveloperEntryEntity?

    @Query("SELECT * FROM developer_entry WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): DeveloperEntryEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(entries: List<DeveloperEntryEntity>)

    /** Insert or replace the row whose primary key (`id` = stableId) matches. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: DeveloperEntryEntity)

    @Query("DELETE FROM developer_entry WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM developer_entry")
    suspend fun count(): Int

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM developer_entry")
    suspend fun maxSortOrder(): Long
}
