package com.pinna.app.network

import com.pinna.app.core.model.Track
import com.pinna.app.protocol.RoomControlMessage
import com.pinna.app.protocol.RoomProtocol
import com.pinna.app.room.RoomState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class HttpLocalRoomClientControlStreamTest {
    private var server: HttpLocalRoomServer? = null
    private var recordingServer: RecordingRoomServer? = null

    @After
    fun tearDown() {
        runBlocking {
        server?.stop()
        recordingServer?.stop()
        }
    }

    @Test
    fun openControlStreamReceivesIncomingBroadcast() = runBlocking {
        val endpoint = startServer()
        val client = HttpLocalRoomClient()
        val room = client.connect(endpoint, "token-123").getOrThrow()
        client.openControlStream(endpoint, "token-123").getOrThrow()
        val incoming = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2_000) { client.controlMessages.first() }
        }
        val message = RoomControlMessage.Pause(positionMs = 100, effectiveAtHostTimeNanos = 200, sequenceNumber = 1)

        server!!.broadcast(message)

        assertEquals("room-1", room.roomId)
        assertTrue(client.controlStreamState.value is ControlStreamState.Connected)
        assertEquals(message, incoming.await())
        client.disconnect()
    }

    @Test
    fun controlMessagesAreNotReplayedToLateCollectors() = runBlocking {
        val endpoint = startServer()
        val client = HttpLocalRoomClient()
        client.connect(endpoint, "token-123").getOrThrow()
        client.openControlStream(endpoint, "token-123").getOrThrow()
        val firstCollector = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2_000) { client.controlMessages.first() }
        }
        val message = RoomControlMessage.Pause(positionMs = 100, effectiveAtHostTimeNanos = 200, sequenceNumber = 1)

        server!!.broadcast(message)
        assertEquals(message, firstCollector.await())

        val replayed = withTimeoutOrNull(200) { client.controlMessages.first() }
        assertNull(replayed)
        client.disconnect()
    }

    @Test
    fun sendReadyUsesWebSocketWhenConnected() = runBlocking {
        val roomServer = RecordingRoomServer(allowControlStream = true).also { it.start() }
        recordingServer = roomServer
        val client = HttpLocalRoomClient()
        client.connect(roomServer.endpoint, "token-123").getOrThrow()
        client.openControlStream(roomServer.endpoint, "token-123").getOrThrow()
        val message = RoomControlMessage.Ready("listener-1", bufferedUntilMs = 1_500)

        val result = client.send(message)

        assertTrue(result.isSuccess)
        assertEquals(message, roomServer.awaitControlStreamMessage())
        assertEquals(emptyList<RoomControlMessage>(), roomServer.postedControlMessages)
        client.disconnect()
    }

    @Test
    fun staleOpenControlStreamCannotReplaceNewerStream() = runBlocking {
        val oldHandshakeStarted = CountDownLatch(1)
        val oldHandshakeRelease = CountDownLatch(1)
        val oldServer = RecordingRoomServer(
            allowControlStream = true,
            controlStreamStarted = oldHandshakeStarted,
            controlStreamRelease = oldHandshakeRelease,
        ).also { it.start() }
        val newServer = RecordingRoomServer(allowControlStream = true).also { it.start() }
        recordingServer = newServer
        val client = HttpLocalRoomClient()
        client.connect(oldServer.endpoint, "token-123").getOrThrow()
        val oldOpen = async(Dispatchers.IO) { client.openControlStream(oldServer.endpoint, "token-123") }
        assertTrue(oldHandshakeStarted.await(2, TimeUnit.SECONDS))

        client.disconnect()
        client.connect(newServer.endpoint, "token-123").getOrThrow()
        client.openControlStream(newServer.endpoint, "token-123").getOrThrow()
        oldHandshakeRelease.countDown()
        oldOpen.await()
        val message = RoomControlMessage.Ready("listener-1", bufferedUntilMs = 1_500)

        val result = client.send(message)

        assertTrue(result.isSuccess)
        assertEquals(message, newServer.awaitControlStreamMessage())
        oldServer.assertNoControlStreamMessageWithin(250)
        oldServer.stop()
        newServer.stop()
        client.disconnect()
    }

    @Test
    fun fallbackPostControlWorksWhenControlStreamIsAbsent() = runBlocking {
        val roomServer = RecordingRoomServer(allowControlStream = false).also { it.start() }
        recordingServer = roomServer
        val client = HttpLocalRoomClient()
        client.connect(roomServer.endpoint, "token-123").getOrThrow()
        val message = RoomControlMessage.Pause(positionMs = 100, effectiveAtHostTimeNanos = 200, sequenceNumber = 3)

        val result = client.send(message)

        assertTrue(result.isSuccess)
        assertEquals(listOf(message), roomServer.postedControlMessages)
        client.disconnect()
    }

    private suspend fun startServer(): LocalRoomEndpoint {
        val file = Files.createTempFile("pinna-track", ".bin")
        Files.write(file, "abcde".encodeToByteArray())
        val track = Track("track-1", "Track", null, 1000, "audio/mpeg", file.toString(), 5)
        val state = RoomState(roomId = "room-1", hostDeviceId = "host-1", queue = listOf(track), currentTrackId = "track-1")
        val localServer = HttpLocalRoomServer(host = "127.0.0.1")
        server = localServer
        return localServer.start(state, "token-123", mapOf("track-1" to file.toString()))
    }
}

