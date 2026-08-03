package com.pinna.app.network

import com.pinna.app.core.model.Track
import com.pinna.app.core.model.publicMediaUriFor
import com.pinna.app.connectivity.NetworkAddressProvider
import com.pinna.app.protocol.RoomControlMessage
import com.pinna.app.protocol.RoomProtocol
import com.pinna.app.room.RoomState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files

class HttpLocalRoomServerTest {
    private val servers = mutableListOf<HttpLocalRoomServer>()

    @After
    fun tearDown() = runBlocking {
        servers.forEach { it.stop() }
    }

    @Test
    fun roomEndpointRejectsMissingTokenAndAcceptsValidToken() = runBlocking {
        val server = newStartedServer()
        val endpoint = server.endpoint!!

        val missing = get("http://${endpoint.host}:${endpoint.port}/room", token = null)
        val valid = get("http://${endpoint.host}:${endpoint.port}/room", token = "token-123")

        assertEquals(401, missing.code)
        assertEquals(200, valid.code)
        assertTrue(valid.body.contains("roomId=room-1"))
    }

    @Test
    fun startAdvertisesSelectedHostWhileBindingAllInterfaces() = runBlocking {
        val server = newStartedServer(
            server = HttpLocalRoomServer(
                bindHost = "0.0.0.0",
                addressProvider = NetworkAddressProvider { "192.168.1.10" },
            ),
        )
        val endpoint = server.endpoint!!

        val response = get("http://127.0.0.1:${endpoint.port}/room", token = "token-123")

        assertEquals("192.168.1.10", endpoint.host)
        assertEquals(200, response.code)
        assertTrue(response.body.contains("roomId=room-1"))
    }

    @Test
    fun roomEndpointDoesNotExposeHostPrivateTrackPath() = runBlocking {
        val privateTrackPath = Files.createTempFile("pinna-private-track", ".bin").toString()
        val server = newStartedServer(trackPath = privateTrackPath)
        val endpoint = server.endpoint!!

        val response = get("http://${endpoint.host}:${endpoint.port}/room", token = "token-123")
        val decoded = RoomHttpRoutes.decodeRoomState(response.body)

        assertEquals(200, response.code)
        assertFalse(response.body.contains(privateTrackPath))
        assertEquals(publicMediaUriFor("track-1"), decoded.queue.single().localUri)
    }

    @Test
    fun timeEndpointRequiresTokenAndReturnsHostTime() = runBlocking {
        val server = newStartedServer()
        val endpoint = server.endpoint!!

        val missing = get("http://${endpoint.host}:${endpoint.port}/time", token = null)
        val valid = get("http://${endpoint.host}:${endpoint.port}/time", token = "token-123")

        assertEquals(401, missing.code)
        assertEquals(200, valid.code)
        assertTrue(RoomHttpRoutes.decodeHostTime(valid.body) > 0)
    }

    @Test
    fun mediaEndpointHonorsByteRange() = runBlocking {
        val file = Files.createTempFile("pinna-track", ".bin")
        Files.write(file, byteArrayOf(10, 11, 12, 13, 14))
        val server = newStartedServer(trackPath = file.toString())
        val endpoint = server.endpoint!!

        val response = get(
            url = "http://${endpoint.host}:${endpoint.port}/media/track-1",
            token = "token-123",
            range = "bytes=1-3",
        )

        assertEquals(206, response.code)
        assertEquals(listOf(11, 12, 13), response.bytes.map { it.toInt() })
    }

    @Test
    fun unknownTrackReturnsNotFound() = runBlocking {
        val server = newStartedServer()
        val endpoint = server.endpoint!!

        val response = get("http://${endpoint.host}:${endpoint.port}/media/missing", token = "token-123")

        assertEquals(404, response.code)
    }

    @Test
    fun wrongTokenIsRejectedForEveryEndpoint() = runBlocking {
        val server = newStartedServer()
        val endpoint = server.endpoint!!

        val room = get("http://${endpoint.host}:${endpoint.port}/room", token = "wrong")
        val media = get("http://${endpoint.host}:${endpoint.port}/media/track-1", token = "wrong")
        val control = post("http://${endpoint.host}:${endpoint.port}/control", token = "wrong", body = "")

        assertEquals(401, room.code)
        assertEquals(401, media.code)
        assertEquals(401, control.code)
    }

