package com.pinna.app.network

import com.pinna.app.connectivity.NetworkAddressProvider
import com.pinna.app.protocol.RoomControlMessage
import com.pinna.app.protocol.RoomProtocol
import com.pinna.app.room.RoomEvent
import com.pinna.app.room.RoomReducer
import com.pinna.app.room.RoomState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class HttpLocalRoomServer(
    host: String = LOOPBACK_IPV4,
    private val bindHost: String = host,
    private val addressProvider: NetworkAddressProvider = NetworkAddressProvider { host },
) : LocalRoomServer {
    @Volatile
    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)

    @Volatile
    private var token: String = ""

    @Volatile
    private var roomState: RoomState = RoomState.initial()

    @Volatile
    private var tracks: Map<String, String> = emptyMap()

    private val stateLock = Any()
    private val controlStreams = ControlStreamHub(maxStreams = 8)
    private val _rooms = MutableStateFlow(RoomState.initial())

    /** Live room snapshot, updated as listeners join/ready and the host broadcasts control events. */
    val rooms: StateFlow<RoomState> = _rooms.asStateFlow()

    private val _incomingControl = MutableSharedFlow<RoomControlMessage>(extraBufferCapacity = 64)

    /** Listener-originated push-to-talk messages (voice/start/end), surfaced for the host to play. */
    val incomingControl: SharedFlow<RoomControlMessage> = _incomingControl

    @Volatile
    override var endpoint: LocalRoomEndpoint? = null
        private set

    @Volatile
    var lastControlMessage: RoomControlMessage? = null
        private set

    override suspend fun start(roomState: RoomState, token: String, tracks: Map<String, String>): LocalRoomEndpoint =
        withContext(Dispatchers.IO) {
            stop()
            this@HttpLocalRoomServer.roomState = roomState
            this@HttpLocalRoomServer.token = token
            this@HttpLocalRoomServer.tracks = tracks
            _rooms.value = roomState

            val advertisedHost = addressProvider.selectedIpv4Address().ifBlank { LOOPBACK_IPV4 }
            val socket = ServerSocket(0, 50, InetAddress.getByName(bindHost))
            serverSocket = socket
            running.set(true)
            val nextEndpoint = LocalRoomEndpoint(host = advertisedHost, port = socket.localPort, roomId = roomState.roomId)
            endpoint = nextEndpoint
            thread(name = "PinnaRoomServer-${roomState.roomId}", isDaemon = true) {
                acceptLoop(socket)
            }
            nextEndpoint
        }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        running.set(false)
        controlStreams.closeAll()
        runCatching { serverSocket?.close() }
        serverSocket = null
        endpoint = null
    }

    override suspend fun broadcast(message: RoomControlMessage) {
        val effectiveMessage = recordHostControlMessage(message)
        controlStreams.broadcastText(RoomProtocol.encode(effectiveMessage))
    }

    private fun recordHostControlMessage(message: RoomControlMessage): RoomControlMessage {
        synchronized(stateLock) {
            val effectiveMessage = message.withFreshSequenceAfter(roomState.sequenceNumber)
            lastControlMessage = effectiveMessage
            roomState = applyControlMessage(roomState, effectiveMessage)
            _rooms.value = roomState
            return effectiveMessage
        }
    }

    private fun recordListenerControlMessage(message: RoomControlMessage) {
        synchronized(stateLock) {
            lastControlMessage = message
            roomState = applyControlMessage(roomState, message)
            _rooms.value = roomState
        }
    }

    private fun applyControlMessage(state: RoomState, message: RoomControlMessage): RoomState {
        val generatedSequence = state.sequenceNumber + 1
        val event = when (message) {
            is RoomControlMessage.Join -> RoomEvent.ListenerJoined(
                deviceId = message.deviceId,
                displayName = message.displayName,
                sequenceNumber = generatedSequence,
            )
            is RoomControlMessage.Ready -> RoomEvent.ListenerReady(
                deviceId = message.deviceId,
                bufferedUntilMs = message.bufferedUntilMs,
                hostTimeNanos = System.nanoTime(),
                sequenceNumber = generatedSequence,
            )
            is RoomControlMessage.Play -> RoomEvent.Play(
                trackId = message.trackId,
                positionMs = message.positionMs,
                effectiveAtHostTimeNanos = message.effectiveAtHostTimeNanos,
                sequenceNumber = message.sequenceNumber,
            )
            is RoomControlMessage.Pause -> RoomEvent.Pause(
                positionMs = message.positionMs,
                effectiveAtHostTimeNanos = message.effectiveAtHostTimeNanos,
                sequenceNumber = message.sequenceNumber,
            )
            is RoomControlMessage.Seek -> RoomEvent.Seek(
                positionMs = message.positionMs,
                effectiveAtHostTimeNanos = message.effectiveAtHostTimeNanos,
                sequenceNumber = message.sequenceNumber,
            )
            is RoomControlMessage.QueueUpdate -> RoomEvent.QueueUpdated(
                queue = message.queue,
                sequenceNumber = message.sequenceNumber,
            )
            is RoomControlMessage.SyncSample,
            is RoomControlMessage.StartTalk,
            is RoomControlMessage.EndTalk,
            is RoomControlMessage.Voice,
            is RoomControlMessage.Error,
            -> return state
        }
        return RoomReducer.reduce(state, event)
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = runCatching { socket.accept() }.getOrNull() ?: continue
            thread(name = "PinnaRoomClient", isDaemon = true) {
                client.use(::handleClient)
            }
        }
    }

    private fun handleClient(socket: Socket) {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
        val requestLine = reader.readLine() ?: return
        val requestParts = requestLine.split(" ")
        if (requestParts.size < 2) {
            socket.writeResponse(400, "Bad Request")
            return
        }
        val method = requestParts[0]
        val path = requestParts[1]
        val headers = readHeaders(reader)
        if (!isAuthorized(RoomHttpRoutes.bearerToken(headers))) {
            socket.writeResponse(401, "Unauthorized")
            return
        }
        if (method == "GET" && path == "/control-stream") {
            handleControlStream(socket, headers)
            return
        }
        val body = readBody(reader, headers["content-length"]?.toIntOrNull() ?: 0)
        when {
            method == "GET" && path == "/room" -> socket.writeResponse(200, RoomHttpRoutes.encodeRoomState(currentRoomState()))
            method == "GET" && path == "/time" -> socket.writeResponse(200, RoomHttpRoutes.encodeHostTime(System.nanoTime()))
            method == "GET" && path.startsWith("/media/") -> handleMedia(socket, path.removePrefix("/media/"), headers)
            method == "POST" && path == "/control" -> {
                handleListenerControlMessage(RoomProtocol.decode(body))
                socket.writeResponse(204, "")
            }
            path == "/room" || path == "/time" || path == "/control" || path.startsWith("/media/") -> socket.writeResponse(405, "Method Not Allowed")
            else -> socket.writeResponse(404, "Not Found")
        }
    }

    private fun handleMedia(socket: Socket, encodedTrackId: String, headers: Map<String, String>) {
        val trackId = URLDecoder.decode(encodedTrackId, StandardCharsets.UTF_8.name())
        val path = tracks[trackId]
        if (path == null) {
            socket.writeResponse(404, "Not Found")
            return
        }
        val file = File(path)
        if (!file.exists() || !file.isFile) {
            socket.writeResponse(404, "Not Found")
            return
        }
        val range = MediaRange.parse(headers[RoomHttpRoutes.RANGE], file.length())
        if (range == null) {
            socket.writeResponse(416, "Range Not Satisfiable")
            return
        }
        val bytes = readRange(file, range)
        val status = if (headers.containsKey(RoomHttpRoutes.RANGE)) 206 else 200
        val extraHeaders = buildMap {
            put("Accept-Ranges", "bytes")
            if (status == 206) {
                put(
                    "Content-Range",
                    "bytes ${range.startInclusive}-${range.endInclusive}/${range.totalSizeBytes}",
                )
            }
        }
        socket.writeBytesResponse(status, bytes, "application/octet-stream", extraHeaders)
    }

    private fun handleControlStream(socket: Socket, headers: Map<String, String>) {
        if (!RoomWebSocketHandshake.isValidUpgradeRequest(headers)) {
            socket.writeResponse(400, "Bad Request")
            return
        }
        if (!controlStreams.tryAdd(socket)) {
            socket.writeResponse(503, "Service Unavailable")
            return
        }
        val key = headers["sec-websocket-key"].orEmpty()
        socket.writeSwitchingProtocols(RoomWebSocketHandshake.acceptKey(key))
        try {
            while (running.get() && !socket.isClosed) {
                val frameBytes = socket.readRawWebSocketFrameOrNull() ?: break
                when (val frame = RoomWebSocketFrameCodec.decodeClientFrame(frameBytes)) {
                    is RoomWebSocketFrame.Text -> {
                        val message = RoomProtocol.decode(frame.value)
                        when (message) {
                            is RoomControlMessage.SyncSample ->
                                socket.writeFrame(RoomWebSocketFrameCodec.encodeText(RoomProtocol.encode(stampSyncSample(message))))
                            is RoomControlMessage.Voice,
                            is RoomControlMessage.StartTalk,
                            is RoomControlMessage.EndTalk,
                            -> relayPushToTalk(message)
                            else -> handleListenerControlMessage(message)
                        }
                    }
                    is RoomWebSocketFrame.Ping -> socket.writeFrame(RoomWebSocketFrameCodec.encodePong(frame.payload))
                    is RoomWebSocketFrame.Close -> {
                        socket.writeFrame(RoomWebSocketFrameCodec.encodeClose(frame.code, frame.reason))
                        break
                    }
                    is RoomWebSocketFrame.Pong -> Unit
                }
            }
        } catch (_: Exception) {
            runCatching { socket.writeFrame(RoomWebSocketFrameCodec.encodeClose(code = 1002, reason = "protocol_error")) }
        } finally {
            controlStreams.remove(socket)
        }
    }

    /**
     * Answers an NTP-style sync request with the host monotonic receive (t2) and send (t3)
     * timestamps. The listener supplies t1 and records t4 locally, then estimates clock offset and
     * round-trip delay. Sync requests carry no room authority and never mutate room state.
     */
    private fun stampSyncSample(request: RoomControlMessage.SyncSample): RoomControlMessage.SyncSample {
        val receiveNanos = System.nanoTime()
        return request.copy(t2HostNanos = receiveNanos, t3HostNanos = System.nanoTime())
    }

    /**
     * Fans a listener-originated push-to-talk message out to every connected control stream (so other
     * listeners hear it) and surfaces it on [incomingControl] so the host can play it. Push-to-talk
     * carries no room authority and never mutates room state.
     */
    private fun relayPushToTalk(message: RoomControlMessage) {
        controlStreams.broadcastText(RoomProtocol.encode(message))
        _incomingControl.tryEmit(message)
    }

    private fun handleListenerControlMessage(message: RoomControlMessage) {
        when (message) {
            is RoomControlMessage.Join,
            is RoomControlMessage.Ready,
            is RoomControlMessage.SyncSample,
            -> recordListenerControlMessage(message)
            is RoomControlMessage.Voice,
            is RoomControlMessage.StartTalk,
            is RoomControlMessage.EndTalk,
            -> relayPushToTalk(message)
            is RoomControlMessage.Play,
            is RoomControlMessage.Pause,
            is RoomControlMessage.Seek,
            is RoomControlMessage.QueueUpdate,
            is RoomControlMessage.Error,
            -> Unit
        }
    }

    private fun readRange(file: File, range: MediaRange): ByteArray {
        val bytes = ByteArray(range.contentLength.toInt())
        RandomAccessFile(file, "r").use { input ->
            input.seek(range.startInclusive)
            input.readFully(bytes)
        }
        return bytes
    }

    private fun readHeaders(reader: BufferedReader): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) break
            val index = line.indexOf(':')
            if (index > 0) {
                headers[line.substring(0, index).trim().lowercase()] = line.substring(index + 1).trim()
            }
        }
        return headers
    }

    private fun readBody(reader: BufferedReader, length: Int): String {
        if (length <= 0) return ""
        val chars = CharArray(length)
        var offset = 0
        while (offset < length) {
            val read = reader.read(chars, offset, length - offset)
            if (read < 0) break
            offset += read
        }
        return String(chars, 0, offset)
    }

    private fun isAuthorized(candidate: String?): Boolean {
        if (candidate == null) return false
        return MessageDigest.isEqual(token.encodeToByteArray(), candidate.encodeToByteArray())
    }

    private fun currentRoomState(): RoomState = synchronized(stateLock) { roomState }
}

