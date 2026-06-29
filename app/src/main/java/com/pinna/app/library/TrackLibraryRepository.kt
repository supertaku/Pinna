package com.pinna.app.library

import com.pinna.app.core.model.Track

interface TrackLibraryRepository {
    suspend fun loadTracks(): List<Track>
    suspend fun saveTrack(track: Track, nowEpochMillis: Long = System.currentTimeMillis()): Track
    suspend fun deleteTrack(id: String): Boolean
}
