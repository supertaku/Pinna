package com.pinna.app.network

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

sealed interface RoomWebSocketFrame {
    data class Text(val value: String) : RoomWebSocketFrame
    data class Ping(val payload: ByteArray) : RoomWebSocketFrame {
        override fun equals(other: Any?): Boolean = other is Ping && payload.contentEquals(other.payload)
        override fun hashCode(): Int = payload.contentHashCode()
    }
    data class Pong(val payload: ByteArray) : RoomWebSocketFrame {
        override fun equals(other: Any?): Boolean = other is Pong && payload.contentEquals(other.payload)
        override fun hashCode(): Int = payload.contentHashCode()
    }
    data class Close(val code: Int, val reason: String = "") : RoomWebSocketFrame
}

object RoomWebSocketFrameCodec {
    const val MAX_PAYLOAD_BYTES = 64 * 1024

    fun encodeText(text: String): ByteArray = encodeServerFrame(opcode = 0x1, payload = text.toByteArray(StandardCharsets.UTF_8))

    fun encodePing(payload: ByteArray): ByteArray {
        require(payload.size <= 125) { "WebSocket control frame payload is too large." }
        return encodeServerFrame(opcode = 0x9, payload = payload)
    }

    fun encodePong(payload: ByteArray): ByteArray {
        require(payload.size <= 125) { "WebSocket control frame payload is too large." }
        return encodeServerFrame(opcode = 0xA, payload = payload)
    }

    fun encodeClose(code: Int = 1000, reason: String = ""): ByteArray {
        val reasonBytes = reason.toByteArray(StandardCharsets.UTF_8)
        val payload = ByteBuffer.allocate(2 + reasonBytes.size)
            .putShort(code.toShort())
            .put(reasonBytes)
            .array()
        require(payload.size <= 125) { "WebSocket control frame payload is too large." }
        return encodeServerFrame(opcode = 0x8, payload = payload)
    }

    fun decodeClientFrame(frame: ByteArray): RoomWebSocketFrame {
        require(frame.size >= 2) { "WebSocket frame is too short." }
        val first = frame[0].toInt() and 0xFF
        val second = frame[1].toInt() and 0xFF
        val fin = (first and 0x80) != 0
        val opcode = first and 0x0F
        val masked = (second and 0x80) != 0
        var offset = 2

        require((first and 0x70) == 0) { "Reserved WebSocket bits are not supported." }
        require(fin) { "Fragmented WebSocket frames are not supported." }
        require(masked) { "Client WebSocket frames must be masked." }

        val payloadLengthMarker = second and 0x7F
        val payloadLength = when (payloadLengthMarker) {
            in 0..125 -> payloadLengthMarker.toLong()
            126 -> {
                require(frame.size >= offset + 2) { "WebSocket frame length is truncated." }
                ((frame[offset].toInt() and 0xFF) shl 8 or (frame[offset + 1].toInt() and 0xFF)).toLong()
                    .also { offset += 2 }
            }
            127 -> {
                require(frame.size >= offset + 8) { "WebSocket frame length is truncated." }
                ByteBuffer.wrap(frame, offset, 8).long.also { offset += 8 }
            }
            else -> error("Unsupported WebSocket payload length.")
        }
        require(payloadLength <= MAX_PAYLOAD_BYTES) { "WebSocket payload is too large." }
        require(payloadLength >= 0) { "WebSocket payload length is invalid." }
        if (opcode in 0x8..0xA) {
            require(payloadLength <= 125) { "WebSocket control frame payload is too large." }
        }
        require(frame.size >= offset + 4 + payloadLength.toInt()) { "WebSocket frame payload is truncated." }

        val mask = frame.copyOfRange(offset, offset + 4)
        offset += 4
        val payload = ByteArray(payloadLength.toInt()) { index ->
            (frame[offset + index].toInt() xor mask[index % 4].toInt()).toByte()
        }

        return when (opcode) {
            0x1 -> RoomWebSocketFrame.Text(decodeUtf8(payload))
            0x8 -> decodeClose(payload)
            0x9 -> RoomWebSocketFrame.Ping(payload)
            0xA -> RoomWebSocketFrame.Pong(payload)
            0x2 -> throw IllegalArgumentException("Binary WebSocket frames are not supported.")
            else -> throw IllegalArgumentException("Unsupported WebSocket opcode.")
        }
    }

    private fun encodeServerFrame(opcode: Int, payload: ByteArray): ByteArray {
        require(payload.size <= MAX_PAYLOAD_BYTES) { "WebSocket payload is too large." }
        val header = when {
            payload.size <= 125 -> byteArrayOf((0x80 or opcode).toByte(), payload.size.toByte())
            payload.size <= 0xFFFF -> byteArrayOf(
                (0x80 or opcode).toByte(),
                126,
                (payload.size shr 8).toByte(),
                payload.size.toByte(),
            )
            else -> byteArrayOf(
                (0x80 or opcode).toByte(),
                127,
                0,
                0,
                0,
                0,
                ((payload.size.toLong() shr 24) and 0xFF).toByte(),
                ((payload.size.toLong() shr 16) and 0xFF).toByte(),
                ((payload.size.toLong() shr 8) and 0xFF).toByte(),
                (payload.size.toLong() and 0xFF).toByte(),
            )
        }
        return header + payload
    }

    private fun decodeClose(payload: ByteArray): RoomWebSocketFrame.Close {
        require(payload.size != 1) { "WebSocket close frame payload cannot be one byte." }
        if (payload.size < 2) return RoomWebSocketFrame.Close(code = 1000)
        val code = (payload[0].toInt() and 0xFF) shl 8 or (payload[1].toInt() and 0xFF)
        val reason = decodeUtf8(payload.copyOfRange(2, payload.size))
        return RoomWebSocketFrame.Close(code = code, reason = reason)
    }

    private fun decodeUtf8(payload: ByteArray): String {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(payload)).toString()
        } catch (exception: CharacterCodingException) {
            throw IllegalArgumentException("WebSocket text payload must be valid UTF-8.", exception)
        }
    }
}
