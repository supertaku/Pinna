package com.pinna.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectBackoffTest {
    @Test
    fun exponentialBackoffWithCap() {
        assertEquals(500, ReconnectBackoff.delayMs(1, baseMs = 500, maxMs = 8_000))
        assertEquals(1_000, ReconnectBackoff.delayMs(2, baseMs = 500, maxMs = 8_000))
        assertEquals(2_000, ReconnectBackoff.delayMs(3, baseMs = 500, maxMs = 8_000))
        assertEquals(4_000, ReconnectBackoff.delayMs(4, baseMs = 500, maxMs = 8_000))
        assertEquals(8_000, ReconnectBackoff.delayMs(5, baseMs = 500, maxMs = 8_000))
        assertEquals(8_000, ReconnectBackoff.delayMs(9, baseMs = 500, maxMs = 8_000))
    }

    @Test
    fun firstAttemptIsBaseAndNeverNegative() {
        assertEquals(500, ReconnectBackoff.delayMs(0, baseMs = 500, maxMs = 8_000))
        assertTrue(ReconnectBackoff.delayMs(100, baseMs = 500, maxMs = 8_000) <= 8_000)
    }
}
