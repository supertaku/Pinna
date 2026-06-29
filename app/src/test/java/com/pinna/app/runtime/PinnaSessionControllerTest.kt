package com.pinna.app.runtime

import com.pinna.app.core.model.PlaybackState
import com.pinna.app.core.model.Track
import com.pinna.app.connectivity.LocalHotspotCoordinator
import com.pinna.app.connectivity.LocalHotspotSession
import com.pinna.app.connectivity.LocalHotspotState
import com.pinna.app.library.ImportedTrackCandidate
import com.pinna.app.library.RemoteTrackImporter
import com.pinna.app.library.TrackImporter
import com.pinna.app.library.TrackLibraryRepository
import com.pinna.app.network.LocalRoomClient
import com.pinna.app.network.LocalRoomEndpoint
import com.pinna.app.network.LocalRoomServer
import com.pinna.app.network.ControlStreamState
import com.pinna.app.playback.PlaybackController
import com.pinna.app.playback.PlaybackSnapshot
import com.pinna.app.protocol.RoomControlMessage
import com.pinna.app.qr.QrJoinPayloadCodec
import com.pinna.app.qr.RoomJoinPayload
import com.pinna.app.room.RoomState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinnaSessionControllerTest {
    private val controllers = mutableListOf<PinnaSessionController>()
    private val track = Track(
        id = "track-1",
        title = "Track",
        artist = null,
        durationMs = 1000,
        mimeType = "audio/mpeg",
        localUri = "C:/tmp/track.mp3",
        sizeBytes = 5,
    )

    @Test
    fun addImportedTracksUpdatesHostSetupState() {
        val controller = newController()

        controller.addImportedTracks(listOf(track))

        assertEquals(listOf(track), controller.state.value.importedTracks)
        assertTrue(controller.state.value.canCreateRoom)
    }

    @Test
    fun loadPersistedTracksHydratesImportedTracks() = runBlocking {
        val repository = FakeTrackLibraryRepository(initialTracks = listOf(track))
        val controller = newController(repository = repository)

        controller.loadPersistedTracks()

        assertEquals(listOf(track), controller.state.value.importedTracks)
    }

    @Test
    fun importedTracksAreSavedToRepository() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val repository = FakeTrackLibraryRepository()
        val importer = FakeTrackImporter(Result.success(track))
        val controller = newController(
            importer = importer,
            repository = repository,
            scope = scope,
        )

        controller.importTracks(listOf(ImportedTrackCandidate(displayName = "Track")))
        scope.runCurrent()

        assertEquals(listOf(track), controller.state.value.importedTracks)
        assertEquals(listOf(track), repository.savedTracks)
    }

    @Test
    fun repositorySaveFailureCleansCopiedTrackAndClearsBusy() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val cleanedTracks = mutableListOf<Track>()
        val repository = FakeTrackLibraryRepository(saveFailure = IllegalStateException("database unavailable"))
        val importer = FakeTrackImporter(Result.success(track))
        val controller = newController(
            importer = importer,
            repository = repository,
            scope = scope,
            importedTrackCleaner = { cleanedTracks += it },
        )

        controller.importTracks(listOf(ImportedTrackCandidate(displayName = "Track")))
        scope.runCurrent()

        assertEquals(emptyList<Track>(), controller.state.value.importedTracks)
        assertEquals(listOf(track), cleanedTracks)
        assertFalse(controller.state.value.isBusy)
        assertEquals("database unavailable", controller.state.value.errorMessage)
    }

    @Test
    fun importFromUrlAddsAndSavesTrack() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val repository = FakeTrackLibraryRepository()
        val remote = FakeRemoteTrackImporter(Result.success(track))
        val controller = newController(remoteImporter = remote, repository = repository, scope = scope)

        controller.importFromUrl("https://youtu.be/dQw4w9WgXcQ")
        scope.runCurrent()

        assertEquals(listOf(track), controller.state.value.importedTracks)
        assertEquals(listOf(track), repository.savedTracks)
        assertFalse(controller.state.value.isBusy)
    }

    @Test
    fun importFromUrlFailureShowsErrorAndDoesNotAdd() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val remote = FakeRemoteTrackImporter(Result.failure(IllegalStateException("No audio found")))
        val controller = newController(remoteImporter = remote, scope = scope)

        controller.importFromUrl("https://youtu.be/dQw4w9WgXcQ")
        scope.runCurrent()

        assertEquals(emptyList<Track>(), controller.state.value.importedTracks)
        assertEquals("No audio found", controller.state.value.errorMessage)
        assertFalse(controller.state.value.isBusy)
    }

    @Test
    fun importFromUrlWithoutImporterShowsError() = runBlocking {
        val controller = newController()

        controller.importFromUrl("https://youtu.be/dQw4w9WgXcQ")

        assertEquals("Link import is not available on this device.", controller.state.value.errorMessage)
    }

    @Test
    fun createRoomWithoutTracksReturnsError() = runBlocking {
        val controller = newController()

        controller.createRoom()

        assertEquals("Add music before creating a room.", controller.state.value.errorMessage)
        assertEquals(PinnaScreen.HostSetup, controller.state.value.screen)
    }

    @Test
    fun createRoomWithTracksStartsServerAndEmitsPayload() = runBlocking {
        val server = FakeLocalRoomServer()
        val controller = newController(server = server)
        controller.addImportedTracks(listOf(track))

        controller.createRoom()

        assertTrue(server.started)
        assertEquals(PinnaScreen.HostRoom, controller.state.value.screen)
        assertNotNull(controller.state.value.hostPayload)
        assertEquals("127.0.0.1", controller.state.value.hostEndpoint!!.host)
    }

    @Test
    fun createRoomWithHotspotStartsHotspotBeforeServerAndEmitsHotspotState() = runBlocking {
        val events = mutableListOf<String>()
        val server = FakeLocalRoomServer(endpointHost = "192.168.43.1", onStart = { events += "server" })
        val hotspot = FakeLocalHotspotCoordinator(onStart = { events += "hotspot" })
        val controller = newController(server = server, hotspot = hotspot)
        controller.addImportedTracks(listOf(track))

        controller.createRoom(useHotspot = true)

        assertEquals(listOf("hotspot", "server"), events)
        assertEquals(PinnaScreen.HostRoom, controller.state.value.screen)
        assertEquals("192.168.43.1", controller.state.value.hostEndpoint!!.host)
        assertEquals(LocalHotspotState.Active(hotspot.session), controller.state.value.hotspotState)
        assertEquals(hotspot.session, controller.state.value.hotspotSession)
    }

    @Test
    fun createRoomWithHotspotFailureDoesNotStartServer() = runBlocking {
        val server = FakeLocalRoomServer()
        val hotspot = FakeLocalHotspotCoordinator(startResult = Result.failure(IllegalStateException("hotspot unavailable")))
        val controller = newController(server = server, hotspot = hotspot)
        controller.addImportedTracks(listOf(track))

        controller.createRoom(useHotspot = true)

        assertFalse(server.started)
        assertEquals(PinnaScreen.HostSetup, controller.state.value.screen)
        assertEquals("Could not start hotspot: hotspot unavailable", controller.state.value.errorMessage)
        assertEquals(LocalHotspotState.Failed("hotspot unavailable"), controller.state.value.hotspotState)
    }

    @Test
    fun createRoomServerFailureReturnsUiError() = runBlocking {
        val server = FakeLocalRoomServer(startFailure = IllegalStateException("bind failed"))
        val controller = newController(server = server)
        controller.addImportedTracks(listOf(track))

        controller.createRoom()

        assertEquals(PinnaScreen.HostSetup, controller.state.value.screen)
        assertEquals("Could not start room: bind failed", controller.state.value.errorMessage)
        assertEquals(null, controller.state.value.hostEndpoint)
    }

    @Test
    fun endRoomStopsServerAndClearsActiveRoom() = runBlocking {
        val server = FakeLocalRoomServer()
        val controller = newController(server = server)
        controller.addImportedTracks(listOf(track))
        controller.createRoom()

        controller.endRoom()

        assertTrue(server.stopped)
        assertEquals(null, controller.state.value.hostEndpoint)
        assertEquals(null, controller.state.value.hostPayload)
        assertEquals(PinnaScreen.Home, controller.state.value.screen)
    }

    @Test
    fun endRoomStopsActiveHotspot() = runBlocking {
        val hotspot = FakeLocalHotspotCoordinator()
        val controller = newController(hotspot = hotspot)
        controller.addImportedTracks(listOf(track))
        controller.createRoom(useHotspot = true)

        controller.endRoom()

        assertTrue(hotspot.stopped)
        assertEquals(null, controller.state.value.hotspotSession)
        assertEquals(LocalHotspotState.Stopped, controller.state.value.hotspotState)
    }

    @Test
    fun shutdownStopsRuntimeResourcesAndClearsState() = runBlocking {
        val server = FakeLocalRoomServer()
        val client = FakeLocalRoomClient(RoomState(roomId = "room-1", hostDeviceId = "host-1"))
        val playback = FakePlaybackController()
        val controller = newController(server = server, client = client, playback = playback)
        controller.addImportedTracks(listOf(track))
        controller.createRoom()
        controller.playPause()

        controller.shutdown()

        assertTrue(server.stopped)
        assertTrue(client.disconnected)
        assertEquals(PlaybackState.IDLE, playback.snapshots.value.state)
        assertEquals(null, controller.state.value.hostEndpoint)
        assertEquals(null, controller.state.value.hostRoomState)
        assertEquals(PinnaScreen.Home, controller.state.value.screen)
    }

    @Test
    fun shutdownStopsActiveHotspot() = runBlocking {
        val hotspot = FakeLocalHotspotCoordinator()
        val controller = newController(hotspot = hotspot)
        controller.addImportedTracks(listOf(track))
        controller.createRoom(useHotspot = true)

        controller.shutdown()

        assertTrue(hotspot.stopped)
        assertEquals(null, controller.state.value.hotspotSession)
        assertEquals(LocalHotspotState.Stopped, controller.state.value.hotspotState)
    }


    @Test
    fun joinInvalidPayloadReturnsUiError() = runBlocking {
        val controller = newController()

        controller.joinRoom("not-a-pinna-payload")

        assertEquals("QR code is not a Pinna room.", controller.state.value.errorMessage)
        assertEquals(PinnaScreen.Scanner, controller.state.value.screen)
    }

    @Test
    fun joinNonLocalHostPayloadIsRejected() = runBlocking {
        val client = FakeLocalRoomClient(RoomState(roomId = "room-1", hostDeviceId = "host-1"))
        val controller = newController(client = client)
        val payload = QrJoinPayloadCodec.encode(
            RoomJoinPayload(
                version = 1,
                roomId = "room-1",
                host = "8.8.8.8",
                port = 1234,
                token = "token",
                expiresAtEpochMillis = 2_000,
                fingerprint = "fp",
            ),
        )

        controller.joinRoom(payload, nowEpochMillis = 1_000)

        assertEquals("This room is not on your local network.", controller.state.value.errorMessage)
        assertEquals(PinnaScreen.Scanner, controller.state.value.screen)
        assertEquals(null, controller.state.value.listenerRoomState)
    }

    @Test
    fun joinValidPayloadFetchesRoomState() = runBlocking {
        val client = FakeLocalRoomClient(RoomState(roomId = "room-1", hostDeviceId = "host-1", queue = listOf(track), currentTrackId = "track-1"))
        val controller = newController(client = client)
        val payload = QrJoinPayloadCodec.encode(
            RoomJoinPayload(
                version = 1,
                roomId = "room-1",
                host = "127.0.0.1",
                port = 1234,
                token = "token",
                expiresAtEpochMillis = 2_000,
                fingerprint = "fp",
            ),
        )

        controller.joinRoom(payload, nowEpochMillis = 1_000)

        assertEquals(PinnaScreen.ListenerRoom, controller.state.value.screen)
        assertEquals("room-1", controller.state.value.listenerRoomState!!.roomId)
    }

    @Test
    fun leaveRoomClearsListenerEndpoint() = runBlocking {
        val client = FakeLocalRoomClient(RoomState(roomId = "room-1", hostDeviceId = "host-1", queue = listOf(track), currentTrackId = "track-1"))
        val controller = newController(client = client)
        val payload = QrJoinPayloadCodec.encode(
            RoomJoinPayload(
                version = 1,
                roomId = "room-1",
                host = "127.0.0.1",
                port = 1234,
                token = "token",
                expiresAtEpochMillis = 2_000,
                fingerprint = "fp",
            ),
        )
        controller.joinRoom(payload, nowEpochMillis = 1_000)

        controller.leaveRoom()

        assertEquals(null, controller.state.value.hostEndpoint)
        assertEquals(null, controller.state.value.listenerRoomState)
        assertEquals(PinnaScreen.Home, controller.state.value.screen)
    }

    @Test
    fun joinPlayingRoomStartsListenerPlayback() = runBlocking {
        val playback = FakePlaybackController()
        val room = RoomState(
            roomId = "room-1",
            hostDeviceId = "host-1",
            queue = listOf(track),
            currentTrackId = "track-1",
            playback = PlaybackState.PLAYING,
            hostPositionMs = 500,
            effectiveAtHostTimeNanos = System.nanoTime(),
            sequenceNumber = 1,
        )
        val controller = newController(
            client = FakeLocalRoomClient(room),
            playback = playback,
        )
        val payload = QrJoinPayloadCodec.encode(
            RoomJoinPayload(
                version = 1,
                roomId = "room-1",
                host = "127.0.0.1",
                port = 1234,
                token = "token",
                expiresAtEpochMillis = 2_000,
                fingerprint = "fp",
            ),
        )

        controller.joinRoom(payload, nowEpochMillis = 1_000)

        assertEquals(PlaybackState.PLAYING, playback.snapshots.value.state)
        assertEquals("track-1", playback.snapshots.value.trackId)
        assertEquals("http://127.0.0.1:1234/media/track-1", playback.lastUri)
        assertEquals(mapOf("Authorization" to "Bearer token"), playback.lastHeaders)
    }

    private fun newController(
        server: FakeLocalRoomServer = FakeLocalRoomServer(),
        client: FakeLocalRoomClient = FakeLocalRoomClient(RoomState(roomId = "room-1", hostDeviceId = "host-1")),
        playback: PlaybackController = FakePlaybackController(),
        importer: TrackImporter? = null,
        remoteImporter: RemoteTrackImporter? = null,
        repository: TrackLibraryRepository? = null,
        importedTrackCleaner: (Track) -> Unit = {},
        hotspot: LocalHotspotCoordinator? = null,
        scope: kotlinx.coroutines.CoroutineScope? = null,
    ): PinnaSessionController = PinnaSessionController(
        server = server,
        client = client,
        playback = playback,
        importer = importer,
        remoteImporter = remoteImporter,
        trackRepository = repository,
        importedTrackCleaner = importedTrackCleaner,
        hotspotCoordinator = hotspot,
        hostName = "host-1",
        scope = scope ?: kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
    ).also { controllers += it }

    @After
    fun tearDown() = runBlocking {
        controllers.forEach { it.shutdown() }
        controllers.clear()
    }
}

