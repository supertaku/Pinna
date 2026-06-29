package com.pinna.app.library

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PinnaDatabaseTest {
    private lateinit var database: PinnaDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PinnaDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun trackDaoInsertsListsAndDeletesTracks() = runBlocking {
        val entity = TrackEntity(
            id = "track-1",
            title = "Track",
            artist = null,
            durationMs = 1_000,
            mimeType = "audio/mpeg",
            localPath = "/data/user/0/com.pinna.app/files/pinna-tracks/track-1.audio",
            sizeBytes = 100,
            createdAtEpochMillis = 1_000,
            lastPlayedAtEpochMillis = 1_000,
        )

        database.trackDao().upsert(entity)
        assertEquals(listOf(entity), database.trackDao().getAll())

        database.trackDao().deleteById("track-1")
        assertEquals(emptyList<TrackEntity>(), database.trackDao().getAll())
    }
}
