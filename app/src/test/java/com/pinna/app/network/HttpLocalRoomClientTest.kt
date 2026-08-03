package com.pinna.app.network

import com.pinna.app.core.model.Track
import com.pinna.app.protocol.RoomControlMessage
import com.pinna.app.room.RoomState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class HttpLocalRoomClientTest {
    private var server: HttpLocalRoomServer? = null

    @After
    fun tearDown() {
        runBlocking {
            server?.stop()
        }
    }

    @Test
    fun connectFetchesRoomMetadata() = runBlocking {
        val endpoint = startServer()
        val client = HttpLocalRoomClient()

        val result = client.connect(endpoint, "token-123")

        assertTrue(result.isSuccess)
        assertEquals("room-1", result.getOrThrow().roomId)
        assertEquals(ControlStreamState.Disconnected, client.controlStreamState.value)
    }

    @Test
    fun connectRejectsBadToken() = runBlocking {
        val endpoint = startServer()
        val client = HttpLocalRoomClient()

        val result = client.connect(endpoint, "wrong")

        assertTrue(result.isFailure)
    }

    @Test
    fun connectRejectsRoomIdentityMismatch() = runBlocking {
        val endpoint = startServer().copy(roomId = "different-room")
        val client = HttpLocalRoomClient()

        val result = client.connect(endpoint, "token-123")

        assertTrue(result.isFailure)
    }

    @Test
    fun sendReadyControlMessageReachesServer() = runBlocking {
        val endpoint = startServer()
        val client = HttpLocalRoomClient()
        client.connect(endpoint, "token-123").getOrThrow()
        val message = RoomControlMessage.Ready("listener-1", bufferedUntilMs = 1_000)

        val result = client.send(message)

        assertTrue(result.isSuccess)
        eventually { assertEquals(message, server!!.lastControlMessage) }
    }

    @Test
    fun fetchHostTimeRequiresConnectionAndValidToken() = runBlocking {
        val endpoint = startServer()
        val client = HttpLocalRoomClient()
        val disconnected = client.fetchHostTimeNanos()

        client.connect(endpoint, "token-123").getOrThrow()
        val connected = client.fetchHostTimeNanos()

        assertTrue(disconnected.isFailure)
        assertTrue(connected.isSuccess)
        assertTrue(connected.getOrThrow() > 0)
    }

    @Test
    fun sendBeforeConnectFails() = runBlocking {
        val client = HttpLocalRoomClient()

        val result = client.send(RoomControlMessage.Pause(100, 200, 3))

        assertTrue(result.isFailure)
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
