package com.rescueauth.v2.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DeveloperEntryDao {

    @Query("SELECT * FROM developer_entry ORDER BY sortOrder")
    suspend fun listAll(): List<DeveloperEntryEntity>

    @Query("SELECT * FROM developer_entry WHERE stableId = :stableId LIMIT 1")
    suspend fun getByStableId(stableId: String): DeveloperEntryEntity?

    @Query("SELECT * FROM developer_entry WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): DeveloperEntryEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(entries: List<DeveloperEntryEntity>)

    @Query("SELECT COUNT(*) FROM developer_entry")
    suspend fun count(): Int
}
