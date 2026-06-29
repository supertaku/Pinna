package com.pinna.app.library

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY createdAtEpochMillis DESC")
    suspend fun getAll(): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): TrackEntity?

    @Upsert
    suspend fun upsert(entity: TrackEntity)

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun deleteById(id: String)
}
