package com.pinna.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaRangeTest {
    @Test
    fun parsesBoundedHttpRange() {
        val range = MediaRange.parse("bytes=100-199", totalSizeBytes = 1_000)

        assertEquals(MediaRange(startInclusive = 100, endInclusive = 199, totalSizeBytes = 1_000), range)
    }

    @Test
    fun openEndedRangeStopsAtEndOfFile() {
        val range = MediaRange.parse("bytes=900-", totalSizeBytes = 1_000)

        assertEquals(MediaRange(startInclusive = 900, endInclusive = 999, totalSizeBytes = 1_000), range)
    }

    @Test
    fun invalidRangeReturnsNull() {
        assertNull(MediaRange.parse("bytes=200-100", totalSizeBytes = 1_000))
        assertNull(MediaRange.parse("items=0-10", totalSizeBytes = 1_000))
        assertNull(MediaRange.parse("bytes=100-200", totalSizeBytes = 150))
    }
}
