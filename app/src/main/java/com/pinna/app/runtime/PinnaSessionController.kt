package com.pinna.app.runtime

import com.pinna.app.core.model.PlaybackState
import com.pinna.app.core.model.Track
import com.pinna.app.core.model.toNetworkVisibleTrack
import com.pinna.app.connectivity.LocalAddressValidator
import com.pinna.app.connectivity.LocalHotspotCoordinator
import com.pinna.app.connectivity.LocalHotspotSession
import com.pinna.app.connectivity.LocalHotspotState
import com.pinna.app.network.ControlStreamState
import com.pinna.app.library.ImportedTrackCandidate
import com.pinna.app.library.RemoteTrackImporter
import com.pinna.app.library.TrackImporter
import com.pinna.app.library.TrackLibraryRepository
import com.pinna.app.network.HttpLocalRoomClient
import com.pinna.app.network.HttpLocalRoomServer
import com.pinna.app.network.LocalRoomClient
import com.pinna.app.network.LocalRoomEndpoint
import com.pinna.app.network.LocalRoomServer
import com.pinna.app.network.ReconnectBackoff
import com.pinna.app.playback.PlaybackController
import com.pinna.app.protocol.RoomControlMessage
import com.pinna.app.qr.QrDecodeResult
import com.pinna.app.qr.QrJoinPayloadCodec
import com.pinna.app.qr.RoomJoinPayload
import com.pinna.app.room.RoomEvent
import com.pinna.app.room.RoomReducer
import com.pinna.app.room.RoomState
import com.pinna.app.security.RoomTokenGenerator
import com.pinna.app.sync.AudioRoute
import com.pinna.app.sync.DriftAction
import com.pinna.app.sync.ListenerSyncController
import com.pinna.app.sync.PlaybackTimeline
import com.pinna.app.sync.RouteLatencyAdvisor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID
import kotlin.math.roundToLong
import kotlin.random.Random

