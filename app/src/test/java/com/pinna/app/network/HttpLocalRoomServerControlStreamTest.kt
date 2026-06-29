package com.pinna.app.network

import com.pinna.app.core.model.PlaybackState
import com.pinna.app.core.model.Track
import com.pinna.app.protocol.RoomControlMessage
import com.pinna.app.protocol.RoomProtocol
import com.pinna.app.room.RoomState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files

class HttpLocalRoomServerControlStreamTest {
    private val servers = mutableListOf<HttpLocalRoomServer>()

    @After
    fun tearDown() = runBlocking {
        servers.forEach { it.stop() }
    }

    @Test
    fun missingAndWrongTokenRejectUpgradeWith401() = runBlocking {
        val server = newStartedServer()
        val endpoint = server.endpoint!!

        val missing = TestControlStreamConnection.open(endpoint, token = null)
        val wrong = TestControlStreamConnection.open(endpoint, token = "wrong")

        assertEquals(401, missing.response.code)
        assertEquals(401, wrong.response.code)
    }

    @Test
    fun validTokenUpgradesControlStream() = runBlocking {
        val server = newStartedServer()
        val endpoint = server.endpoint!!

        TestControlStreamConnection.open(endpoint, token = "token-123").use { stream ->
            assertEquals(101, stream.response.code)
            assertEquals("websocket", stream.response.headers["upgrade"])
        }
    }

    @Test
    fun listenerJoinUpdatesRoomState() = runBlocking {
        val server = newStartedServer()
        val endpoint = server.endpoint!!

        TestControlStreamConnection.open(endpoint, token = "token-123").use { stream ->
            stream.send(RoomControlMessage.Join("listener-1", "Listener"))

            eventually {
                val room = fetchRoom(endpoint)
                assertEquals(listOf("listener-1"), room.listeners.map { it.deviceId })
            }
        }
    }

    @Test
    fun listenerPlayIsIgnoredAndDoesNotMutateRoomState() = runBlocking {
        val server = newStartedServer()
        val endpoint = server.endpoint!!

        TestControlStreamConnection.open(endpoint, token = "token-123").use { stream ->
            stream.send(RoomControlMessage.Play("track-1", positionMs = 500, effectiveAtHostTimeNanos = 600, sequenceNumber = 1))
            stream.send(RoomControlMessage.Join("listener-1", "Listener"))

            eventually {
                val room = fetchRoom(endpoint)
                assertEquals(listOf("listener-1"), room.listeners.map { it.deviceId })
                assertEquals(PlaybackState.IDLE, room.playback)
                assertEquals(0, room.hostPositionMs)
            }
        }
    }

    @Test
    fun syncSampleRequestReceivesHostStampedReply() = runBlocking {
        val server = newStartedServer()
        val endpoint = server.endpoint!!

        TestControlStreamConnection.open(endpoint, token = "token-123").use { stream ->
            stream.send(RoomControlMessage.SyncSample(t1ClientNanos = 12_345, t2HostNanos = 0, t3HostNanos = 0))

            val reply = stream.readMessage() as RoomControlMessage.SyncSample
            assertEquals(12_345, reply.t1ClientNanos)
            assertTrue(reply.t2HostNanos != 0L)
            assertTrue(reply.t3HostNanos >= reply.t2HostNanos)
        }
    }

    @Test
    fun syncSampleDoesNotMutateRoomState() = runBlocking {
        val server = newStartedServer()
        val endpoint = server.endpoint!!

        TestControlStreamConnection.open(endpoint, token = "token-123").use { stream ->
            stream.send(RoomControlMessage.SyncSample(t1ClientNanos = 1, t2HostNanos = 0, t3HostNanos = 0))
            stream.readMessage()

            val room = fetchRoom(endpoint)
            assertTrue(room.listeners.isEmpty())
            assertEquals(PlaybackState.IDLE, room.playback)
            assertEquals(0, room.sequenceNumber)
        }
    }

    @Test
    fun listenerVoiceFrameIsRebroadcastToOtherStreams() = runBlocking {
        val server = newStartedServer()
        val endpoint = server.endpoint!!
        val voice = RoomControlMessage.Voice(deviceId = "listener-1", sequence = 1, pcmBase64 = "AAEC")

        TestControlStreamConnection.open(endpoint, token = "token-123").use { talker ->
            TestControlStreamConnection.open(endpoint, token = "token-123").use { other ->
                talker.send(voice)

                assertEquals(voice, other.readMessage())
            }
        }
    }

    @Test
    fun listenerVoiceFrameDoesNotMutateRoomState() = runBlocking {
        val server = newStartedServer()
        val endpoint = server.endpoint!!

        TestControlStreamConnection.open(endpoint, token = "token-123").use { stream ->
            stream.send(RoomControlMessage.StartTalk("listener-1"))
            // Give the relay a moment, then confirm room state is untouched.
            val room = fetchRoom(endpoint)
            assertEquals(PlaybackState.IDLE, room.playback)
            assertEquals(0, room.sequenceNumber)
            assertTrue(room.listeners.isEmpty())
        }
    }

