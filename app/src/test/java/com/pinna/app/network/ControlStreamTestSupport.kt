package com.pinna.app.network

import com.pinna.app.protocol.RoomControlMessage
import com.pinna.app.protocol.RoomProtocol
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Base64

internal data class TestHttpHead(
    val code: Int,
    val headers: Map<String, String>,
)

internal data class TestHttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: String,
)

internal class TestControlStreamConnection private constructor(
    private val socket: Socket,
    val response: TestHttpHead,
) : AutoCloseable {
    fun send(message: RoomControlMessage) {
        val frame = maskedClientFrame(opcode = 0x1, payload = RoomProtocol.encode(message).toByteArray(StandardCharsets.UTF_8))
        socket.getOutputStream().write(frame)
        socket.getOutputStream().flush()
    }

    fun readMessage(): RoomControlMessage {
        val frame = readFrameOrNull() as? RoomWebSocketFrame.Text ?: error("Expected WebSocket text frame.")
        return RoomProtocol.decode(frame.value)
    }

    fun readFrameOrNull(): RoomWebSocketFrame? {
        val raw = readRawWebSocketFrame(socket.getInputStream()) ?: return null
        return decodeUnmaskedServerFrame(raw)
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        fun open(endpoint: LocalRoomEndpoint, token: String?): TestControlStreamConnection {
            val socket = Socket(endpoint.host, endpoint.port)
            socket.soTimeout = 2_000
            val key = Base64.getEncoder().encodeToString(ByteArray(16) { index -> index.toByte() })
            val request = buildString {
                append("GET /control-stream HTTP/1.1\r\n")
                append("Host: ${endpoint.host}:${endpoint.port}\r\n")
                if (token != null) append("Authorization: Bearer $token\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Key: $key\r\n")
                append("Sec-WebSocket-Version: 13\r\n")
                append("\r\n")
            }
            socket.getOutputStream().write(request.toByteArray(StandardCharsets.ISO_8859_1))
            socket.getOutputStream().flush()
            val response = readHttpResponseHead(socket.getInputStream())
            if (response.code != 101) {
                socket.close()
            }
            return TestControlStreamConnection(socket, response)
        }
    }
}

internal fun readHttpResponseHead(input: InputStream): TestHttpHead {
    val statusLine = readAsciiLine(input) ?: error("Missing HTTP status line.")
    val code = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: error("Invalid HTTP status line: $statusLine")
    return TestHttpHead(code = code, headers = readAsciiHeaders(input))
}

internal fun readHttpRequest(input: InputStream): TestHttpRequest {
    val requestLine = readAsciiLine(input) ?: error("Missing HTTP request line.")
    val requestParts = requestLine.split(" ")
    val headers = readAsciiHeaders(input)
    val length = headers["content-length"]?.toIntOrNull() ?: 0
    val body = if (length == 0) "" else readExact(input, length).toString(StandardCharsets.UTF_8)
    return TestHttpRequest(
        method = requestParts.getOrElse(0) { "" },
        path = requestParts.getOrElse(1) { "" },
        headers = headers,
        body = body,
    )
}

internal fun writeHttpResponse(output: OutputStream, code: Int, body: String = "") {
    val reason = when (code) {
        101 -> "Switching Protocols"
        200 -> "OK"
        204 -> "No Content"
        401 -> "Unauthorized"
        404 -> "Not Found"
        else -> "OK"
    }
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    val response = buildString {
        append("HTTP/1.1 $code $reason\r\n")
        append("Content-Length: ${bytes.size}\r\n")
        append("Connection: close\r\n")
        append("\r\n")
    }.toByteArray(StandardCharsets.ISO_8859_1)
    output.write(response)
    output.write(bytes)
    output.flush()
}

internal fun writeSwitchingProtocols(output: OutputStream, acceptKey: String) {
    val response = buildString {
        append("HTTP/1.1 101 Switching Protocols\r\n")
        append("Upgrade: websocket\r\n")
        append("Connection: Upgrade\r\n")
        append("Sec-WebSocket-Accept: $acceptKey\r\n")
        append("\r\n")
    }.toByteArray(StandardCharsets.ISO_8859_1)
    output.write(response)
    output.flush()
}

