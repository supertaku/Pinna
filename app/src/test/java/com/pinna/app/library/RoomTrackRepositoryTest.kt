package com.pinna.app.library

import com.pinna.app.core.model.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomTrackRepositoryTest {
    private val track = Track(
        id = "track-1",
        title = "Track",
        artist = null,
        durationMs = 1_000,
        mimeType = "audio/mpeg",
        localUri = "C:/app/files/pinna-tracks/track-1.audio",
        sizeBytes = 100,
    )

    @Test
    fun saveTrackPersistsMetadataAndLoadTracksReturnsDomainTracks() = runBlocking {
        val dao = FakeTrackDao()
        val repository = RoomTrackRepository(dao = dao, fileExists = { true }, fileDeleter = { true })

        repository.saveTrack(track, nowEpochMillis = 1_000)
        val loaded = repository.loadTracks()

        assertEquals(listOf(track), loaded)
        assertEquals(1_000, dao.entities.single().createdAtEpochMillis)
    }

    @Test
    fun deleteTrackDeletesMetadataAndPrivateFile() = runBlocking {
        val deletedPaths = mutableListOf<String>()
        val dao = FakeTrackDao()
        val repository = RoomTrackRepository(dao = dao, fileDeleter = { path ->
            deletedPaths += path
            true
        })
        repository.saveTrack(track, nowEpochMillis = 1_000)

        val deleted = repository.deleteTrack("track-1")

        assertTrue(deleted)
        assertEquals(emptyList<TrackEntity>(), dao.entities)
        assertEquals(listOf(track.localUri), deletedPaths)
    }

    @Test
    fun deleteTrackReturnsFalseForMissingTrack() = runBlocking {
        val repository = RoomTrackRepository(dao = FakeTrackDao(), fileDeleter = { true })

        val deleted = repository.deleteTrack("missing")

        assertFalse(deleted)
    }

    @Test
    fun deleteTrackKeepsMetadataWhenPrivateFileDeleteFails() = runBlocking {
        val dao = FakeTrackDao()
        val repository = RoomTrackRepository(dao = dao, fileDeleter = { false })
        repository.saveTrack(track, nowEpochMillis = 1_000)

        val deleted = repository.deleteTrack("track-1")

        assertFalse(deleted)
        assertEquals(listOf(TrackEntity.fromTrack(track, createdAtEpochMillis = 1_000, lastPlayedAtEpochMillis = 1_000)), dao.entities)
    }

    @Test
    fun loadTracksPrunesMetadataWhenPrivateFileIsMissing() = runBlocking {
        val dao = FakeTrackDao()
        val repository = RoomTrackRepository(dao = dao, fileExists = { false }, fileDeleter = { true })
        repository.saveTrack(track, nowEpochMillis = 1_000)

        val loaded = repository.loadTracks()

        assertTrue(loaded.isEmpty())
        assertTrue(dao.entities.isEmpty())
    }
}

private class FakeTrackDao : TrackDao {
    val entities = mutableListOf<TrackEntity>()

    override suspend fun getAll(): List<TrackEntity> = entities.sortedByDescending { it.createdAtEpochMillis }

    override suspend fun findById(id: String): TrackEntity? = entities.firstOrNull { it.id == id }

    override suspend fun upsert(entity: TrackEntity) {
        entities.removeAll { it.id == entity.id }
        entities += entity
    }

    override suspend fun deleteById(id: String) {
        entities.removeAll { it.id == id }
    }
}
