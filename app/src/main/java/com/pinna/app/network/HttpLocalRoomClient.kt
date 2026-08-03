package com.pinna.app.network

import com.pinna.app.protocol.RoomControlMessage
import com.pinna.app.protocol.RoomProtocol
import com.pinna.app.room.RoomState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

class HttpLocalRoomClient : LocalRoomClient {
    private companion object {
        const val HTTP_TIMEOUT_MS = 3_000
        const val CONTROL_HANDSHAKE_TIMEOUT_MS = 3_000
    }

    @Volatile
    private var endpoint: LocalRoomEndpoint? = null
    @Volatile
    private var token: String? = null
    private var controlSocket: Socket? = null
    private val controlStreamGeneration = AtomicLong(0)
    private val connectionGeneration = AtomicLong(0)
    private val controlStreamLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _controlMessages = MutableSharedFlow<RoomControlMessage>(extraBufferCapacity = 32)
    private val _controlStreamState = MutableStateFlow<ControlStreamState>(ControlStreamState.Disconnected)

    override val controlMessages: SharedFlow<RoomControlMessage> = _controlMessages
    override val controlStreamState: StateFlow<ControlStreamState> = _controlStreamState

    override suspend fun connect(endpoint: LocalRoomEndpoint, token: String): Result<RoomState> =
        withContext(Dispatchers.IO) {
            val generation = connectionGeneration.incrementAndGet()
            runCatching {
                val body = request("GET", endpoint, "/room", token)
                val room = RoomHttpRoutes.decodeRoomState(body)
                require(room.roomId == endpoint.roomId) { "Room identity did not match the scanned payload." }
                require(room.hostDeviceId.isNotBlank()) { "Room response is missing its host identity." }
                synchronized(controlStreamLock) {
                    require(connectionGeneration.get() == generation) { "Room connection was superseded." }
                    this@HttpLocalRoomClient.endpoint = endpoint
                    this@HttpLocalRoomClient.token = token
                }
                room
            }
        }