private const val LOOPBACK_IPV4 = "127.0.0.1"

private fun RoomControlMessage.withFreshSequenceAfter(currentSequenceNumber: Long): RoomControlMessage {
    fun next(incoming: Long): Long = if (incoming <= currentSequenceNumber) currentSequenceNumber + 1 else incoming
    return when (this) {
        is RoomControlMessage.Play -> copy(sequenceNumber = next(sequenceNumber))
        is RoomControlMessage.Pause -> copy(sequenceNumber = next(sequenceNumber))
        is RoomControlMessage.Seek -> copy(sequenceNumber = next(sequenceNumber))
        is RoomControlMessage.QueueUpdate -> copy(sequenceNumber = next(sequenceNumber))
        is RoomControlMessage.Join,
        is RoomControlMessage.Ready,
        is RoomControlMessage.SyncSample,
        is RoomControlMessage.StartTalk,
        is RoomControlMessage.EndTalk,
        is RoomControlMessage.Voice,
        is RoomControlMessage.Error,
        -> this
    }
}

private fun Socket.writeResponse(code: Int, body: String) {
    writeBytesResponse(code, body.encodeToByteArray(), "text/plain; charset=utf-8")
}

private fun Socket.writeBytesResponse(
    code: Int,
    body: ByteArray,
    contentType: String,
    extraHeaders: Map<String, String> = emptyMap(),
) {
    val reason = when (code) {
        200 -> "OK"
        204 -> "No Content"
        206 -> "Partial Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        416 -> "Range Not Satisfiable"
        503 -> "Service Unavailable"
        else -> "OK"
    }
    val header = buildString {
        append("HTTP/1.1 $code $reason\r\n")
        append("Content-Type: $contentType\r\n")
        append("Content-Length: ${body.size}\r\n")
        extraHeaders.forEach { (name, value) -> append("$name: $value\r\n") }
        append("Connection: close\r\n")
        append("\r\n")
    }.encodeToByteArray()
    getOutputStream().use { output ->
        output.write(header)
        output.write(body)
        output.flush()
    }
}

