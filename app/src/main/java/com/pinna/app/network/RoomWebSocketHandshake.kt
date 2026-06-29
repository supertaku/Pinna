package com.pinna.app.network

import java.security.MessageDigest
import java.util.Base64

object RoomWebSocketHandshake {
    private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    fun acceptKey(clientKey: String): String {
        require(isValidClientKey(clientKey)) { "Sec-WebSocket-Key must be a base64-encoded 16-byte nonce." }
        val digest = MessageDigest.getInstance("SHA-1")
            .digest((clientKey.trim() + GUID).toByteArray(Charsets.ISO_8859_1))
        return Base64.getEncoder().encodeToString(digest)
    }

    fun isValidClientKey(clientKey: String?): Boolean {
        if (clientKey.isNullOrBlank()) return false
        return runCatching {
            Base64.getDecoder().decode(clientKey.trim()).size == 16
        }.getOrDefault(false)
    }

    fun isValidUpgradeRequest(headers: Map<String, String>): Boolean {
        val normalized = headers.mapKeys { it.key.lowercase() }
        val upgrade = normalized["upgrade"].orEmpty()
        val connection = normalized["connection"].orEmpty()
        val version = normalized["sec-websocket-version"].orEmpty()
        val key = normalized["sec-websocket-key"]

        return upgrade.equals("websocket", ignoreCase = true) &&
            connection.split(",").any { it.trim().equals("upgrade", ignoreCase = true) } &&
            version == "13" &&
            isValidClientKey(key)
    }
}
