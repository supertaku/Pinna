package com.pinna.app.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QrJoinPayloadTest {
    @Test
    fun payloadRoundTrip_preservesJoinFields() {
        val payload = RoomJoinPayload(
            version = 1,
            roomId = "room-123",
            host = "192.168.1.8",
            port = 48732,
            token = "secret-token",
            expiresAtEpochMillis = 2_000,
            fingerprint = "abc123",
        )

        val encoded = QrJoinPayloadCodec.encode(payload)
        val decoded = QrJoinPayloadCodec.decode(encoded, nowEpochMillis = 1_000)

        assertEquals(QrDecodeResult.Valid(payload), decoded)
    }

    @Test
    fun malformedPayload_returnsInvalidQrError() {
        val decoded = QrJoinPayloadCodec.decode("not-a-pinna-payload", nowEpochMillis = 1_000)

        assertEquals(QrDecodeResult.Invalid("QR code is not a Pinna room."), decoded)
    }

    @Test
    fun expiredPayload_returnsExpiredJoinCode() {
        val encoded = QrJoinPayloadCodec.encode(
            RoomJoinPayload(
                version = 1,
                roomId = "room-123",
                host = "192.168.1.8",
                port = 48732,
                token = "secret-token",
                expiresAtEpochMillis = 999,
                fingerprint = "abc123",
            ),
        )

        val decoded = QrJoinPayloadCodec.decode(encoded, nowEpochMillis = 1_000)

        assertEquals(QrDecodeResult.Expired, decoded)
    }

    @Test
    fun payloadAtExactExpiry_returnsExpiredJoinCode() {
        val encoded = QrJoinPayloadCodec.encode(
            RoomJoinPayload(
                version = 1,
                roomId = "room-123",
                host = "192.168.1.8",
                port = 48732,
                token = "secret-token",
                expiresAtEpochMillis = 1_000,
                fingerprint = "abc123",
            ),
        )

        val decoded = QrJoinPayloadCodec.decode(encoded, nowEpochMillis = 1_000)

        assertEquals(QrDecodeResult.Expired, decoded)
    }

    @Test
    fun missingFingerprint_isRejected() {
        val encoded = "pinna://join?v=1&room=room-123&host=192.168.1.8&port=48732&token=secret-token&exp=2000&fp="

        val decoded = QrJoinPayloadCodec.decode(encoded, nowEpochMillis = 1_000)

        assertEquals(QrDecodeResult.Invalid("Room QR is missing connection details."), decoded)
    }

    @Test
    fun malformedEscaping_returnsInvalidQrError() {
        val decoded = QrJoinPayloadCodec.decode("pinna://join?v=%", nowEpochMillis = 1_000)

        assertEquals(QrDecodeResult.Invalid("Room QR contains invalid encoding."), decoded)
    }

    @Test
    fun redactedPayloadDoesNotExposeToken() {
        val payload = RoomJoinPayload(
            version = 1,
            roomId = "room-123",
            host = "192.168.1.8",
            port = 48732,
            token = "secret-token",
            expiresAtEpochMillis = 2_000,
            fingerprint = "abc123",
        )

        val redacted = payload.redactedForLogs()

        assertFalse(redacted.contains("secret-token"))
        assertTrue(redacted.contains("room-123"))
    }
}
