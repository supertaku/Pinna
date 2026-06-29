package com.pinna.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomWebSocketHandshakeTest {
    @Test
    fun generatesRfc6455AcceptKey() {
        val acceptKey = RoomWebSocketHandshake.acceptKey("dGhlIHNhbXBsZSBub25jZQ==")

        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", acceptKey)
    }

    @Test
    fun rejectsInvalidClientKeyWhenGeneratingAcceptKey() {
        assertThrows(IllegalArgumentException::class.java) {
            RoomWebSocketHandshake.acceptKey("c2hvcnQ=")
        }
    }

    @Test
    fun validatesClientKeyAsBase64EncodedSixteenByteNonce() {
        assertTrue(RoomWebSocketHandshake.isValidClientKey("dGhlIHNhbXBsZSBub25jZQ=="))

        assertFalse(RoomWebSocketHandshake.isValidClientKey(null))
        assertFalse(RoomWebSocketHandshake.isValidClientKey(""))
        assertFalse(RoomWebSocketHandshake.isValidClientKey("not-base64"))
        assertFalse(RoomWebSocketHandshake.isValidClientKey("c2hvcnQ="))
    }

    @Test
    fun validatesUpgradeRequestHeadersCaseInsensitively() {
        val headers = mapOf(
            "host" to "127.0.0.1:8080",
            "upgrade" to "websocket",
            "connection" to "keep-alive, Upgrade",
            "sec-websocket-version" to "13",
            "sec-websocket-key" to "dGhlIHNhbXBsZSBub25jZQ==",
        )

        assertTrue(RoomWebSocketHandshake.isValidUpgradeRequest(headers))
        assertFalse(RoomWebSocketHandshake.isValidUpgradeRequest(headers - "sec-websocket-key"))
        assertFalse(RoomWebSocketHandshake.isValidUpgradeRequest(headers + ("sec-websocket-version" to "12")))
        assertFalse(RoomWebSocketHandshake.isValidUpgradeRequest(headers + ("connection" to "keep-alive")))
    }
}
