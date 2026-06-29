package com.pinna.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomTokenGeneratorTest {
    @Test
    fun generatedToken_hasEnoughEntropyForRoomAdmission() {
        val token = RoomTokenGenerator.generate()

        assertTrue(token.value.length >= 43)
        assertFalse(token.redactedForLogs().contains(token.value))
    }

    @Test
    fun tokenExpiresAtConfiguredTtl() {
        val token = RoomTokenGenerator.generate(nowEpochMillis = 1_000, ttlMillis = 5_000)

        assertFalse(token.isExpired(nowEpochMillis = 5_999))
        assertTrue(token.isExpired(nowEpochMillis = 6_000))
    }

    @Test
    fun constantTimeEquals_rejectsDifferentTokens() {
        val token = RoomToken("abc", expiresAtEpochMillis = 10_000)

        assertTrue(token.constantTimeEquals("abc"))
        assertFalse(token.constantTimeEquals("abd"))
        assertFalse(token.constantTimeEquals("abcd"))
    }
}