    override suspend fun openControlStream(endpoint: LocalRoomEndpoint, token: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val generation = synchronized(controlStreamLock) {
                val nextGeneration = controlStreamGeneration.incrementAndGet()
                closeControlSocketLocked()
                nextGeneration
            }
            _controlStreamState.value = ControlStreamState.Connecting
            var socket: Socket? = null
            runCatching {
                socket = Socket()
                socket.connect(InetSocketAddress(endpoint.host, endpoint.port), CONTROL_HANDSHAKE_TIMEOUT_MS)
                socket.soTimeout = CONTROL_HANDSHAKE_TIMEOUT_MS
                val key = Base64.getEncoder().encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) })
                val request = buildString {
                    append("GET /control-stream HTTP/1.1\r\n")
                    append("Host: ${endpoint.host}:${endpoint.port}\r\n")
                    append("Authorization: Bearer $token\r\n")
                    append("Upgrade: websocket\r\n")
                    append("Connection: Upgrade\r\n")
                    append("Sec-WebSocket-Key: $key\r\n")
                    append("Sec-WebSocket-Version: 13\r\n")
                    append("\r\n")
                }.toByteArray(StandardCharsets.ISO_8859_1)
                socket.writeFrame(request)
                val response = readHttpResponseHead(socket.getInputStream())
                require(response.code == 101) { "Control stream request failed with HTTP ${response.code}" }
                require(response.headers["upgrade"].equals("websocket", ignoreCase = true)) { "Control stream upgrade failed." }
                require(response.headers["sec-websocket-accept"] == RoomWebSocketHandshake.acceptKey(key)) {
                    "Control stream handshake was rejected."
                }
                socket.soTimeout = 0
                synchronized(controlStreamLock) {
                    require(controlStreamGeneration.get() == generation) { "Control stream request was superseded." }
                    require(this@HttpLocalRoomClient.endpoint == endpoint && this@HttpLocalRoomClient.token == token) {
                        "Control stream request was superseded."
                    }
                    controlSocket = socket
                    _controlStreamState.value = ControlStreamState.Connected
                }
                scope.launch { readControlStream(socket) }
                Unit
            }.onFailure { failure ->
                runCatching { socket?.close() }
                synchronized(controlStreamLock) {
                    if (controlStreamGeneration.get() == generation) {
                        closeControlSocketLocked()
                        _controlStreamState.value = ControlStreamState.Failed(failure.message ?: "Control stream failed.")
                    }
                }
            }
        }

    override suspend fun send(message: RoomControlMessage): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val activeEndpoint = requireNotNull(endpoint) { "Client is not connected." }
                val activeToken = requireNotNull(token) { "Client is not connected." }
                val encoded = RoomProtocol.encode(message)
                val socket = controlSocket
                if (socket != null && !socket.isClosed && controlStreamState.value is ControlStreamState.Connected) {
                    socket.writeFrame(maskedClientTextFrame(encoded))
                } else {
                    request("POST", activeEndpoint, "/control", activeToken, encoded)
                }
                Unit
            }
        }

    override suspend fun disconnect() {
        synchronized(controlStreamLock) {
            connectionGeneration.incrementAndGet()
            controlStreamGeneration.incrementAndGet()
            closeControlSocketLocked()
        }
        _controlStreamState.value = ControlStreamState.Disconnected
        endpoint = null
        token = null
    }

    override suspend fun shutdown() {
        disconnect()
        scope.cancel()
    }

    suspend fun fetchHostTimeNanos(): Result<Long> =
        withContext(Dispatchers.IO) {
            runCatching {
                val activeEndpoint = requireNotNull(endpoint) { "Client is not connected." }
                val activeToken = requireNotNull(token) { "Client is not connected." }
                RoomHttpRoutes.decodeHostTime(request("GET", activeEndpoint, "/time", activeToken))
            }
        }

    fun mediaUrl(endpoint: LocalRoomEndpoint, trackId: String): String =
        "http://${endpoint.host}:${endpoint.port}/media/${java.net.URLEncoder.encode(trackId, "UTF-8")}"

    fun authorizationHeaders(token: String): Map<String, String> = mapOf("Authorization" to "Bearer $token")

    private fun readControlStream(socket: Socket) {
        try {
            while (!socket.isClosed) {
                if (controlSocket !== socket) break
                val frame = readUnmaskedServerFrame(socket.getInputStream()) ?: break
                when (frame) {
                    is RoomWebSocketFrame.Text -> {
                        if (controlSocket === socket) {
                            _controlMessages.tryEmit(RoomProtocol.decode(frame.value))
                        }
                    }
                    is RoomWebSocketFrame.Ping -> {
                        if (controlSocket === socket) {
                            socket.writeFrame(maskedClientFrame(opcode = 0xA, payload = frame.payload))
                        }
                    }
                    is RoomWebSocketFrame.Close -> break
                    is RoomWebSocketFrame.Pong -> Unit
                }
            }
            if (controlSocket === socket) {
                _controlStreamState.value = ControlStreamState.Disconnected
            }
        } catch (failure: Exception) {
            if (controlSocket === socket) {
                _controlStreamState.value = ControlStreamState.Failed(failure.message ?: "Control stream failed.")
            }
        } finally {
            if (controlSocket === socket) closeControlSocket()
        }
    }

    private fun closeControlSocket() {
        synchronized(controlStreamLock) {
            closeControlSocketLocked()
        }
    }

    private fun closeControlSocketLocked() {
        runCatching { controlSocket?.close() }
        controlSocket = null
    }

    private fun request(
        method: String,
        endpoint: LocalRoomEndpoint,
        path: String,
        token: String,
        body: String? = null,
    ): String {
        val connection = URL("http://${endpoint.host}:${endpoint.port}$path").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = HTTP_TIMEOUT_MS
            connection.readTimeout = HTTP_TIMEOUT_MS
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.encodeToByteArray()) }
            }
            val code = connection.responseCode
            if (code !in 200..299) error("Room request failed with HTTP $code")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private data class HttpHead(val code: Int, val headers: Map<String, String>)

    private fun readHttpResponseHead(input: InputStream): HttpHead {
        val statusLine = readAsciiLine(input) ?: error("Missing HTTP response.")
        val code = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: error("Invalid HTTP response.")
        return HttpHead(code = code, headers = readAsciiHeaders(input))
    }

    private fun readUnmaskedServerFrame(input: InputStream): RoomWebSocketFrame? {
        val first = input.read()
        if (first < 0) return null
        val second = input.read()
        if (second < 0) return null
        require((second and 0x80) == 0) { "Server frames must not be masked." }
        val marker = second and 0x7F
        val extendedLength = when (marker) {
            in 0..125 -> ByteArray(0)
            126 -> input.readExactBytes(2)
            127 -> input.readExactBytes(8)
            else -> error("Unsupported WebSocket length marker.")
        }
        val payloadLength = when (marker) {
            in 0..125 -> marker
            126 -> ((extendedLength[0].toInt() and 0xFF) shl 8) or (extendedLength[1].toInt() and 0xFF)
            else -> ByteBuffer.wrap(extendedLength).long.toInt()
        }
        require(payloadLength >= 0 && payloadLength <= RoomWebSocketFrameCodec.MAX_PAYLOAD_BYTES) {
            "WebSocket payload is too large."
        }
        val payload = input.readExactBytes(payloadLength)
        val opcode = first and 0x0F
        return when (opcode) {
            0x1 -> RoomWebSocketFrame.Text(payload.toString(StandardCharsets.UTF_8))
            0x8 -> RoomWebSocketFrame.Close(1000)
            0x9 -> RoomWebSocketFrame.Ping(payload)
            0xA -> RoomWebSocketFrame.Pong(payload)
            else -> null
        }
    }

    private fun maskedClientTextFrame(text: String): ByteArray =
        maskedClientFrame(opcode = 0x1, payload = text.toByteArray(StandardCharsets.UTF_8))

    private fun maskedClientFrame(opcode: Int, payload: ByteArray): ByteArray {
        val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
        val header = when {
            payload.size <= 125 -> byteArrayOf((0x80 or opcode).toByte(), (0x80 or payload.size).toByte())
            payload.size <= 0xFFFF -> byteArrayOf(
                (0x80 or opcode).toByte(),
                (0x80 or 126).toByte(),
                (payload.size shr 8).toByte(),
                payload.size.toByte(),
            )
            else -> error("Control stream message is too large.")
        }
        val masked = ByteArray(payload.size) { index -> (payload[index].toInt() xor mask[index % 4].toInt()).toByte() }
        return header + mask + masked
    }

    private fun readAsciiHeaders(input: InputStream): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = readAsciiLine(input) ?: break
            if (line.isBlank()) break
            val index = line.indexOf(':')
            if (index > 0) headers[line.substring(0, index).trim().lowercase()] = line.substring(index + 1).trim()
        }
        return headers
    }

    private fun readAsciiLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            if (next < 0) return if (buffer.size() == 0) null else buffer.toString(StandardCharsets.ISO_8859_1.name())
            if (next == '\n'.code) {
                val bytes = buffer.toByteArray()
                val end = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                return bytes.copyOf(end).toString(StandardCharsets.ISO_8859_1)
            }
            buffer.write(next)
        }
    }

    private fun InputStream.readExactBytes(length: Int): ByteArray {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = read(bytes, offset, length - offset)
            if (read < 0) error("Unexpected end of WebSocket frame.")
            offset += read
        }
        return bytes
    }

    private fun Socket.writeFrame(frame: ByteArray) {
        synchronized(this) {
            getOutputStream().write(frame)
            getOutputStream().flush()
        }
    }
}
