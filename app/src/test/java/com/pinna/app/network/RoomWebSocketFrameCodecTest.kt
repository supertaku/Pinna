package com.pinna.app.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.charset.StandardCharsets

class RoomWebSocketFrameCodecTest {
    @Test
    fun encodesServerTextFrameWithoutMasking() {
        val frame = RoomWebSocketFrameCodec.encodeText("hello")

        assertArrayEquals(byteArrayOf(0x81.toByte(), 0x05, 'h'.code.toByte(), 'e'.code.toByte(), 'l'.code.toByte(), 'l'.code.toByte(), 'o'.code.toByte()), frame)
    }

    @Test
    fun encodesServerTextFrameAtSixtyFourKibLimit() {
        val frame = RoomWebSocketFrameCodec.encodeText("a".repeat(RoomWebSocketFrameCodec.MAX_PAYLOAD_BYTES))

        assertEquals(0x81.toByte(), frame[0])
        assertEquals(127.toByte(), frame[1])
        assertArrayEquals(byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0), frame.copyOfRange(2, 10))
        assertEquals(10 + RoomWebSocketFrameCodec.MAX_PAYLOAD_BYTES, frame.size)
    }

    @Test
    fun rejectsServerTextFrameAboveSixtyFourKibLimit() {
        assertThrows(IllegalArgumentException::class.java) {
            RoomWebSocketFrameCodec.encodeText("a".repeat(RoomWebSocketFrameCodec.MAX_PAYLOAD_BYTES + 1))
        }
    }

    @Test
    fun decodesMaskedClientTextFrame() {
        val frame = maskedClientFrame(opcode = 0x1, payload = "play".toByteArray(StandardCharsets.UTF_8))

        val decoded = RoomWebSocketFrameCodec.decodeClientFrame(frame)

        assertEquals(RoomWebSocketFrame.Text("play"), decoded)
    }

    @Test
    fun rejectsUnmaskedClientTextFrame() {
        assertThrows(IllegalArgumentException::class.java) {
            RoomWebSocketFrameCodec.decodeClientFrame(byteArrayOf(0x81.toByte(), 0x00))
        }
    }

    @Test
    fun rejectsMaskedClientBinaryFrame() {
        assertThrows(IllegalArgumentException::class.java) {
            RoomWebSocketFrameCodec.decodeClientFrame(maskedClientFrame(opcode = 0x2, payload = byteArrayOf(1, 2, 3)))
        }
    }

    @Test
    fun rejectsFragmentedClientTextFrame() {
        assertThrows(IllegalArgumentException::class.java) {
            RoomWebSocketFrameCodec.decodeClientFrame(maskedClientFrame(opcode = 0x1, payload = "partial".toByteArray(), fin = false))
        }
    }

    @Test
    fun rejectsClientTextFrameWithReservedBits() {
        assertThrows(IllegalArgumentException::class.java) {
            RoomWebSocketFrameCodec.decodeClientFrame(maskedClientFrame(opcode = 0x1, payload = "reserved".toByteArray(), rsvBits = 0x40))
        }
    }

    @Test
    fun rejectsClientTextFrameWithMalformedUtf8() {
        assertThrows(IllegalArgumentException::class.java) {
            RoomWebSocketFrameCodec.decodeClientFrame(maskedClientFrame(opcode = 0x1, payload = byteArrayOf(0xC3.toByte(), 0x28)))
        }
    }

    @Test
    fun rejectsClientTextFrameAboveSixtyFourKibLimit() {
        assertThrows(IllegalArgumentException::class.java) {
            RoomWebSocketFrameCodec.decodeClientFrame(maskedClientFrame(opcode = 0x1, payload = ByteArray(RoomWebSocketFrameCodec.MAX_PAYLOAD_BYTES + 1)))
        }
    }

    @Test
    fun decodesMaskedClientPingFrame() {
        val decoded = RoomWebSocketFrameCodec.decodeClientFrame(maskedClientFrame(opcode = 0x9, payload = byteArrayOf(1, 2, 3)))

        val ping = decoded as RoomWebSocketFrame.Ping
        assertArrayEquals(byteArrayOf(1, 2, 3), ping.payload)
    }

    @Test
    fun rejectsMaskedClientPingFrameAboveControlFrameLimit() {
        assertThrows(IllegalArgumentException::class.java) {
            RoomWebSocketFrameCodec.decodeClientFrame(maskedClientFrame(opcode = 0x9, payload = ByteArray(126)))
        }
    }

    @Test
    fun encodesServerPingFrameWithoutMasking() {
        val frame = RoomWebSocketFrameCodec.encodePing(byteArrayOf(4, 5))

        assertArrayEquals(byteArrayOf(0x89.toByte(), 0x02, 4, 5), frame)
    }

    @Test
    fun encodesServerPongFrameWithoutMasking() {
        val frame = RoomWebSocketFrameCodec.encodePong(byteArrayOf(1, 2, 3))

        assertArrayEquals(byteArrayOf(0x8A.toByte(), 0x03, 1, 2, 3), frame)
    }

    @Test
    fun decodesMaskedClientCloseFrame() {
        val payload = byteArrayOf(0x03, 0xE8.toByte()) + "bye".toByteArray(StandardCharsets.UTF_8)

        val decoded = RoomWebSocketFrameCodec.decodeClientFrame(maskedClientFrame(opcode = 0x8, payload = payload))

        assertEquals(RoomWebSocketFrame.Close(code = 1000, reason = "bye"), decoded)
    }

    @Test
    fun rejectsMaskedClientCloseFrameWithOneBytePayload() {
        assertThrows(IllegalArgumentException::class.java) {
            RoomWebSocketFrameCodec.decodeClientFrame(maskedClientFrame(opcode = 0x8, payload = byteArrayOf(0x03)))
        }
    }

    @Test
    fun encodesServerCloseFrameWithoutMasking() {
        val frame = RoomWebSocketFrameCodec.encodeClose(code = 1000, reason = "bye")

        assertArrayEquals(byteArrayOf(0x88.toByte(), 0x05, 0x03, 0xE8.toByte(), 'b'.code.toByte(), 'y'.code.toByte(), 'e'.code.toByte()), frame)
    }

    private fun maskedClientFrame(opcode: Int, payload: ByteArray, fin: Boolean = true, rsvBits: Int = 0): ByteArray {
        val mask = byteArrayOf(0x37, 0xFA.toByte(), 0x21, 0x3D)
        val first = (if (fin) 0x80 else 0x00) or rsvBits or opcode
        val header = when {
            payload.size <= 125 -> byteArrayOf(first.toByte(), (0x80 or payload.size).toByte())
            payload.size <= 0xFFFF -> byteArrayOf(first.toByte(), (0x80 or 126).toByte(), (payload.size shr 8).toByte(), payload.size.toByte())
            else -> byteArrayOf(
                first.toByte(),
                (0x80 or 127).toByte(),
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
        val masked = ByteArray(payload.size) { index -> (payload[index].toInt() xor mask[index % 4].toInt()).toByte() }
        return header + mask + masked
    }
}
