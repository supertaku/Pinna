package com.pinna.app.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

data class RoomToken(
    val value: String,
    val expiresAtEpochMillis: Long,
) {
    fun isExpired(nowEpochMillis: Long): Boolean = nowEpochMillis >= expiresAtEpochMillis

    fun redactedForLogs(): String = "RoomToken(value=<redacted>, expiresAtEpochMillis=$expiresAtEpochMillis)"

    fun constantTimeEquals(candidate: String): Boolean {
        val expected = value.encodeToByteArray()
        val actual = candidate.encodeToByteArray()
        return MessageDigest.isEqual(expected, actual)
    }
}

object RoomTokenGenerator {
    private const val DEFAULT_TTL_MILLIS = 30 * 60 * 1000L
    private val secureRandom = SecureRandom()

    fun generate(
        nowEpochMillis: Long = System.currentTimeMillis(),
        ttlMillis: Long = DEFAULT_TTL_MILLIS,
    ): RoomToken {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        val value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        return RoomToken(value = value, expiresAtEpochMillis = nowEpochMillis + ttlMillis)
    }
}