    @Test
    fun postControlHostOnlyMessageIsIgnoredAndDoesNotMutateRoomState() = runBlocking {
        val server = newStartedServer()
        val endpoint = server.endpoint!!

        val response = postControl(
            endpoint = endpoint,
            message = RoomControlMessage.Pause(positionMs = 500, effectiveAtHostTimeNanos = 600, sequenceNumber = 1),
        )

        assertEquals(204, response)
        val room = fetchRoom(endpoint)
        assertEquals(PlaybackState.IDLE, room.playback)
        assertEquals(0, room.hostPositionMs)
    }

    @Test
    fun postControlReadyUpdatesListenerState() = runBlocking {
        val server = newStartedServer()
        val endpoint = server.endpoint!!

        val response = postControl(endpoint, RoomControlMessage.Join("listener-1", "Listener"))

        assertEquals(204, response)
        eventually {
            assertEquals(listOf("listener-1"), fetchRoom(endpoint).listeners.map { it.deviceId })
        }
    }

    @Test
    fun broadcastPlayReachesTwoConnectedControlStreams() = runBlocking {
        val server = newStartedServer()
        val endpoint = server.endpoint!!
        val message = RoomControlMessage.Play("track-1", positionMs = 1_000, effectiveAtHostTimeNanos = 2_000, sequenceNumber = 1)

        TestControlStreamConnection.open(endpoint, token = "token-123").use { first ->
            TestControlStreamConnection.open(endpoint, token = "token-123").use { second ->
                server.broadcast(message)

                assertEquals(message, first.readMessage())
                assertEquals(message, second.readMessage())
                assertEquals(message, server.lastControlMessage)
                eventually {
                    assertEquals(PlaybackState.PLAYING, fetchRoom(endpoint).playback)
                }
            }
        }
    }

    @Test
    fun broadcastPlayAfterListenerJoinUsesFreshServerSequence() = runBlocking {
        val server = newStartedServer()
        val endpoint = server.endpoint!!

        TestControlStreamConnection.open(endpoint, token = "token-123").use { stream ->
            stream.send(RoomControlMessage.Join("listener-1", "Listener"))
            eventually {
                assertEquals(listOf("listener-1"), fetchRoom(endpoint).listeners.map { it.deviceId })
            }

            server.broadcast(RoomControlMessage.Play("track-1", positionMs = 1_000, effectiveAtHostTimeNanos = 2_000, sequenceNumber = 1))

            val broadcast = stream.readMessage() as RoomControlMessage.Play
            assertEquals(2, broadcast.sequenceNumber)
            eventually {
                val room = fetchRoom(endpoint)
                assertEquals(PlaybackState.PLAYING, room.playback)
                assertEquals(2, room.sequenceNumber)
            }
        }
    }

    @Test
    fun stopClosesActiveControlStreams() = runBlocking {
        val server = newStartedServer()
        val endpoint = server.endpoint!!
        val stream = TestControlStreamConnection.open(endpoint, token = "token-123")

        server.stop()

        val close = stream.readFrameOrNull()
        assertTrue(close == null || close is RoomWebSocketFrame.Close)
        stream.close()
    }

    @Test
    fun controlStreamCapsListenersAtEight() = runBlocking {
        val server = newStartedServer()
        val endpoint = server.endpoint!!
        val streams = (1..8).map {
            TestControlStreamConnection.open(endpoint, token = "token-123").also { stream ->
                assertEquals(101, stream.response.code)
            }
        }

        val ninth = TestControlStreamConnection.open(endpoint, token = "token-123")

        assertEquals(503, ninth.response.code)
        streams.forEach { it.close() }
    }

    private suspend fun newStartedServer(): HttpLocalRoomServer {
        val file = Files.createTempFile("pinna-track", ".bin")
        Files.write(file, "abcde".encodeToByteArray())
        val track = Track("track-1", "Track", null, 1000, "audio/mpeg", file.toString(), 5)
        val state = RoomState(roomId = "room-1", hostDeviceId = "host-1", queue = listOf(track), currentTrackId = "track-1")
        val server = HttpLocalRoomServer(host = "127.0.0.1")
        server.start(state, "token-123", mapOf("track-1" to file.toString()))
        servers += server
        return server
    }

    private fun fetchRoom(endpoint: LocalRoomEndpoint): RoomState {
        val connection = URL("http://${endpoint.host}:${endpoint.port}/room").openConnection() as HttpURLConnection
        connection.setRequestProperty("Authorization", "Bearer token-123")
        connection.connect()
        assertEquals(200, connection.responseCode)
        return RoomHttpRoutes.decodeRoomState(connection.inputStream.bufferedReader().use { it.readText() })
    }

    private fun postControl(endpoint: LocalRoomEndpoint, message: RoomControlMessage): Int {
        val connection = URL("http://${endpoint.host}:${endpoint.port}/control").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Authorization", "Bearer token-123")
        connection.outputStream.use { it.write(RoomProtocol.encode(message).encodeToByteArray()) }
        return connection.responseCode
    }

    private fun eventually(assertion: () -> Unit) {
        val deadline = System.nanoTime() + 2_000_000_000L
        var lastError: AssertionError? = null
        while (System.nanoTime() < deadline) {
            try {
                assertion()
                return
            } catch (error: AssertionError) {
                lastError = error
                Thread.sleep(25)
            }
        }
        throw lastError ?: AssertionError("Condition was not met.")
    }
}
