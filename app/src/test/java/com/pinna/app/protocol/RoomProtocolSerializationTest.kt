package com.pinna.app.protocol

import com.pinna.app.core.model.Track
import com.pinna.app.core.model.publicMediaUriFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RoomProtocolSerializationTest {
    @Test
    fun playMessage_roundTrips() {
        val message = RoomControlMessage.Play(
            trackId = "track-1",
            positionMs = 10_000,
            effectiveAtHostTimeNanos = 42_000,
            sequenceNumber = 7,
        )

        val encoded = RoomProtocol.encode(message)
        val decoded = RoomProtocol.decode(encoded)

        assertEquals(message, decoded)
    }

    @Test
    fun queueUpdate_roundTripsWithTrackMetadata() {
        val privateUri = "C:/app/files/private-track.audio"
        val message = RoomControlMessage.QueueUpdate(
            queue = listOf(
                Track(
                    id = "track-1",
                    title = "Pulse Test",
                    artist = "Pinna",
                    durationMs = 180_000,
                    mimeType = "audio/mpeg",
                    localUri = privateUri,
                    sizeBytes = 1_000_000,
                ),
            ),
            sequenceNumber = 8,
        )

        val encoded = RoomProtocol.encode(message)
        val decoded = RoomProtocol.decode(encoded)

        assertFalse(encoded.contains(privateUri))
        assertEquals(
            message.copy(
                queue = message.queue.map { it.copy(localUri = publicMediaUriFor(it.id)) },
            ),
            decoded,
        )
    }

    @Test
    fun pushToTalkMessages_roundTrip() {
        val voice = RoomControlMessage.Voice(deviceId = "dev-1", sequence = 12, pcmBase64 = "AAEC/f7/")
        val start = RoomControlMessage.StartTalk(deviceId = "dev-1")
        val end = RoomControlMessage.EndTalk(deviceId = "dev-2")

        assertEquals(voice, RoomProtocol.decode(RoomProtocol.encode(voice)))
        assertEquals(start, RoomProtocol.decode(RoomProtocol.encode(start)))
        assertEquals(end, RoomProtocol.decode(RoomProtocol.encode(end)))
    }

    @Test
    fun missingType_isRejected() {
        val decoded = RoomProtocol.decode("sequenceNumber=1")

        assertEquals(RoomControlMessage.Error("invalid_message", "Missing message type."), decoded)
    }

    @Test
    fun malformedEscaping_isRejectedWithoutThrowing() {
        val decoded = RoomProtocol.decode("type=%")

        assertEquals(RoomControlMessage.Error("invalid_message", "Invalid message encoding."), decoded)
    }
}
