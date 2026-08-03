package com.pinna.app.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraQrScannerTest {
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

    private val secondValidPayload = QrJoinPayloadCodec.encode(
        RoomJoinPayload(
            version = 1,
            roomId = "room-2",
            host = "192.168.1.11",
            port = 1235,
            token = "token-2",
            expiresAtEpochMillis = 2_000,
            fingerprint = QrJoinPayloadCodec.ROOM_FINGERPRINT,
        ),
    )

    @Test
    fun ignoresNonPinnaQrValuesAndEmitsFirstValidPayloadOnly() {
        val emittedPayloads = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val handler = newHandler(emittedPayloads, errors)

        handler.accept("https://example.com")
        handler.accept(validPayload)
        handler.accept(secondValidPayload)

        assertEquals(listOf(validPayload), emittedPayloads)
        assertTrue(errors.isEmpty())
        assertTrue(handler.isComplete)
    }

    @Test
    fun reportsPinnaPayloadErrorsWithoutCompletingScan() {
        val emittedPayloads = mutableListOf<String>()
        val errors = mutableListOf<String>()
        var nowEpochMillis = 2_000L
        val handler = newHandler(emittedPayloads, errors) { nowEpochMillis }

        handler.accept(validPayload)
        nowEpochMillis = 1_000
        handler.accept(validPayload)

        assertEquals(listOf("This room is no longer available."), errors)
        assertEquals(listOf(validPayload), emittedPayloads)
        assertTrue(handler.isComplete)
    }

    @Test
    fun ignoresAllRawValuesAfterFirstValidPayload() {
        val emittedPayloads = mutableListOf<String>()
        val errors = mutableListOf<String>()
        var nowEpochMillis = 1_000L
        val handler = newHandler(emittedPayloads, errors) { nowEpochMillis }

        handler.accept(validPayload)
        nowEpochMillis = 2_000
        handler.accept(validPayload)

        assertEquals(listOf(validPayload), emittedPayloads)
        assertTrue(errors.isEmpty())
        assertTrue(handler.isComplete)
    }

    @Test
    fun blankRawValueDoesNotCompleteScan() {
        val emittedPayloads = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val handler = newHandler(emittedPayloads, errors)

        handler.accept(null)

        assertTrue(emittedPayloads.isEmpty())
        assertTrue(errors.isEmpty())
        assertFalse(handler.isComplete)
    }

    private fun newHandler(
        emittedPayloads: MutableList<String>,
        errors: MutableList<String>,
        nowEpochMillis: () -> Long = { 1_000 },
    ): QrScanResultHandler = QrScanResultHandler(
        mapResult = { rawValue -> QrScanResultMapper.map(rawValue, nowEpochMillis()) },
        onValidPayload = emittedPayloads::add,
        onScanError = errors::add,
    )
}
