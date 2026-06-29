package com.pinna.app.qr

import org.junit.Assert.assertEquals
import org.junit.Test

class QrScanResultMapperTest {
    private val validPayload = QrJoinPayloadCodec.encode(
        RoomJoinPayload(
            version = 1,
            roomId = "room-1",
            host = "192.168.1.10",
            port = 1234,
            token = "token",
            expiresAtEpochMillis = 2_000,
            fingerprint = "local-room",
        ),
    )

    @Test
    fun validPinnaQrReturnsPayload() {
        val result = QrScanResultMapper.map(rawValue = validPayload, nowEpochMillis = 1_000)

        assertEquals(QrScanResult.Valid(validPayload), result)
    }

    @Test
    fun malformedQrIsIgnored() {
        val result = QrScanResultMapper.map(rawValue = "https://example.com", nowEpochMillis = 1_000)

        assertEquals(QrScanResult.Ignored, result)
    }

    @Test
    fun expiredPinnaQrReturnsError() {
        val result = QrScanResultMapper.map(rawValue = validPayload, nowEpochMillis = 2_000)

        assertEquals(QrScanResult.Error("This room is no longer available."), result)
    }
}
