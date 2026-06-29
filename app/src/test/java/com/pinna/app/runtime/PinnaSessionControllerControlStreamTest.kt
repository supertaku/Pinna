package com.pinna.app.runtime

import com.pinna.app.core.model.PlaybackState
import com.pinna.app.core.model.Track
import com.pinna.app.core.model.publicMediaUriFor
import com.pinna.app.network.ControlStreamState
import com.pinna.app.network.LocalRoomClient
import com.pinna.app.network.LocalRoomEndpoint
import com.pinna.app.network.LocalRoomServer
import com.pinna.app.playback.PlaybackController
import com.pinna.app.playback.PlaybackSnapshot
import com.pinna.app.protocol.RoomControlMessage
import com.pinna.app.qr.QrJoinPayloadCodec
import com.pinna.app.qr.RoomJoinPayload
import com.pinna.app.room.RoomState
import com.pinna.app.sync.SyncQuality
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Test

class PinnaSessionControllerControlStreamTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val controllers = mutableListOf<PinnaSessionController>()
    private val track = Track(
        id = "track-1",
        title = "Track",
        artist = null,
        durationMs = 1_000,
        mimeType = "audio/mpeg",
        localUri = publicMediaUriFor("track-1"),
        sizeBytes = 100,
    )
    private val secondTrack = Track(
        id = "track-2",
        title = "Next",
        artist = null,
        durationMs = 2_000,
        mimeType = "audio/mpeg",
        localUri = publicMediaUriFor("track-2"),
        sizeBytes = 200,
    )
    private val endpoint = LocalRoomEndpoint("127.0.0.1", 1234, "room-1")

    @Test
    fun validPlayMessageUpdatesListenerStateAndStartsPlayback() = runBlocking {
        val client = ControlStreamFakeClient(room(playback = PlaybackState.PAUSED, sequenceNumber = 1))
        val playback = ControlStreamFakePlaybackController()
        val controller = newController(client = client, playback = playback)

        controller.joinRoom(payload(), nowEpochMillis = 1_000)
        scope.runCurrent()
        client.emit(RoomControlMessage.Play("track-1", positionMs = 400, effectiveAtHostTimeNanos = System.nanoTime(), sequenceNumber = 2))
        scope.runCurrent()

        assertEquals(PlaybackState.PLAYING, controller.state.value.listenerRoomState!!.playback)
        assertEquals(PlaybackState.PLAYING, playback.snapshots.value.state)
        assertEquals("http://127.0.0.1:1234/media/track-1", playback.lastUri)
        assertEquals(mapOf("Authorization" to "Bearer token"), playback.lastHeaders)
    }

    @Test
    fun validPauseMessagePausesPlayback() = runBlocking {
        val client = ControlStreamFakeClient(room(playback = PlaybackState.PLAYING, sequenceNumber = 2))
        val playback = ControlStreamFakePlaybackController()
        val controller = newController(client = client, playback = playback)
        controller.joinRoom(payload(), nowEpochMillis = 1_000)
        scope.runCurrent()

        client.emit(RoomControlMessage.Pause(positionMs = 500, effectiveAtHostTimeNanos = 700, sequenceNumber = 3))
        scope.runCurrent()

        assertEquals(PlaybackState.PAUSED, controller.state.value.listenerRoomState!!.playback)
        assertEquals(PlaybackState.PAUSED, playback.snapshots.value.state)
    }

    @Test
    fun stalePlayMessageIsIgnored() = runBlocking {
        val client = ControlStreamFakeClient(room(playback = PlaybackState.PAUSED, sequenceNumber = 5))
        val playback = ControlStreamFakePlaybackController()
        val controller = newController(client = client, playback = playback)
        controller.joinRoom(payload(), nowEpochMillis = 1_000)
        scope.runCurrent()

        client.emit(RoomControlMessage.Play("track-1", positionMs = 900, effectiveAtHostTimeNanos = 1_000, sequenceNumber = 4))
        scope.runCurrent()

        assertEquals(PlaybackState.PAUSED, controller.state.value.listenerRoomState!!.playback)
        assertNull(playback.snapshots.value.trackId)
    }

    @Test
    fun queueUpdatePreservesPublicTrackUris() = runBlocking {
        val client = ControlStreamFakeClient(room(sequenceNumber = 1))
        val controller = newController(client = client)
        val newTrack = Track("track-2", "Next", null, 2_000, "audio/mpeg", "C:/private/track-2.audio", 200)
        controller.joinRoom(payload(), nowEpochMillis = 1_000)
        scope.runCurrent()

        client.emit(RoomControlMessage.QueueUpdate(listOf(newTrack), sequenceNumber = 2))
        scope.runCurrent()

        assertEquals(publicMediaUriFor("track-2"), controller.state.value.listenerRoomState!!.queue.single().localUri)
    }

    @Test
    fun queueUpdateRemovingCurrentTrackSwitchesListenerPlayback() = runBlocking {
        val client = ControlStreamFakeClient(room(playback = PlaybackState.PLAYING, sequenceNumber = 1))
        val playback = ControlStreamFakePlaybackController()
        val controller = newController(client = client, playback = playback)
        controller.joinRoom(payload(), nowEpochMillis = 1_000)
        scope.runCurrent()

        client.emit(RoomControlMessage.QueueUpdate(listOf(secondTrack.copy(localUri = "C:/private/track-2.audio")), sequenceNumber = 2))
        scope.runCurrent()

        assertEquals("track-2", controller.state.value.listenerRoomState!!.currentTrackId)
        assertEquals("track-2", playback.snapshots.value.trackId)
        assertEquals("http://127.0.0.1:1234/media/track-2", playback.lastUri)
    }

    @Test
    fun controlStreamOpenFailureDoesNotEnterListenerRoomOrStartPlayback() = runBlocking {
        val client = ControlStreamFakeClient(
            roomState = room(playback = PlaybackState.PLAYING, sequenceNumber = 1),
            openControlStreamResult = Result.failure(IllegalStateException("websocket unavailable")),
        )
        val playback = ControlStreamFakePlaybackController()
        val controller = newController(client = client, playback = playback)

        controller.joinRoom(payload(), nowEpochMillis = 1_000)
        scope.runCurrent()

        assertEquals(PinnaScreen.Scanner, controller.state.value.screen)
        assertEquals("websocket unavailable", controller.state.value.errorMessage)
        assertNull(controller.state.value.listenerRoomState)
        assertNull(playback.snapshots.value.trackId)
    }

    @Test
    fun olderJoinContinuationCannotReplaceNewerRoom() = runBlocking {
        val oldRoomReady = CompletableDeferred<Unit>()
        val oldRoomResume = CompletableDeferred<Unit>()
        val oldClient = DelayedJoinClient(
            oldRoomReady = oldRoomReady,
            oldRoomResume = oldRoomResume,
            oldResult = Result.success(room(sequenceNumber = 1)),
            newRoom = room(roomId = "room-2", track = secondTrack, sequenceNumber = 1),
        )
        val playback = ControlStreamFakePlaybackController()
        val controller = newController(client = oldClient, playback = playback)

        scope.launch { controller.joinRoom(payload(roomId = "room-1"), nowEpochMillis = 1_000) }
        scope.runCurrent()
        oldRoomReady.await()
        scope.launch { controller.joinRoom(payload(roomId = "room-2"), nowEpochMillis = 1_000) }
        scope.runCurrent()
        oldRoomResume.complete(Unit)
        scope.runCurrent()

        assertEquals("room-2", controller.state.value.listenerRoomState!!.roomId)
        assertEquals("track-2", controller.state.value.listenerRoomState!!.currentTrackId)
        assertEquals(1, oldClient.openedRoomIds.count { it == "room-2" })
        assertEquals(0, oldClient.openedRoomIds.count { it == "room-1" })
    }

    @Test
    fun olderFailedJoinContinuationCannotClearNewerRoom() = runBlocking {
        val oldRoomReady = CompletableDeferred<Unit>()
        val oldRoomResume = CompletableDeferred<Unit>()
        val oldClient = DelayedJoinClient(
            oldRoomReady = oldRoomReady,
            oldRoomResume = oldRoomResume,
            oldResult = Result.failure(IllegalStateException("old network failed")),
            newRoom = room(roomId = "room-2", track = secondTrack, sequenceNumber = 1),
        )
        val controller = newController(client = oldClient)

        scope.launch { controller.joinRoom(payload(roomId = "room-1"), nowEpochMillis = 1_000) }
        scope.runCurrent()
        oldRoomReady.await()
        scope.launch { controller.joinRoom(payload(roomId = "room-2"), nowEpochMillis = 1_000) }
        scope.runCurrent()
        oldRoomResume.complete(Unit)
        scope.runCurrent()

        assertEquals(PinnaScreen.ListenerRoom, controller.state.value.screen)
        assertEquals("room-2", controller.state.value.listenerRoomState!!.roomId)
        assertNull(controller.state.value.errorMessage)
    }

    @Test
    fun leaveRoomCancelsControlCollection() = runBlocking {
        val client = ControlStreamFakeClient(room(sequenceNumber = 1))
        val playback = ControlStreamFakePlaybackController()
        val controller = newController(client = client, playback = playback)
        controller.joinRoom(payload(), nowEpochMillis = 1_000)
        scope.runCurrent()

        controller.leaveRoom()
        scope.runCurrent()
        client.emit(RoomControlMessage.Play("track-1", positionMs = 100, effectiveAtHostTimeNanos = 200, sequenceNumber = 2))
        scope.runCurrent()

        assertNull(controller.state.value.listenerRoomState)
        assertNull(playback.snapshots.value.trackId)
    }

    @Test
    fun syncReplyUpdatesListenerSyncStatus() = runBlocking {
        val client = ControlStreamFakeClient(room(playback = PlaybackState.PAUSED, sequenceNumber = 1))
        val controller = newController(client = client)
        controller.joinRoom(payload(), nowEpochMillis = 1_000)
        scope.runCurrent()

        val t1 = System.nanoTime()
        client.emit(
            RoomControlMessage.SyncSample(
                t1ClientNanos = t1,
                t2HostNanos = t1 + 50_000_000,
                t3HostNanos = t1 + 50_000_000,
            ),
        )
        scope.runCurrent()

        val sync = controller.state.value.listenerSync
        assertNotNull(sync.roundTripNanos)
        assertEquals(0, sync.correctionCount)
    }

    @Test
    fun manualOffsetIsClampedToCalibrationRange() = runBlocking {
        val controller = newController(client = ControlStreamFakeClient(room()))

        controller.setManualOffsetMs(5_000)
        assertEquals(300, controller.state.value.listenerSync.manualOffsetMs)

        controller.setManualOffsetMs(-5_000)
        assertEquals(-300, controller.state.value.listenerSync.manualOffsetMs)

        controller.resetManualOffset()
        assertEquals(0, controller.state.value.listenerSync.manualOffsetMs)
    }

    @Test
    fun unstableSyncQualityBeforeSamples() = runBlocking {
        val controller = newController(client = ControlStreamFakeClient(room(playback = PlaybackState.PAUSED, sequenceNumber = 1)))
        controller.joinRoom(payload(), nowEpochMillis = 1_000)
        scope.runCurrent()

        assertEquals(SyncQuality.UNSTABLE, controller.state.value.listenerSync.quality)
    }

    @Test
    fun streamErrorMessageIsSurfaced() = runBlocking {
        val client = ControlStreamFakeClient(room(sequenceNumber = 1))
        val controller = newController(client = client)
        controller.joinRoom(payload(), nowEpochMillis = 1_000)
        scope.runCurrent()

        client.emit(RoomControlMessage.Error("room_closed", "The host ended the room."))
        scope.runCurrent()

        assertEquals("The host ended the room.", controller.state.value.errorMessage)
    }

    private fun newController(
        client: LocalRoomClient,
        playback: PlaybackController = ControlStreamFakePlaybackController(),
    ): PinnaSessionController = PinnaSessionController(
        server = ControlStreamFakeLocalRoomServer(),
        client = client,
        playback = playback,
        hostName = "host-1",
        scope = scope,
    ).also { controllers += it }

    @After
    fun tearDown() = runBlocking {
        controllers.forEach { it.shutdown() }
    }

    private fun room(
        roomId: String = "room-1",
        track: Track = this.track,
        playback: PlaybackState = PlaybackState.IDLE,
        sequenceNumber: Long = 0,
    ) = RoomState(
        roomId = roomId,
        hostDeviceId = "host-1",
        queue = listOf(track),
        currentTrackId = track.id,
        playback = playback,
        hostPositionMs = 0,
        effectiveAtHostTimeNanos = System.nanoTime(),
        sequenceNumber = sequenceNumber,
    )

    private fun payload(roomId: String = "room-1"): String = QrJoinPayloadCodec.encode(
        RoomJoinPayload(
            version = 1,
            roomId = roomId,
            host = endpoint.host,
            port = endpoint.port,
            token = "token",
            expiresAtEpochMillis = 2_000,
            fingerprint = "local-room",
        ),
    )
}