class PinnaSessionController(
    private val server: LocalRoomServer,
    private val client: LocalRoomClient,
    private val playback: PlaybackController,
    private val importer: TrackImporter? = null,
    private val remoteImporter: RemoteTrackImporter? = null,
    private val trackRepository: TrackLibraryRepository? = null,
    private val importedTrackCleaner: (Track) -> Unit = { track -> File(localPathFromUri(track.localUri)).delete() },
    private val hotspotCoordinator: LocalHotspotCoordinator? = null,
    private val hostName: String = "Android host",
    private val hostStartLeadNanos: Long = HOST_START_LEAD_NANOS,
    private val syncSampleIntervalMs: Long = SYNC_SAMPLE_INTERVAL_MS,
    private val audioRouteProvider: () -> AudioRoute = { AudioRoute.UNKNOWN },
    private val reconnectBaseMs: Long = RECONNECT_BASE_MS,
    private val reconnectMaxAttempts: Int = RECONNECT_MAX_ATTEMPTS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _state = MutableStateFlow(PinnaAppState())
    val state: StateFlow<PinnaAppState> = _state.asStateFlow()
    private var controlStreamJob: Job? = null
    private var streamStateJob: Job? = null
    private var syncJob: Job? = null
    private var hostStartJob: Job? = null
    private var hostRoomJob: Job? = null
    private var reconnectJob: Job? = null
    @Volatile
    private var listenerSync: ListenerSyncController? = null
    @Volatile
    private var listenerEndpoint: LocalRoomEndpoint? = null
    @Volatile
    private var listenerToken: String? = null
    private val listenerSessionId = AtomicLong(0)
    private val joinAttemptId = AtomicLong(0)

    fun show(screen: PinnaScreen) {
        _state.update { it.copy(screen = screen, errorMessage = null) }
    }

    fun addImportedTracks(tracks: List<Track>) {
        _state.update { it.copy(importedTracks = it.importedTracks + tracks, errorMessage = null) }
    }

    suspend fun loadPersistedTracks() {
        val repository = trackRepository ?: return
        runCatching { repository.loadTracks() }
            .onSuccess { tracks ->
                _state.update { it.copy(importedTracks = tracks, errorMessage = null) }
            }
            .onFailure { failure ->
                _state.update { it.copy(errorMessage = failure.message ?: "Could not load imported tracks.") }
            }
    }

    fun importTracks(candidates: List<ImportedTrackCandidate>) {
        val activeImporter = importer
        if (activeImporter == null) {
            _state.update { it.copy(errorMessage = "Importer is not available on this device.") }
            return
        }
        scope.launch {
            _state.update { it.copy(isBusy = true, errorMessage = null) }
            val imported = mutableListOf<Track>()
            val failures = mutableListOf<String>()
            try {
                candidates.forEach { candidate ->
                    activeImporter.import(candidate)
                        .onSuccess { track ->
                            persistImportedTrack(track)
                                .onSuccess { saved -> imported += saved }
                                .onFailure { failure -> failures += failure.message ?: "Could not save ${track.title}." }
                        }
                        .onFailure { failures += it.message ?: "Could not import ${candidate.displayName}" }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                failures += failure.message ?: "Could not import selected audio."
            }
            _state.update {
                it.copy(
                    importedTracks = it.importedTracks + imported,
                    isBusy = false,
                    errorMessage = failures.firstOrNull(),
                )
            }
        }
    }

    /**
     * Imports a track from a pasted link (e.g. YouTube) into private storage, reusing the same
     * persist-and-cleanup flow as local file import.
     */
    fun importFromUrl(url: String) {
        val activeImporter = remoteImporter
        if (activeImporter == null) {
            _state.update { it.copy(errorMessage = "Link import is not available on this device.") }
            return
        }
        if (url.isBlank()) {
            _state.update { it.copy(errorMessage = "Paste a link to import.") }
            return
        }
        scope.launch {
            _state.update { it.copy(isBusy = true, errorMessage = null) }
            val outcome = activeImporter.importFromUrl(url).fold(
                onSuccess = { track -> persistImportedTrack(track) },
                onFailure = { Result.failure(it) },
            )
            _state.update { app ->
                outcome.fold(
                    onSuccess = { saved ->
                        app.copy(importedTracks = app.importedTracks + saved, isBusy = false, errorMessage = null)
                    },
                    onFailure = { failure ->
                        app.copy(isBusy = false, errorMessage = failure.message ?: "Could not import from link.")
                    },
                )
            }
        }
    }

    private suspend fun persistImportedTrack(track: Track): Result<Track> =
        runCatching { trackRepository?.saveTrack(track) ?: track }
            .onFailure { importedTrackCleaner(track) }

    suspend fun createRoom(useHotspot: Boolean = false, nowEpochMillis: Long = System.currentTimeMillis()) {
        val current = state.value
        if (current.importedTracks.isEmpty()) {
            _state.update { it.copy(screen = PinnaScreen.HostSetup, errorMessage = "Add music before creating a room.") }
            return
        }

        val hotspotSession = if (useHotspot) {
            startHotspotForRoom() ?: return
        } else {
            null
        }

        val roomId = UUID.randomUUID().toString()
        val initialState = RoomState(
            roomId = roomId,
            hostDeviceId = hostName,
            queue = current.importedTracks,
            currentTrackId = current.importedTracks.firstOrNull()?.id,
        )
        val token = RoomTokenGenerator.generate(nowEpochMillis = nowEpochMillis)
        val tracks = current.importedTracks.associate { it.id to localPathFromUri(it.localUri) }
        val endpoint = runCatching { server.start(initialState, token.value, tracks) }
            .onFailure { failure ->
                if (hotspotSession != null) stopHotspot()
                _state.update {
                    it.copy(
                        screen = PinnaScreen.HostSetup,
                        hostEndpoint = null,
                        hostPayload = null,
                        hostRoomState = null,
                        errorMessage = "Could not start room: ${failure.message ?: "network unavailable"}",
                    )
                }
            }
            .getOrNull()
            ?: return
        val payload = QrJoinPayloadCodec.encode(
            RoomJoinPayload(
                version = 1,
                roomId = roomId,
                host = endpoint.host,
                port = endpoint.port,
                token = token.value,
                expiresAtEpochMillis = token.expiresAtEpochMillis,
                fingerprint = "local-room",
            ),
        )

        _state.update {
            it.copy(
                screen = PinnaScreen.HostRoom,
                hostEndpoint = endpoint,
                hostPayload = payload,
                hostRoomState = initialState,
                hotspotSession = hotspotSession ?: it.hotspotSession,
                hotspotState = hotspotCoordinator?.state?.value ?: it.hotspotState,
                errorMessage = null,
            )
        }
        startHostRoomObserver()
    }

    private fun startHostRoomObserver() {
        hostRoomJob?.cancel()
        val activeServer = server as? HttpLocalRoomServer ?: return
        hostRoomJob = scope.launch {
            activeServer.rooms.collect { serverRoom ->
                _state.update { app ->
                    val host = app.hostRoomState ?: return@update app
                    if (host.listeners == serverRoom.listeners) app
                    else app.copy(hostRoomState = host.copy(listeners = serverRoom.listeners))
                }
            }
        }
    }

    suspend fun endRoom() {
        hostStartJob?.cancel()
        hostStartJob = null
        hostRoomJob?.cancel()
        hostRoomJob = null
        server.stop()
        stopHotspot()
        playback.stop()
        _state.update {
            it.copy(
                screen = PinnaScreen.Home,
                hostEndpoint = null,
                hostPayload = null,
                hostRoomState = null,
                hotspotSession = null,
                hotspotState = hotspotCoordinator?.state?.value ?: LocalHotspotState.Stopped,
                errorMessage = null,
            )
        }
    }

    suspend fun startHotspot() {
        val session = startHotspotForRoom() ?: return
        _state.update {
            it.copy(
                hotspotSession = session,
                hotspotState = hotspotCoordinator?.state?.value ?: LocalHotspotState.Active(session),
                errorMessage = null,
            )
        }
    }

    suspend fun stopHotspot() {
        val coordinator = hotspotCoordinator ?: return
        coordinator.stop()
        _state.update {
            it.copy(
                hotspotSession = null,
                hotspotState = coordinator.state.value,
            )
        }
    }

    private suspend fun startHotspotForRoom(): LocalHotspotSession? {
        val coordinator = hotspotCoordinator
        if (coordinator == null) {
            _state.update {
                it.copy(
                    screen = PinnaScreen.HostSetup,
                    hotspotState = LocalHotspotState.Unavailable,
                    hotspotSession = null,
                    errorMessage = "Could not start hotspot: hotspot is not available on this device.",
                )
            }
            return null
        }
        _state.update { it.copy(hotspotState = LocalHotspotState.Starting, hotspotSession = null, errorMessage = null) }
        return coordinator.start()
            .onFailure { failure ->
                _state.update {
                    it.copy(
                        screen = PinnaScreen.HostSetup,
                        hotspotState = coordinator.state.value,
                        hotspotSession = null,
                        errorMessage = "Could not start hotspot: ${failure.message ?: "hotspot unavailable"}",
                    )
                }
            }
            .onSuccess { session ->
                _state.update {
                    it.copy(
                        hotspotState = coordinator.state.value,
                        hotspotSession = session,
                        errorMessage = null,
                    )
                }
            }
            .getOrNull()
    }

    suspend fun joinRoom(rawPayload: String, nowEpochMillis: Long = System.currentTimeMillis()) {
        when (val decoded = QrJoinPayloadCodec.decode(rawPayload, nowEpochMillis)) {
            QrDecodeResult.Expired -> _state.update {
                it.copy(screen = PinnaScreen.Scanner, errorMessage = "This room is no longer available.")
            }
            is QrDecodeResult.Invalid -> _state.update {
                it.copy(screen = PinnaScreen.Scanner, errorMessage = decoded.reason)
            }
            is QrDecodeResult.UnsupportedVersion -> _state.update {
                it.copy(screen = PinnaScreen.Scanner, errorMessage = "This room uses an unsupported Pinna version.")
            }
            is QrDecodeResult.Valid -> {
                val payload = decoded.payload
                if (!LocalAddressValidator.isAllowedLocalHost(payload.host)) {
                    _state.update {
                        it.copy(
                            screen = PinnaScreen.Scanner,
                            errorMessage = "This room is not on your local network.",
                        )
                    }
                    return
                }
                val endpoint = LocalRoomEndpoint(payload.host, payload.port, payload.roomId)
                val attemptId = nextJoinAttemptId()
                cancelListenerControlStream()
                client.disconnect()
                client.connect(endpoint, payload.token)
                    .onSuccess { room ->
                        if (!isActiveJoinAttempt(attemptId)) return@onSuccess
                        val openResult = ensureListenerControlStreamOpen(endpoint, payload.token)
                        if (!isActiveJoinAttempt(attemptId)) return@onSuccess
                        if (openResult.isFailure) {
                            val failure = openResult.exceptionOrNull()
                            client.disconnect()
                            playback.stop()
                            _state.update {
                                if (!isActiveJoinAttempt(attemptId)) return@update it
                                it.copy(
                                    screen = PinnaScreen.Scanner,
                                    hostEndpoint = null,
                                    listenerRoomState = null,
                                    errorMessage = failure?.message ?: "Could not open the room control stream.",
                                )
                            }
                            return@onSuccess
                        }
                        val sessionId = nextListenerSessionId()
                        val listenerRoom = room.copy(queue = room.queue.map { it.toNetworkVisibleTrack() })
                        _state.update {
                            if (!isActiveJoinAttempt(attemptId)) return@update it
                            it.copy(
                                screen = PinnaScreen.ListenerRoom,
                                hostEndpoint = endpoint,
                                listenerRoomState = listenerRoom,
                                errorMessage = null,
                            )
                        }
                        if (!isActiveJoinAttempt(attemptId)) return@onSuccess
                        startListenerControlStreamCollector(endpoint, payload.token, sessionId)
                        startListenerSync(endpoint, payload.token, sessionId)
                        maybeStartListenerPlayback(endpoint, payload.token, listenerRoom, sessionId)
                    }
                    .onFailure { failure ->
                        _state.update {
                            if (!isActiveJoinAttempt(attemptId)) return@update it
                            it.copy(
                                screen = PinnaScreen.Scanner,
                                errorMessage = failure.message ?: "Could not reach this room.",
                            )
                        }
                    }
            }
        }
    }

    private suspend fun maybeStartListenerPlayback(
        endpoint: LocalRoomEndpoint,
        token: String,
        room: RoomState,
        sessionId: Long? = null,
    ) {
        if (sessionId != null && !isActiveListenerSession(sessionId)) return
        val timeline = PlaybackTimeline.from(room) ?: return
        val track = room.queue.firstOrNull { it.id == timeline.trackId } ?: return
        val httpClient = client as? HttpLocalRoomClient
        val hostNowNanos = httpClient?.fetchHostTimeNanos()?.getOrNull() ?: System.nanoTime()
        if (sessionId != null && !isActiveListenerSession(sessionId)) return
        val mediaUri = listenerMediaUrl(endpoint, track.id)
        val headers = httpClient?.authorizationHeaders(token) ?: mapOf("Authorization" to "Bearer $token")
        val manualOffsetMs = state.value.listenerSync.manualOffsetMs
        val sync = listenerSync
        val targetPositionMs = if (sync != null && sync.isReady) {
            sync.targetPositionMs(timeline, manualOffsetMs)
        } else {
            (timeline.targetPositionMs(hostNowNanos) + manualOffsetMs).coerceAtLeast(0)
        }
        playback.play(track.id, mediaUri, targetPositionMs, headers)
    }

    private suspend fun ensureListenerControlStreamOpen(endpoint: LocalRoomEndpoint, token: String): Result<Unit> {
        if (client.controlStreamState.value !is ControlStreamState.Connected) {
            return client.openControlStream(endpoint, token)
        }
        return Result.success(Unit)
    }

    private fun startListenerControlStreamCollector(endpoint: LocalRoomEndpoint, token: String, sessionId: Long) {
        controlStreamJob = scope.launch {
            try {
                client.controlMessages.collect { message ->
                    if (isActiveListenerSession(sessionId)) {
                        handleListenerControlMessage(endpoint, token, message, sessionId)
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                _state.update { it.copy(errorMessage = failure.message ?: "Room control stream failed.") }
            }
        }
    }

    private fun cancelListenerControlStream() {
        listenerSessionId.incrementAndGet()
        controlStreamJob?.cancel()
        controlStreamJob = null
        syncJob?.cancel()
        syncJob = null
        streamStateJob?.cancel()
        streamStateJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        listenerSync = null
        listenerEndpoint = null
        listenerToken = null
    }

    private fun nextListenerSessionId(): Long {
        return listenerSessionId.incrementAndGet()
    }

    private fun isActiveListenerSession(sessionId: Long): Boolean =
        listenerSessionId.get() == sessionId

    private fun nextJoinAttemptId(): Long =
        joinAttemptId.incrementAndGet()

    private fun isActiveJoinAttempt(attemptId: Long): Boolean =
        joinAttemptId.get() == attemptId

    private suspend fun handleListenerControlMessage(
        endpoint: LocalRoomEndpoint,
        token: String,
        message: RoomControlMessage,
        sessionId: Long,
    ) {
        when (message) {
            is RoomControlMessage.Play -> reduceListenerRoom(
                sessionId,
                RoomEvent.Play(message.trackId, message.positionMs, message.effectiveAtHostTimeNanos, message.sequenceNumber),
            )?.let { maybeStartListenerPlayback(endpoint, token, it, sessionId) }

            is RoomControlMessage.Pause -> reduceListenerRoom(
                sessionId,
                RoomEvent.Pause(message.positionMs, message.effectiveAtHostTimeNanos, message.sequenceNumber),
            )
                ?.let {
                    if (isActiveListenerSession(sessionId)) {
                        playback.seekTo(it.hostPositionMs)
                        playback.pause()
                    }
                }

            is RoomControlMessage.Seek -> reduceListenerRoom(
                sessionId,
                RoomEvent.Seek(message.positionMs, message.effectiveAtHostTimeNanos, message.sequenceNumber),
            )
                ?.let {
                    val targetPositionMs = PlaybackTimeline.from(it)?.targetPositionMs(System.nanoTime()) ?: message.positionMs
                    if (isActiveListenerSession(sessionId)) playback.seekTo(targetPositionMs)
                }

            is RoomControlMessage.QueueUpdate -> {
                val previous = state.value.listenerRoomState
                val next = reduceListenerRoom(
                    sessionId,
                    RoomEvent.QueueUpdated(
                        queue = message.queue.map { it.toNetworkVisibleTrack() },
                        sequenceNumber = message.sequenceNumber,
                    ),
                )
                if (next != null && previous?.currentTrackId != next.currentTrackId) {
                    if (next.currentTrackId == null) {
                        if (isActiveListenerSession(sessionId)) playback.stop()
                    } else if (next.playback == PlaybackState.PLAYING) {
                        maybeStartListenerPlayback(endpoint, token, next, sessionId)
                    }
                }
            }

            is RoomControlMessage.Error -> _state.update {
                if (isActiveListenerSession(sessionId)) it.copy(errorMessage = message.message) else it
            }
            is RoomControlMessage.SyncSample -> {
                if (message.t2HostNanos != 0L || message.t3HostNanos != 0L) {
                    handleSyncReply(message, sessionId)
                }
            }
            is RoomControlMessage.Join,
            is RoomControlMessage.Ready,
            -> Unit
        }
    }

    private fun reduceListenerRoom(sessionId: Long, event: RoomEvent): RoomState? {
        var reduced: RoomState? = null
        _state.update { appState ->
            if (!isActiveListenerSession(sessionId)) return@update appState
            val current = appState.listenerRoomState ?: return@update appState
            val next = RoomReducer.reduce(current, event)
            if (next == current) return@update appState
            reduced = next
            appState.copy(listenerRoomState = next, errorMessage = null)
        }
        return reduced
    }

    suspend fun leaveRoom() {
        cancelListenerControlStream()
        client.disconnect()
        playback.stop()
        _state.update {
            it.copy(
                screen = PinnaScreen.Home,
                hostEndpoint = null,
                listenerRoomState = null,
                listenerSync = ListenerSyncStatus(),
                controlStreamState = ControlStreamState.Disconnected,
                errorMessage = null,
            )
        }
    }

    suspend fun shutdown() {
        cancelListenerControlStream()
        hostStartJob?.cancel()
        hostStartJob = null
        hostRoomJob?.cancel()
        hostRoomJob = null
        scope.coroutineContext.cancelChildren()
        server.stop()
        client.disconnect()
        stopHotspot()
        playback.stop()
        _state.update {
            it.copy(
                screen = PinnaScreen.Home,
                hostEndpoint = null,
                hostPayload = null,
                hostRoomState = null,
                listenerRoomState = null,
                listenerSync = ListenerSyncStatus(),
                controlStreamState = ControlStreamState.Disconnected,
                hotspotSession = null,
                hotspotState = hotspotCoordinator?.state?.value ?: LocalHotspotState.Stopped,
                errorMessage = null,
                isBusy = false,
            )
        }
    }

    suspend fun playPause() {
        val room = state.value.hostRoomState ?: return
        val track = room.queue.firstOrNull { it.id == room.currentTrackId } ?: room.queue.firstOrNull() ?: return
        val nextSequence = room.sequenceNumber + 1
        if (room.playback == PlaybackState.PLAYING) {
            val nextRoom = RoomReducer.reduce(
                room,
                RoomEvent.Pause(playback.snapshots.value.positionMs, System.nanoTime(), nextSequence),
            )
            hostStartJob?.cancel()
            playback.pause()
            server.broadcast(RoomControlMessage.Pause(nextRoom.hostPositionMs, nextRoom.effectiveAtHostTimeNanos, nextRoom.sequenceNumber))
            _state.update { it.copy(hostRoomState = nextRoom) }
        } else {
            // Schedule the start a fixed lead ahead of the host clock so every device can buffer and
            // begin on the same effective timeline.
            val effectiveAt = System.nanoTime() + hostStartLeadNanos
            val nextRoom = RoomReducer.reduce(
                room,
                RoomEvent.Play(track.id, playback.snapshots.value.positionMs, effectiveAt, nextSequence),
            )
            server.broadcast(RoomControlMessage.Play(track.id, nextRoom.hostPositionMs, nextRoom.effectiveAtHostTimeNanos, nextRoom.sequenceNumber))
            scheduleHostStart(track.id, track.localUri, nextRoom)
            _state.update { it.copy(hostRoomState = nextRoom) }
        }
    }

    fun mediaUrlFor(trackId: String): String? {
        val endpoint = state.value.hostEndpoint ?: return null
        return (client as? HttpLocalRoomClient)?.mediaUrl(endpoint, trackId)
    }

    /** Adjusts the listener's manual output offset, clamped to the calibration range. */
    fun setManualOffsetMs(offsetMs: Long) {
        val clamped = offsetMs.coerceIn(
            ListenerSyncStatus.MIN_MANUAL_OFFSET_MS,
            ListenerSyncStatus.MAX_MANUAL_OFFSET_MS,
        )
        _state.update { it.copy(listenerSync = it.listenerSync.copy(manualOffsetMs = clamped)) }
    }

    fun resetManualOffset() = setManualOffsetMs(0)

    private fun startListenerSync(endpoint: LocalRoomEndpoint, token: String, sessionId: Long) {
        listenerSync = ListenerSyncController(playback)
        listenerEndpoint = endpoint
        listenerToken = token
        _state.update {
            if (!isActiveListenerSession(sessionId)) return@update it
            it.copy(listenerSync = it.listenerSync.copy(
                quality = com.pinna.app.sync.SyncQuality.UNSTABLE,
                estimatedOffsetNanos = 0,
                roundTripNanos = null,
                driftMs = null,
                correctionCount = 0,
                routeWarning = RouteLatencyAdvisor.warning(audioRouteProvider()),
            ))
        }
        streamStateJob = scope.launch {
            var wasConnected = false
            client.controlStreamState.collect { streamState ->
                if (!isActiveListenerSession(sessionId)) return@collect
                if (streamState !is ControlStreamState.Reconnecting) {
                    _state.update { it.copy(controlStreamState = streamState) }
                }
                when (streamState) {
                    is ControlStreamState.Connected -> wasConnected = true
                    is ControlStreamState.Disconnected,
                    is ControlStreamState.Failed,
                    -> if (wasConnected) scheduleReconnect(endpoint, token, sessionId)
                    else -> Unit
                }
            }
        }
        syncJob = scope.launch {
            while (isActiveListenerSession(sessionId)) {
                client.send(
                    RoomControlMessage.SyncSample(
                        t1ClientNanos = System.nanoTime(),
                        t2HostNanos = 0,
                        t3HostNanos = 0,
                    ),
                )
                delay(syncSampleIntervalMs)
            }
        }
    }

    private fun handleSyncReply(message: RoomControlMessage.SyncSample, sessionId: Long) {
        val sync = listenerSync ?: return
        if (!isActiveListenerSession(sessionId)) return
        sync.onSampleReply(message) ?: run {
            publishSyncStatus(sessionId)
            return
        }
        val room = state.value.listenerRoomState
        val timeline = room?.let { PlaybackTimeline.from(it) }
        if (timeline != null && isActiveListenerSession(sessionId)) {
            sync.correctDrift(timeline, state.value.listenerSync.manualOffsetMs)
        }
        publishSyncStatus(sessionId)
    }

    private fun publishSyncStatus(sessionId: Long) {
        val sync = listenerSync ?: return
        _state.update {
            if (!isActiveListenerSession(sessionId)) return@update it
            it.copy(
                listenerSync = it.listenerSync.copy(
                    quality = sync.quality(),
                    estimatedOffsetNanos = sync.estimatedOffsetNanos,
                    roundTripNanos = sync.lastRoundTripNanos,
                    driftMs = sync.lastDriftMs,
                    correctionCount = sync.correctionCount,
                    bufferedPositionMs = playback.snapshots.value.bufferedPositionMs,
                    routeWarning = RouteLatencyAdvisor.warning(audioRouteProvider()),
                ),
            )
        }
    }

    /**
     * Reconnects the listener control stream after a transient drop. Retries with jittered exponential
     * backoff, re-fetches `/room`, resumes the WebSocket, and restarts playback if the host is playing.
     * Stale events are ignored by the reducer's sequence freshness. Single-flight per drop.
     */
    private fun scheduleReconnect(endpoint: LocalRoomEndpoint, token: String, sessionId: Long) {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            var attempt = 1
            while (isActiveListenerSession(sessionId) && attempt <= reconnectMaxAttempts) {
                if (client.controlStreamState.value is ControlStreamState.Connected) return@launch
                val jitter = Random.nextLong(0, (reconnectBaseMs / 2) + 1)
                val delayMs = ReconnectBackoff.delayMs(attempt, baseMs = reconnectBaseMs) + jitter
                _state.update {
                    if (isActiveListenerSession(sessionId)) {
                        it.copy(controlStreamState = ControlStreamState.Reconnecting(attempt, delayMs))
                    } else {
                        it
                    }
                }
                delay(delayMs)
                if (!isActiveListenerSession(sessionId)) return@launch
                val room = client.connect(endpoint, token).getOrNull()
                if (room != null && isActiveListenerSession(sessionId)) {
                    val listenerRoom = room.copy(queue = room.queue.map { it.toNetworkVisibleTrack() })
                    _state.update {
                        if (isActiveListenerSession(sessionId)) {
                            it.copy(listenerRoomState = listenerRoom, errorMessage = null)
                        } else {
                            it
                        }
                    }
                    val opened = ensureListenerControlStreamOpen(endpoint, token)
                    if (opened.isSuccess && isActiveListenerSession(sessionId)) {
                        maybeStartListenerPlayback(endpoint, token, listenerRoom, sessionId)
                        return@launch
                    }
                }
                attempt++
            }
            if (isActiveListenerSession(sessionId)) {
                _state.update { it.copy(errorMessage = "Lost connection to the room. Rejoin to continue.") }
            }
        }
    }

    private fun scheduleHostStart(trackId: String, uri: String, room: RoomState) {
        hostStartJob?.cancel()
        hostStartJob = scope.launch {
            val delayMs = ((room.effectiveAtHostTimeNanos - System.nanoTime()).coerceAtLeast(0) / 1_000_000.0).roundToLong()
            if (delayMs > 0) delay(delayMs)
            val startPositionMs = PlaybackTimeline.from(room)?.targetPositionMs(System.nanoTime()) ?: room.hostPositionMs
            playback.play(trackId, uri, startPositionMs)
        }
    }

    companion object {
        const val HOST_START_LEAD_NANOS: Long = 2_000_000_000
        const val SYNC_SAMPLE_INTERVAL_MS: Long = 2_000
        const val RECONNECT_BASE_MS: Long = 500
        const val RECONNECT_MAX_ATTEMPTS: Int = 6
    }
}

private fun listenerMediaUrl(endpoint: LocalRoomEndpoint, trackId: String): String =
    "http://${endpoint.host}:${endpoint.port}/media/${URLEncoder.encode(trackId, "UTF-8")}"

private fun localPathFromUri(uri: String): String {
    if (uri.startsWith("file://")) return uri.removePrefix("file://").let(::File).path
    return uri
}