internal fun readRawWebSocketFrame(input: InputStream): ByteArray? {
    val first = input.read()
    if (first < 0) return null
    val second = input.read()
    if (second < 0) return null
    val marker = second and 0x7F
    val extendedLength = when (marker) {
        in 0..125 -> ByteArray(0)
        126 -> readExact(input, 2)
        127 -> readExact(input, 8)
        else -> error("Unsupported length marker.")
    }
    val payloadLength = when (marker) {
        in 0..125 -> marker
        126 -> ((extendedLength[0].toInt() and 0xFF) shl 8) or (extendedLength[1].toInt() and 0xFF)
        else -> ByteBuffer.wrap(extendedLength).long.toInt()
    }
    val maskLength = if ((second and 0x80) != 0) 4 else 0
    return byteArrayOf(first.toByte(), second.toByte()) + extendedLength + readExact(input, maskLength + payloadLength)
}

internal fun maskedClientFrame(opcode: Int, payload: ByteArray): ByteArray {
    val mask = byteArrayOf(0x37, 0xFA.toByte(), 0x21, 0x3D)
    val header = when {
        payload.size <= 125 -> byteArrayOf((0x80 or opcode).toByte(), (0x80 or payload.size).toByte())
        payload.size <= 0xFFFF -> byteArrayOf(
            (0x80 or opcode).toByte(),
            (0x80 or 126).toByte(),
            (payload.size shr 8).toByte(),
            payload.size.toByte(),
        )
        else -> error("Test frame is too large.")
    }
    val masked = ByteArray(payload.size) { index -> (payload[index].toInt() xor mask[index % 4].toInt()).toByte() }
    return header + mask + masked
}

internal fun decodeUnmaskedServerFrame(frame: ByteArray): RoomWebSocketFrame {
    val first = frame[0].toInt() and 0xFF
    val second = frame[1].toInt() and 0xFF
    val opcode = first and 0x0F
    require((second and 0x80) == 0) { "Server frames must not be masked." }
    var offset = 2
    val marker = second and 0x7F
    val payloadLength = when (marker) {
        in 0..125 -> marker
        126 -> {
            val length = ((frame[offset].toInt() and 0xFF) shl 8) or (frame[offset + 1].toInt() and 0xFF)
            offset += 2
            length
        }
        127 -> {
            val length = ByteBuffer.wrap(frame, offset, 8).long.toInt()
            offset += 8
            length
        }
        else -> error("Unsupported length marker.")
    }
    val payload = frame.copyOfRange(offset, offset + payloadLength)
    return when (opcode) {
        0x1 -> RoomWebSocketFrame.Text(payload.toString(StandardCharsets.UTF_8))
        0x8 -> {
            if (payload.size < 2) {
                RoomWebSocketFrame.Close(1000)
            } else {
                RoomWebSocketFrame.Close(((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF))
            }
        }
        0x9 -> RoomWebSocketFrame.Ping(payload)
        0xA -> RoomWebSocketFrame.Pong(payload)
        else -> error("Unexpected server opcode $opcode.")
    }
}

internal fun readExact(input: InputStream, length: Int): ByteArray {
    val bytes = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val read = input.read(bytes, offset, length - offset)
        if (read < 0) error("Unexpected end of stream.")
        offset += read
    }
    return bytes
}

private fun readAsciiHeaders(input: InputStream): Map<String, String> {
    val headers = linkedMapOf<String, String>()
    while (true) {
        val line = readAsciiLine(input) ?: break
        if (line.isBlank()) break
        val index = line.indexOf(':')
        if (index > 0) {
            headers[line.substring(0, index).trim().lowercase()] = line.substring(index + 1).trim()
        }
    }
    return headers
}

private fun readAsciiLine(input: InputStream): String? {
    val buffer = ByteArrayOutputStream()
    while (true) {
        val next = input.read()
        if (next < 0) {
            return if (buffer.size() == 0) null else buffer.toString(StandardCharsets.ISO_8859_1.name())
        }
        if (next == '\n'.code) {
            val bytes = buffer.toByteArray()
            val end = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
            return bytes.copyOf(end).toString(StandardCharsets.ISO_8859_1)
        }
        buffer.write(next)
    }
}
