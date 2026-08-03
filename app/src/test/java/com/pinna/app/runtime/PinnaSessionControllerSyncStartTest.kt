@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.pinna.app.runtime

import com.pinna.app.core.model.PlaybackState
import com.pinna.app.core.model.Track
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinnaSessionControllerSyncStartTest {
    private val controllers = mutableListOf<PinnaSessionController>()
    private val endpoint = LocalRoomEndpoint("127.0.0.1", 1234, "room-1")
    private val track = Track(
        id = "track-1",
        title = "Track",
        artist = null,
        durationMs = 10_000,
        mimeType = "audio/mpeg",
        localUri = "http://127.0.0.1:1234/media/track-1",
        sizeBytes = 100,
    )
    private val secondTrack = Track(
        id = "track-2",
        title = "Next",
        artist = null,
        durationMs = 10_000,
        mimeType = "audio/mpeg",
        localUri = "http://127.0.0.1:1234/media/track-2",
        sizeBytes = 100,
    )

    @Test
    fun listenerFuturePlayDoesNotCallPlaybackImmediately() = runBlocking {
        val scope = TestScope(StandardTestDispatcher())
        val client = SyncStartFakeClient(room(playback = PlaybackState.PAUSED, sequenceNumber = 1))
        val playback = SyncStartFakePlaybackController()
        val controller = newController(client = client, playback = playback, scope = scope)
        controller.joinRoom(payload(), nowEpochMillis = 1_000)
        scope.runCurrent()

        client.emit(
            RoomControlMessage.Play(
                trackId = "track-1",
                positionMs = 400,
                effectiveAtHostTimeNanos = System.nanoTime() + 5_000_000_000,
                sequenceNumber = 2,
            ),
        )
        scope.runCurrent()

        assertTrue(playback.playCalls.isEmpty())
    }

    @Test
    fun scheduledPlayCallsPlaybackAfterEffectiveTime() = runBlocking {
        val scope = TestScope(StandardTestDispatcher())
        val client = SyncStartFakeClient(room(playback = PlaybackState.PAUSED, sequenceNumber = 1))
        val playback = SyncStartFakePlaybackController()
        val controller = newController(client = client, playback = playback, scope = scope)
        controller.joinRoom(payload(), nowEpochMillis = 1_000)
        scope.runCurrent()

        client.emit(
            RoomControlMessage.Play(
                trackId = "track-1",
                positionMs = 400,
                effectiveAtHostTimeNanos = System.nanoTime() + 5_000_000_000,
                sequenceNumber = 2,
            ),
        )
        scope.runCurrent()
        assertTrue(playback.playCalls.isEmpty())

        scope.testScheduler.advanceTimeBy(10_000)
        scope.runCurrent()

        assertEquals(listOf(PlayCall("track-1", "http://127.0.0.1:1234/media/track-1", 400)), playback.playCalls)
    }

    @Test
    fun leaveCancelsPendingPlay() = runBlocking {
        val scope = TestScope(StandardTestDispatcher())
        val client = SyncStartFakeClient(room(playback = PlaybackState.PAUSED, sequenceNumber = 1))
        val playback = SyncStartFakePlaybackController()
        val controller = newController(client = client, playback = playback, scope = scope)
        controller.joinRoom(payload(), nowEpochMillis = 1_000)
        scope.runCurrent()

        client.emit(
            RoomControlMessage.Play(
                trackId = "track-1",
                positionMs = 400,
                effectiveAtHostTimeNanos = System.nanoTime() + 5_000_000_000,
                sequenceNumber = 2,
            ),
        )
        scope.runCurrent()
        controller.leaveRoom()
        scope.runCurrent()
        scope.testScheduler.advanceTimeBy(10_000)
        scope.runCurrent()

        assertTrue(playback.playCalls.isEmpty())
    }

    @Test
    fun newerPlayCancelsOlderPendingPlay() = runBlocking {
        val scope = TestScope(StandardTestDispatcher())
        val client = SyncStartFakeClient(room(queue = listOf(track, secondTrack), playback = PlaybackState.PAUSED, sequenceNumber = 1))
        val playback = SyncStartFakePlaybackController()
        val controller = newController(client = client, playback = playback, scope = scope)
        controller.joinRoom(payload(), nowEpochMillis = 1_000)
        scope.runCurrent()

        client.emit(
            RoomControlMessage.Play(
                trackId = "track-1",
                positionMs = 100,
                effectiveAtHostTimeNanos = System.nanoTime() + 10_000_000_000,
                sequenceNumber = 2,
            ),
        )
        scope.runCurrent()
        client.emit(
            RoomControlMessage.Play(
                trackId = "track-2",
                positionMs = 900,
                effectiveAtHostTimeNanos = System.nanoTime(),
                sequenceNumber = 3,
            ),
        )
        scope.runCurrent()
        scope.testScheduler.advanceTimeBy(20_000)
        scope.runCurrent()

        assertEquals(listOf(PlayCall("track-2", "http://127.0.0.1:1234/media/track-2", 900)), playback.playCalls)
    }

    @Test
    fun pauseCancelsPendingPlay() = runBlocking {
        val scope = TestScope(StandardTestDispatcher())
        val client = SyncStartFakeClient(room(playback = PlaybackState.PAUSED, sequenceNumber = 1))
        val playback = SyncStartFakePlaybackController()
        val controller = newController(client = client, playback = playback, scope = scope)
        controller.joinRoom(payload(), nowEpochMillis = 1_000)
        scope.runCurrent()

        client.emit(
            RoomControlMessage.Play(
                trackId = "track-1",
                positionMs = 400,
                effectiveAtHostTimeNanos = System.nanoTime() + 5_000_000_000,
                sequenceNumber = 2,
            ),
        )
        scope.runCurrent()
        client.emit(RoomControlMessage.Pause(positionMs = 450, effectiveAtHostTimeNanos = System.nanoTime(), sequenceNumber = 3))
        scope.runCurrent()
        scope.testScheduler.advanceTimeBy(10_000)
        scope.runCurrent()

        assertTrue(playback.playCalls.isEmpty())
    }

    @Test
    fun seekCancelsPendingPlay() = runBlocking {
        val scope = TestScope(StandardTestDispatcher())
        val client = SyncStartFakeClient(room(playback = PlaybackState.PAUSED, sequenceNumber = 1))
        val playback = SyncStartFakePlaybackController()
        val controller = newController(client = client, playback = playback, scope = scope)
        controller.joinRoom(payload(), nowEpochMillis = 1_000)
        scope.runCurrent()

        client.emit(
            RoomControlMessage.Play(
                trackId = "track-1",
                positionMs = 400,
                effectiveAtHostTimeNanos = System.nanoTime() + 5_000_000_000,
                sequenceNumber = 2,
            ),
        )
        scope.runCurrent()
        client.emit(
            RoomControlMessage.Seek(
                positionMs = 900,
                effectiveAtHostTimeNanos = System.nanoTime() + 5_000_000_000,
                sequenceNumber = 3,
            ),
        )
        scope.runCurrent()
        scope.testScheduler.advanceTimeBy(10_000)
        scope.runCurrent()

        assertTrue(playback.playCalls.isEmpty())
        assertEquals(900, playback.snapshots.value.positionMs)
    }

    @Test
    fun queueUpdateCancelsOlderPendingPlayWhenStartingReplacementTrack() = runBlocking {
        val scope = TestScope(StandardTestDispatcher())
        val client = SyncStartFakeClient(room(queue = listOf(track, secondTrack), playback = PlaybackState.PAUSED, sequenceNumber = 1))
        val playback = SyncStartFakePlaybackController()
        val controller = newController(client = client, playback = playback, scope = scope)
        controller.joinRoom(payload(), nowEpochMillis = 1_000)
        scope.runCurrent()

        client.emit(
            RoomControlMessage.Play(
                trackId = "track-1",
                positionMs = 100,
                effectiveAtHostTimeNanos = System.nanoTime() + 5_000_000_000,
                sequenceNumber = 2,
            ),
        )
        scope.runCurrent()
        client.emit(RoomControlMessage.QueueUpdate(listOf(secondTrack), sequenceNumber = 3))
        scope.runCurrent()
        scope.testScheduler.advanceTimeBy(10_000)
        scope.runCurrent()

        assertEquals(listOf(PlayCall("track-2", "http://127.0.0.1:1234/media/track-2", 100)), playback.playCalls)
    }

    @Test
    fun shutdownCancelsPendingPlay() = runBlocking {
        val scope = TestScope(StandardTestDispatcher())
        val client = SyncStartFakeClient(room(playback = PlaybackState.PAUSED, sequenceNumber = 1))
        val playback = SyncStartFakePlaybackController()
        val controller = newController(client = client, playback = playback, scope = scope)
        controller.joinRoom(payload(), nowEpochMillis = 1_000)
        scope.runCurrent()

        client.emit(
            RoomControlMessage.Play(
                trackId = "track-1",
                positionMs = 400,
                effectiveAtHostTimeNanos = System.nanoTime() + 5_000_000_000,
                sequenceNumber = 2,
            ),
        )
        scope.runCurrent()
        controller.shutdown()
        scope.runCurrent()
        scope.testScheduler.advanceTimeBy(10_000)
        scope.runCurrent()

        assertTrue(playback.playCalls.isEmpty())
    }

    @Test
    fun reconnectPausedSnapshotCancelsPendingPlayJob() = runBlocking {
        val scope = TestScope(StandardTestDispatcher())
        val client = SyncStartFakeClient(room(playback = PlaybackState.PAUSED, sequenceNumber = 1))
        val playback = SyncStartFakePlaybackController()
        val controller = newController(
            client = client,
            playback = playback,
            scope = scope,
            reconnectBaseMs = 1,
        )
        controller.joinRoom(payload(), nowEpochMillis = 1_000)
        scope.runCurrent()
        val baselineActiveJobs = activeChildJobCount(scope)

        client.emit(
            RoomControlMessage.Play(
                trackId = "track-1",
                positionMs = 400,
                effectiveAtHostTimeNanos = System.nanoTime() + 5_000_000_000,
                sequenceNumber = 2,
            ),
        )
        scope.runCurrent()
        assertEquals(baselineActiveJobs + 1, activeChildJobCount(scope))

        client.updateRoom(room(playback = PlaybackState.PAUSED, sequenceNumber = 3))
        client.controlStreamState.value = ControlStreamState.Disconnected
        scope.runCurrent()
        scope.testScheduler.advanceTimeBy(2)
        scope.runCurrent()

        assertEquals(baselineActiveJobs, activeChildJobCount(scope))
        scope.testScheduler.advanceTimeBy(10_000)
        scope.runCurrent()
        assertTrue(playback.playCalls.isEmpty())
    }

    @Test
    fun leaveCancelsPendingNudgeReset() = runBlocking {
        val scope = TestScope(StandardTestDispatcher())
        val client = SyncStartFakeClient(
            room(
                playback = PlaybackState.PLAYING,
                sequenceNumber = 1,
                effectiveAtHostTimeNanos = System.nanoTime() + 60_000_000_000,
            ),
        )
        val playback = SyncStartFakePlaybackController()
        val controller = newController(client = client, playback = playback, scope = scope)
        controller.joinRoom(payload(), nowEpochMillis = 1_000)
        scope.runCurrent()
        playback.snapshots.value = PlaybackSnapshot(PlaybackState.PLAYING, trackId = "track-1", positionMs = 50)

        val now = System.nanoTime()
        client.emit(
            RoomControlMessage.SyncSample(
                t1ClientNanos = now,
                t2HostNanos = now,
                t3HostNanos = now,
            ),
        )
        scope.runCurrent()
        controller.leaveRoom()
        scope.runCurrent()
        scope.testScheduler.advanceTimeBy(1_500)
        scope.runCurrent()

        assertEquals(listOf(0.98f, 1.0f), playback.speedCalls)
    }

    private fun newController(
        client: LocalRoomClient,
        playback: PlaybackController,
        scope: TestScope,
        reconnectBaseMs: Long = PinnaSessionController.RECONNECT_BASE_MS,
    ): PinnaSessionController = PinnaSessionController(
        server = SyncStartFakeServer(),
        client = client,
        playback = playback,
        hostName = "host-1",
        reconnectBaseMs = reconnectBaseMs,
        scope = scope,
    ).also { controllers += it }

    private fun room(
        queue: List<Track> = listOf(track),
        playback: PlaybackState,
        sequenceNumber: Long,
        hostPositionMs: Long = 0,
        effectiveAtHostTimeNanos: Long = System.nanoTime(),
    ) = RoomState(
        roomId = "room-1",
        hostDeviceId = "host-1",
        queue = queue,
        currentTrackId = queue.firstOrNull()?.id,
        playback = playback,
        hostPositionMs = hostPositionMs,
        effectiveAtHostTimeNanos = effectiveAtHostTimeNanos,
        sequenceNumber = sequenceNumber,
    )

    private fun payload(): String = QrJoinPayloadCodec.encode(
        RoomJoinPayload(
            version = 1,
            roomId = endpoint.roomId,
            host = endpoint.host,
            port = endpoint.port,
            token = "token",
            expiresAtEpochMillis = 2_000,
            fingerprint = "local-room",
        ),
    )

    @After
    fun tearDown() = runBlocking {
        controllers.forEach { it.shutdown() }
        controllers.clear()
    }
}