    @Test
    fun mediaEndpointReturnsFullFileWithoutRange() = runBlocking {
        val file = Files.createTempFile("pinna-track", ".bin")
        Files.write(file, byteArrayOf(10, 11, 12, 13, 14))
        val server = newStartedServer(trackPath = file.toString())
        val endpoint = server.endpoint!!

        val response = get("http://${endpoint.host}:${endpoint.port}/media/track-1", token = "token-123")

        assertEquals(200, response.code)
        assertEquals(listOf(10, 11, 12, 13, 14), response.bytes.map { it.toInt() })
        assertEquals("bytes", response.headers["Accept-Ranges"]?.single())
    }

    @Test
    fun invalidMediaRangeReturns416() = runBlocking {
        val server = newStartedServer()
        val endpoint = server.endpoint!!

        val response = get(
            url = "http://${endpoint.host}:${endpoint.port}/media/track-1",
            token = "token-123",
            range = "bytes=100-200",
        )

        assertEquals(416, response.code)
    }

    @Test
    fun wrongMethodReturns405ForKnownRoute() = runBlocking {
        val server = newStartedServer()
        val endpoint = server.endpoint!!

        val response = post("http://${endpoint.host}:${endpoint.port}/room", token = "token-123", body = "")

        assertEquals(405, response.code)
    }

    @Test
    fun stopClosesPort() = runBlocking {
        val server = newStartedServer()
        val endpoint = server.endpoint!!

        server.stop()
        val result = runCatching { get("http://${endpoint.host}:${endpoint.port}/room", token = "token-123") }

        assertTrue(result.isFailure)
    }

    @Test
    fun controlEndpointStoresAllowedListenerControlMessage() = runBlocking {
        val server = newStartedServer()
        val endpoint = server.endpoint!!
        val message = RoomControlMessage.Join("listener-1", "Listener")

        val response = post(
            url = "http://${endpoint.host}:${endpoint.port}/control",
            token = "token-123",
            body = RoomProtocol.encode(message),
        )

        assertEquals(204, response.code)
        assertEquals(message, server.lastControlMessage)
    }

    @Test
    fun controlEndpointRejectsHostOnlyControlMessage() = runBlocking {
        val server = newStartedServer()
        val endpoint = server.endpoint!!

        post(
            url = "http://${endpoint.host}:${endpoint.port}/control",
            token = "token-123",
            body = RoomProtocol.encode(RoomControlMessage.Play("track-1", 1000, 2000, 1)),
        )
        val room = get("http://${endpoint.host}:${endpoint.port}/room", token = "token-123")

        assertTrue(room.body.contains("playback=IDLE"))
        assertTrue(room.body.contains("hostPositionMs=0"))
    }

    private suspend fun newStartedServer(
        trackPath: String = Files.createTempFile("pinna-track", ".bin").toString(),
        server: HttpLocalRoomServer = HttpLocalRoomServer(host = "127.0.0.1"),
    ): HttpLocalRoomServer {
        val path = java.nio.file.Path.of(trackPath)
        if (Files.size(path) == 0L) {
            Files.write(path, "abcde".encodeToByteArray())
        }
        val track = Track(
            id = "track-1",
            title = "Track",
            artist = null,
            durationMs = 1000,
            mimeType = "audio/mpeg",
            localUri = trackPath,
            sizeBytes = 5,
        )
        val state = RoomState(roomId = "room-1", hostDeviceId = "host-1", queue = listOf(track), currentTrackId = "track-1")
        server.start(state, "token-123", mapOf("track-1" to trackPath))
        servers += server
        return server
    }

    private fun get(url: String, token: String?, range: String? = null): HttpResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
        if (range != null) connection.setRequestProperty("Range", range)
        connection.connect()
        return connection.toHttpResponse()
    }

    private fun post(url: String, token: String, body: String): HttpResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.outputStream.use { it.write(body.encodeToByteArray()) }
        return connection.toHttpResponse()
    }
}