private class FakeTrackImporter(private val result: Result<Track>) : TrackImporter {
    override suspend fun import(candidate: ImportedTrackCandidate): Result<Track> = result
}

private class FakeRemoteTrackImporter(private val result: Result<Track>) : RemoteTrackImporter {
    override suspend fun importFromUrl(url: String): Result<Track> = result
}

private class FakeTrackLibraryRepository(
    initialTracks: List<Track> = emptyList(),
    private val saveFailure: Throwable? = null,
) : TrackLibraryRepository {
    private val tracks = initialTracks.toMutableList()
    val savedTracks = mutableListOf<Track>()

    override suspend fun loadTracks(): List<Track> = tracks.toList()

    override suspend fun saveTrack(track: Track, nowEpochMillis: Long): Track {
        saveFailure?.let { throw it }
        tracks.removeAll { it.id == track.id }
        tracks += track
        savedTracks += track
        return track
    }

    override suspend fun deleteTrack(id: String): Boolean {
        val removed = tracks.removeAll { it.id == id }
        return removed
    }
}

private class FakeLocalRoomServer(
    private val startFailure: Throwable? = null,
    private val endpointHost: String = "127.0.0.1",
    private val onStart: () -> Unit = {},
) : LocalRoomServer {
    override var endpoint: LocalRoomEndpoint? = null
        private set
    var started = false
    var stopped = false

    override suspend fun start(roomState: RoomState, token: String, tracks: Map<String, String>): LocalRoomEndpoint {
        startFailure?.let { throw it }
        onStart()
        started = true
        endpoint = LocalRoomEndpoint(endpointHost, 1234, roomState.roomId)
        return endpoint!!
    }

    override suspend fun stop() {
        stopped = true
        endpoint = null
    }

    override suspend fun broadcast(message: RoomControlMessage) = Unit
}

