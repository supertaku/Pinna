package com.pinna.app.library

import com.pinna.app.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TrackEntityTest {
    @Test
    fun mapsTrackToEntityAndBackWithoutChangingPublicTrackFields() {
        val track = Track(
            id = "track-1",
            title = "Song",
            artist = "Artist",
            durationMs = 123_000,
            mimeType = "audio/mpeg",
            localUri = "C:/app/files/pinna-tracks/track-1.audio",
            sizeBytes = 456,
        )

        val entity = TrackEntity.fromTrack(
            track = track,
            createdAtEpochMillis = 1_000,
            lastPlayedAtEpochMillis = 2_000,
        )

        assertEquals("track-1", entity.id)
        assertEquals("C:/app/files/pinna-tracks/track-1.audio", entity.localPath)
        assertEquals(1_000, entity.createdAtEpochMillis)
        assertEquals(2_000, entity.lastPlayedAtEpochMillis)
        assertEquals(track, entity.toTrack())
    }

    @Test
    fun entitySchemaDoesNotExposeRoomTokens() {
        val fieldNames = TrackEntity::class.java.declaredFields.map { it.name.lowercase() }

        assertFalse(fieldNames.any { it.contains("token") })
        assertFalse(fieldNames.any { it.contains("payload") })
    }
}