private class RecordingRoomServer(
    private val allowControlStream: Boolean,
    private val controlStreamStarted: CountDownLatch? = null,
    private val controlStreamRelease: CountDownLatch? = null,
) {
    private val running = AtomicBoolean(false)
    private val receivedControlStreamMessages = mutableListOf<RoomControlMessage>()
    private val webSocketMessageLatch = CountDownLatch(1)
    private var serverSocket: ServerSocket? = null

    lateinit var endpoint: LocalRoomEndpoint
        private set

    val postedControlMessages = mutableListOf<RoomControlMessage>()

    fun start() {
        val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        endpoint = LocalRoomEndpoint("127.0.0.1", socket.localPort, "room-1")
        running.set(true)
        thread(name = "RecordingRoomServer", isDaemon = true) {
            while (running.get()) {
                val client = runCatching { socket.accept() }.getOrNull() ?: continue
                thread(name = "RecordingRoomClient", isDaemon = true) {
                    client.use(::handleClient)
                }
            }
        }
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
    }

    fun awaitControlStreamMessage(): RoomControlMessage {
        assertTrue(webSocketMessageLatch.await(2, TimeUnit.SECONDS))
        return synchronized(receivedControlStreamMessages) { receivedControlStreamMessages.single() }
    }

    private fun handleClient(socket: Socket) {
        val request = readHttpRequest(socket.getInputStream())
        val token = RoomHttpRoutes.bearerToken(request.headers)
        if (token != "token-123") {
            writeHttpResponse(socket.getOutputStream(), 401, "Unauthorized")
            return
        }
        when {
            request.method == "GET" && request.path == "/room" -> {
                val body = RoomHttpRoutes.encodeRoomState(RoomState(roomId = "room-1", hostDeviceId = "host-1"))
                writeHttpResponse(socket.getOutputStream(), 200, body)
            }
            request.method == "GET" && request.path == "/control-stream" && allowControlStream -> {
                controlStreamStarted?.countDown()
                controlStreamRelease?.await(2, TimeUnit.SECONDS)
                val key = request.headers["sec-websocket-key"].orEmpty()
                writeSwitchingProtocols(socket.getOutputStream(), RoomWebSocketHandshake.acceptKey(key))
                val rawFrame = readRawWebSocketFrame(socket.getInputStream()) ?: return
                val text = RoomWebSocketFrameCodec.decodeClientFrame(rawFrame) as RoomWebSocketFrame.Text
                synchronized(receivedControlStreamMessages) {
                    receivedControlStreamMessages += RoomProtocol.decode(text.value)
                }
                webSocketMessageLatch.countDown()
            }
            request.method == "GET" && request.path == "/control-stream" -> {
                writeHttpResponse(socket.getOutputStream(), 404, "Not Found")
            }
            request.method == "POST" && request.path == "/control" -> {
                postedControlMessages += RoomProtocol.decode(request.body)
                writeHttpResponse(socket.getOutputStream(), 204)
            }
            else -> writeHttpResponse(socket.getOutputStream(), 404, "Not Found")
        }
    }

    fun assertNoControlStreamMessageWithin(timeoutMs: Long) {
        assertTrue(!webSocketMessageLatch.await(timeoutMs, TimeUnit.MILLISECONDS))
    }
}
