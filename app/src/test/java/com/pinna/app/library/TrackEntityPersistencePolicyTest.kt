package com.pinna.app.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Milestone 8 persistence guardrail: Room only stores host-imported library metadata. Active room
 * tokens, QR payloads, and ephemeral socket state must never be added to a persisted entity.
 */
class TrackEntityPersistencePolicyTest {
    @Test
    fun trackEntityHasNoTokenOrPayloadFields() {
        val sensitiveNames = listOf("token", "payload", "secret", "passphrase", "ssid", "session")
        val fieldNames = TrackEntity::class.java.declaredFields.map { it.name.lowercase() }

        sensitiveNames.forEach { sensitive ->
            assertFalse(
                "TrackEntity must not persist a '$sensitive' field.",
                fieldNames.any { it.contains(sensitive) },
            )
        }
    }

    @Test
    fun trackEntityOnlyExposesLibraryMetadata() {
        val expected = setOf(
            "id",
            "title",
            "artist",
            "durationMs",
            "mimeType",
            "localPath",
            "sizeBytes",
            "createdAtEpochMillis",
            "lastPlayedAtEpochMillis",
        )
        val actual = TrackEntity::class.java.declaredFields
            .map { it.name }
            .filterNot { it.startsWith("\$") || it == "Companion" }
            .toSet()

        assertTrue(
            "Unexpected persisted fields: ${actual - expected}",
            actual.all { it in expected },
        )
    }
}