private class FakeLocalHotspotCoordinator(
    val session: LocalHotspotSession = LocalHotspotSession(ssid = "Pinna", passphrase = "secret-pass"),
    private val startResult: Result<LocalHotspotSession> = Result.success(session),
    private val onStart: () -> Unit = {},
) : LocalHotspotCoordinator {
    private val _state = MutableStateFlow<LocalHotspotState>(LocalHotspotState.Stopped)
    override val state = _state
    var stopped = false
        private set

    override suspend fun start(): Result<LocalHotspotSession> {
        onStart()
        startResult
            .onSuccess { _state.value = LocalHotspotState.Active(it) }
            .onFailure { _state.value = LocalHotspotState.Failed(it.message ?: "Local hotspot failed.") }
        return startResult
    }

    override suspend fun stop() {
        stopped = true
        _state.value = LocalHotspotState.Stopped
    }
}

class FakeLocalRoomClient(
    private val roomState: RoomState,
    private val openControlStreamResult: Result<Unit> = Result.success(Unit),
) : LocalRoomClient {
    override val controlMessages = MutableSharedFlow<RoomControlMessage>(extraBufferCapacity = 16)
    override val controlStreamState = MutableStateFlow<ControlStreamState>(ControlStreamState.Disconnected)
    var disconnected = false
        private set
    var openControlStreamCalls = 0
        private set

    override suspend fun connect(endpoint: LocalRoomEndpoint, token: String): Result<RoomState> = Result.success(roomState)
    override suspend fun openControlStream(endpoint: LocalRoomEndpoint, token: String): Result<Unit> {
        openControlStreamCalls += 1
        openControlStreamResult
            .onSuccess { controlStreamState.value = ControlStreamState.Connected }
            .onFailure { controlStreamState.value = ControlStreamState.Failed(it.message ?: "Control stream failed.") }
        return openControlStreamResult
    }
    override suspend fun send(message: RoomControlMessage): Result<Unit> = Result.success(Unit)
    override suspend fun disconnect() {
        disconnected = true
        controlStreamState.value = ControlStreamState.Disconnected
    }
}

class FakePlaybackController : PlaybackController {
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
