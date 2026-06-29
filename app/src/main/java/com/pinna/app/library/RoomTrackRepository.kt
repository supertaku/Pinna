package com.pinna.app.library

import com.pinna.app.core.model.Track
import java.io.File

class RoomTrackRepository(
    private val dao: TrackDao,
    private val fileDeleter: (String) -> Boolean = { path ->
        val file = File(path)
        !file.exists() || file.delete()
    },
) : TrackLibraryRepository {
    override suspend fun loadTracks(): List<Track> = dao.getAll().map { it.toTrack() }

    override suspend fun saveTrack(track: Track, nowEpochMillis: Long): Track {
        val existing = dao.findById(track.id)
        dao.upsert(
            TrackEntity.fromTrack(
                track = track,
                createdAtEpochMillis = existing?.createdAtEpochMillis ?: nowEpochMillis,
                lastPlayedAtEpochMillis = existing?.lastPlayedAtEpochMillis ?: nowEpochMillis,
            ),
        )
        return track
    }

    override suspend fun deleteTrack(id: String): Boolean {
        val existing = dao.findById(id) ?: return false
        if (!fileDeleter(existing.localPath)) return false
        dao.deleteById(id)
        return true
    }
}