private fun Socket.writeSwitchingProtocols(acceptKey: String) {
    val response = buildString {
        append("HTTP/1.1 101 Switching Protocols\r\n")
        append("Upgrade: websocket\r\n")
        append("Connection: Upgrade\r\n")
        append("Sec-WebSocket-Accept: $acceptKey\r\n")
        append("\r\n")
    }.encodeToByteArray()
    getOutputStream().write(response)
    getOutputStream().flush()
}

private fun Socket.writeFrame(frame: ByteArray) {
    synchronized(this) {
        getOutputStream().write(frame)
        getOutputStream().flush()
    }
}

private fun Socket.readRawWebSocketFrameOrNull(): ByteArray? {
    val input = getInputStream()
    val first = input.read()
    if (first < 0) return null
    val second = input.read()
    if (second < 0) return null
    val marker = second and 0x7F
    val extendedLength = when (marker) {
        in 0..125 -> ByteArray(0)
        126 -> input.readExactBytes(2)
        127 -> input.readExactBytes(8)
        else -> return null
    }
    val payloadLength = when (marker) {
        in 0..125 -> marker
        126 -> ((extendedLength[0].toInt() and 0xFF) shl 8) or (extendedLength[1].toInt() and 0xFF)
        else -> java.nio.ByteBuffer.wrap(extendedLength).long.toInt()
    }
    if (payloadLength < 0 || payloadLength > RoomWebSocketFrameCodec.MAX_PAYLOAD_BYTES) {
        error("WebSocket payload is too large.")
    }
    val maskLength = if ((second and 0x80) != 0) 4 else 0
    return byteArrayOf(first.toByte(), second.toByte()) + extendedLength + input.readExactBytes(maskLength + payloadLength)
}

private fun java.io.InputStream.readExactBytes(length: Int): ByteArray {
    val bytes = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val read = read(bytes, offset, length - offset)
        if (read < 0) error("Unexpected end of WebSocket frame.")
        offset += read
    }
    return bytes
}