private class DelayedJoinClient(
    private val oldRoomReady: CompletableDeferred<Unit>,
    private val oldRoomResume: CompletableDeferred<Unit>,
    private val oldResult: Result<RoomState>,
    private val newRoom: RoomState,
) : LocalRoomClient {
    override val controlMessages = MutableSharedFlow<RoomControlMessage>(extraBufferCapacity = 16)
    override val controlStreamState = MutableStateFlow<ControlStreamState>(ControlStreamState.Disconnected)
    val openedRoomIds = mutableListOf<String>()
    private var connectCalls = 0

    override suspend fun connect(endpoint: LocalRoomEndpoint, token: String): Result<RoomState> {
        connectCalls += 1
        return if (connectCalls == 1) {
            oldRoomReady.complete(Unit)
            oldRoomResume.await()
            oldResult
        } else {
            Result.success(newRoom)
        }
    }

    override suspend fun openControlStream(endpoint: LocalRoomEndpoint, token: String): Result<Unit> {
        openedRoomIds += endpoint.roomId
        controlStreamState.value = ControlStreamState.Connected
        return Result.success(Unit)
    }

    override suspend fun send(message: RoomControlMessage): Result<Unit> = Result.success(Unit)

    override suspend fun disconnect() {
        controlStreamState.value = ControlStreamState.Disconnected
    }
}

