package com.pinna.app.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.file.Files

class TemporaryRoomCacheTest {
    @Test
    fun leaveRoom_clearsRoomScopedCache() {
        val directory = Files.createTempDirectory("pinna-cache")
        val cache = TemporaryRoomCache(directory)

        cache.put("room-a", "track-1", ByteArray(4) { 1 }, nowEpochMillis = 1_000, ttlMillis = 60_000)
        cache.put("room-b", "track-1", ByteArray(4) { 2 }, nowEpochMillis = 1_000, ttlMillis = 60_000)

        cache.clearRoom("room-a")

        assertFalse(cache.exists("room-a", "track-1", nowEpochMillis = 1_001))
        assertEquals(4, cache.get("room-b", "track-1", nowEpochMillis = 1_001)!!.size)
    }

    @Test
    fun cacheEntryExpiresAfterTtl() {
        val directory = Files.createTempDirectory("pinna-cache")
        val cache = TemporaryRoomCache(directory)

        cache.put("room-a", "track-1", ByteArray(4) { 1 }, nowEpochMillis = 1_000, ttlMillis = 10)

        assertFalse(cache.exists("room-a", "track-1", nowEpochMillis = 1_011))
    }

    @Test
    fun idsThatSanitizeSimilarly_doNotCollide() {
        val directory = Files.createTempDirectory("pinna-cache")
        val cache = TemporaryRoomCache(directory)

        cache.put("room/a", "track:1", byteArrayOf(1), nowEpochMillis = 1_000, ttlMillis = 60_000)
        cache.put("rooma", "track1", byteArrayOf(2), nowEpochMillis = 1_000, ttlMillis = 60_000)

        assertEquals(1, cache.get("room/a", "track:1", nowEpochMillis = 1_001)!!.single().toInt())
        assertEquals(2, cache.get("rooma", "track1", nowEpochMillis = 1_001)!!.single().toInt())
    }
}