private fun activeChildJobCount(scope: TestScope): Int =
    scope.coroutineContext[Job]?.children?.count { it.isActive } ?: 0

private data class PlayCall(
    val trackId: String,
    val uri: String,
    val positionMs: Long,
)

private class SyncStartFakeClient(
    private var roomState: RoomState,
) : LocalRoomClient {
    override val controlMessages = MutableSharedFlow<RoomControlMessage>(extraBufferCapacity = 16)
    override val controlStreamState = MutableStateFlow<ControlStreamState>(ControlStreamState.Disconnected)

    override suspend fun connect(endpoint: LocalRoomEndpoint, token: String): Result<RoomState> =
        Result.success(roomState)

    override suspend fun openControlStream(endpoint: LocalRoomEndpoint, token: String): Result<Unit> {
        controlStreamState.value = ControlStreamState.Connected
        return Result.success(Unit)
    }

    override suspend fun send(message: RoomControlMessage): Result<Unit> = Result.success(Unit)

    override suspend fun disconnect() {
        controlStreamState.value = ControlStreamState.Disconnected
    }

    suspend fun emit(message: RoomControlMessage) {
        controlMessages.emit(message)
    }

    fun updateRoom(room: RoomState) {
        roomState = room
    }
}

private class SyncStartFakeServer : LocalRoomServer {
    override val endpoint: LocalRoomEndpoint? = null
    override suspend fun start(roomState: RoomState, token: String, tracks: Map<String, String>): LocalRoomEndpoint =
        LocalRoomEndpoint("127.0.0.1", 1234, roomState.roomId)

    override suspend fun stop() = Unit
    override suspend fun broadcast(message: RoomControlMessage) = Unit
}

private class SyncStartFakePlaybackController : PlaybackController {
    override val snapshots = MutableStateFlow(PlaybackSnapshot())
    val playCalls = mutableListOf<PlayCall>()
    val speedCalls = mutableListOf<Float>()

    override fun play(trackId: String, uri: String, positionMs: Long, requestHeaders: Map<String, String>) {
        playCalls += PlayCall(trackId, uri, positionMs)
        snapshots.value = PlaybackSnapshot(PlaybackState.PLAYING, trackId, positionMs)
    }

    override fun pause() {
        snapshots.value = snapshots.value.copy(state = PlaybackState.PAUSED)
    }

    override fun resume() {
        snapshots.value = snapshots.value.copy(state = PlaybackState.PLAYING)
    }

    override fun seekTo(positionMs: Long) {
        snapshots.value = snapshots.value.copy(positionMs = positionMs)
    }

    override fun stop() {
        snapshots.value = PlaybackSnapshot()
    }

    override fun setPlaybackSpeed(speed: Float) {
        speedCalls += speed
    }
}