private class ControlStreamFakeClient(
    private val roomState: RoomState,
    private val openControlStreamResult: Result<Unit> = Result.success(Unit),
) : LocalRoomClient {
    override val controlMessages = MutableSharedFlow<RoomControlMessage>(extraBufferCapacity = 16)
    override val controlStreamState = MutableStateFlow<ControlStreamState>(ControlStreamState.Disconnected)
    var openedStream = false
        private set
    var disconnected = false
        private set

    override suspend fun connect(endpoint: LocalRoomEndpoint, token: String): Result<RoomState> = Result.success(roomState)

    override suspend fun openControlStream(endpoint: LocalRoomEndpoint, token: String): Result<Unit> {
        openedStream = true
        openControlStreamResult
            .onSuccess { controlStreamState.value = ControlStreamState.Connected }
            .onFailure { controlStreamState.value = ControlStreamState.Failed(it.message ?: "Control stream failed.") }
        return openControlStreamResult
    }

    override suspend fun send(message: RoomControlMessage): Result<Unit> = Result.success(Unit)

    override suspend fun disconnect() {
        disconnected = true
    }

    suspend fun emit(message: RoomControlMessage) {
        controlMessages.emit(message)
    }
}

private class ControlStreamFakeLocalRoomServer : LocalRoomServer {
    override val endpoint: LocalRoomEndpoint? = null
    override suspend fun start(roomState: RoomState, token: String, tracks: Map<String, String>): LocalRoomEndpoint =
        LocalRoomEndpoint("127.0.0.1", 1234, roomState.roomId)
    override suspend fun stop() = Unit
    override suspend fun broadcast(message: RoomControlMessage) = Unit
}

private class ControlStreamFakePlaybackController : PlaybackController {
    override val snapshots = MutableStateFlow(PlaybackSnapshot())
    var lastUri: String? = null
    var lastHeaders: Map<String, String> = emptyMap()

    override fun play(trackId: String, uri: String, positionMs: Long, requestHeaders: Map<String, String>) {
        lastUri = uri
        lastHeaders = requestHeaders
        snapshots.value = PlaybackSnapshot(PlaybackState.PLAYING, trackId, positionMs)
    }

    override fun pause() {
        snapshots.value = snapshots.value.copy(state = PlaybackState.PAUSED)
    }

    override fun seekTo(positionMs: Long) {
        snapshots.value = snapshots.value.copy(positionMs = positionMs)
    }

    override fun stop() {
        snapshots.value = PlaybackSnapshot()
    }
}
