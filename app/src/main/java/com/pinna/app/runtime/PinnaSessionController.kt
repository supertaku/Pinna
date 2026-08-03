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
import com.pinna.app.voice.TalkArbiter
import com.pinna.app.voice.VoiceSink
import com.pinna.app.voice.VoiceSource
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
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
    private val voiceSource: VoiceSource? = null,
    private val voiceSink: VoiceSink? = null,
    private val deviceId: String = UUID.randomUUID().toString(),
    private val hostName: String = "Android host",
    private val hostStartLeadNanos: Long = HOST_START_LEAD_NANOS,
    private val syncSampleIntervalMs: Long = SYNC_SAMPLE_INTERVAL_MS,
    private val audioRouteProvider: () -> AudioRoute = { AudioRoute.UNKNOWN },
    private val reconnectBaseMs: Long = RECONNECT_BASE_MS,
    private val reconnectMaxAttempts: Int = RECONNECT_MAX_ATTEMPTS,
    private val hasLocalNetworkTransport: () -> Boolean = { true },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _state = MutableStateFlow(
        PinnaAppState(hotspotState = hotspotCoordinator?.state?.value ?: LocalHotspotState.Unavailable),
    )
    val state: StateFlow<PinnaAppState> = _state.asStateFlow()
    private var controlStreamJob: Job? = null
    private var streamStateJob: Job? = null
    private var syncJob: Job? = null
    private var listenerStartJob: Job? = null
    private var hostStartJob: Job? = null
    private var hostRoomJob: Job? = null
    private var reconnectJob: Job? = null
    private var hostVoiceJob: Job? = null
    private var playbackErrorJob: Job? = null
    private var voiceSendJob: Job? = null
    private var voiceFrames: Channel<ByteArray>? = null
    private val talkArbiter = TalkArbiter()
    private val voiceSequence = AtomicLong(0)
    private val incomingVoiceSequences = mutableMapOf<String, Long>()
    @Volatile
    private var listenerSync: ListenerSyncController? = null
    @Volatile
    private var listenerEndpoint: LocalRoomEndpoint? = null
    @Volatile
    private var listenerToken: String? = null
    private val listenerSessionId = AtomicLong(0)
    private val joinAttemptId = AtomicLong(0)

    init {
        playbackErrorJob = scope.launch {
            playback.errors.collect { message -> _state.update { it.copy(errorMessage = message) } }
        }
    }

    fun show(screen: PinnaScreen) {
        _state.update { it.copy(screen = screen, errorMessage = null) }
    }

    fun showDiagnostics() {
        _state.update {
            it.copy(
                screen = PinnaScreen.Diagnostics,
                diagnosticsReturnScreen = it.screen.takeIf { current ->
                    current == PinnaScreen.HostRoom || current == PinnaScreen.ListenerRoom
                } ?: PinnaScreen.Home,
                errorMessage = null,
            )
        }
    }

    fun closeDiagnostics() {
        _state.update { it.copy(screen = it.diagnosticsReturnScreen, errorMessage = null) }
    }

    fun reportHotspotPermissionDenied() {
        _state.update {
            it.copy(
                hotspotState = hotspotCoordinator?.state?.value ?: LocalHotspotState.Unavailable,
                errorMessage = "Nearby Wi-Fi permission is required to start a phone hotspot.",
            )
        }
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
                fingerprint = QrJoinPayloadCodec.ROOM_FINGERPRINT,
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
        startHostVoiceObserver()
    }

    private fun startHostRoomObserver() {
        hostRoomJob?.cancel()
        val activeServer = server as? HttpLocalRoomServer ?: return
        hostRoomJob = scope.launch {
            activeServer.rooms.collect { serverRoom ->
                _state.update { app ->
                    if (app.hostRoomState == null || app.hostRoomState == serverRoom) app
                    else app.copy(hostRoomState = serverRoom)
                }
            }
        }
    }

    suspend fun endRoom() {
        stopTalkingInternal()
        hostStartJob?.cancel()
        hostStartJob = null
        hostRoomJob?.cancel()
        hostRoomJob = null
        hostVoiceJob?.cancel()
        hostVoiceJob = null
        server.broadcast(RoomControlMessage.Error(ROOM_ENDED_CODE, "The host ended the room."))
        server.stop()
        stopHotspot()
        playback.stop()
        playback.setVolumeMultiplier(1f)
        voiceSink?.release()
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
                if (!hasLocalNetworkTransport()) {
                    _state.update {
                        it.copy(
                            screen = PinnaScreen.Scanner,
                            errorMessage = "Connect to the host's Wi-Fi or phone hotspot before joining.",
                        )
                    }
                    return
                }
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
                        val admission = client.send(RoomControlMessage.Join(deviceId, DEFAULT_LISTENER_NAME))
                        if (admission.isFailure || !isActiveJoinAttempt(attemptId)) {
                            val failure = admission.exceptionOrNull()
                            client.disconnect()
                            playback.stop()
                            _state.update {
                                if (!isActiveJoinAttempt(attemptId)) return@update it
                                it.copy(
                                    screen = PinnaScreen.Scanner,
                                    hostEndpoint = null,
                                    listenerRoomState = null,
                                    errorMessage = failure?.message ?: "Could not announce this listener to the room.",
                                )
                            }
                            return@onSuccess
                        }
                        client.send(RoomControlMessage.Ready(deviceId, bufferedUntilMs = 0))
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
        cancelPendingListenerStart()
        val timeline = PlaybackTimeline.from(room) ?: return
        val track = room.queue.firstOrNull { it.id == timeline.trackId } ?: return
        val hostNowNanos = estimatedListenerHostNowNanos()
        if (sessionId != null && !isActiveListenerSession(sessionId)) return
        val delayMs = ((room.effectiveAtHostTimeNanos - hostNowNanos).coerceAtLeast(0) / 1_000_000.0).roundToLong()
        if (delayMs > 0) {
            val mediaUri = listenerMediaUrl(endpoint, track.id)
            val httpClient = client as? HttpLocalRoomClient
            val headers = httpClient?.authorizationHeaders(token) ?: mapOf("Authorization" to "Bearer $token")
            playback.prepare(track.id, mediaUri, timeline.basePositionMs, headers)
            listenerStartJob = scope.launch {
                delay(delayMs)
                listenerStartJob = null
                if (sessionId != null && !isActiveListenerSession(sessionId)) return@launch
                val latestRoom = state.value.listenerRoomState ?: return@launch
                if (!isSameScheduledListenerPlayback(latestRoom, room)) return@launch
                startListenerPlaybackNow(endpoint, token, latestRoom, sessionId, usePreparedPlayback = true)
            }
            return
        }
        startListenerPlaybackNow(endpoint, token, room, sessionId, hostNowNanos)
    }

    private suspend fun startListenerPlaybackNow(
        endpoint: LocalRoomEndpoint,
        token: String,
        room: RoomState,
        sessionId: Long?,
        hostNowNanos: Long? = null,
        usePreparedPlayback: Boolean = false,
    ) {
        if (sessionId != null && !isActiveListenerSession(sessionId)) return
        val timeline = PlaybackTimeline.from(room) ?: return
        val track = room.queue.firstOrNull { it.id == timeline.trackId } ?: return
        val effectiveHostNowNanos = hostNowNanos ?: estimatedListenerHostNowNanos()
        val httpClient = client as? HttpLocalRoomClient
        val mediaUri = listenerMediaUrl(endpoint, track.id)
        val headers = httpClient?.authorizationHeaders(token) ?: mapOf("Authorization" to "Bearer $token")
        val manualOffsetMs = state.value.listenerSync.manualOffsetMs
        val sync = listenerSync
        val targetPositionMs = if (sync != null && sync.isReady) {
            sync.targetPositionMs(timeline, manualOffsetMs)
        } else {
            (timeline.targetPositionMs(effectiveHostNowNanos) + manualOffsetMs).coerceAtLeast(0)
        }
        if (usePreparedPlayback) {
            playback.playPrepared(track.id, mediaUri, targetPositionMs, headers)
        } else {
            playback.play(track.id, mediaUri, targetPositionMs, headers)
        }
    }

    private suspend fun estimatedListenerHostNowNanos(): Long {
        val sync = listenerSync
        if (sync != null && sync.isReady) return sync.estimatedHostNowNanos()
        val httpClient = client as? HttpLocalRoomClient
        return httpClient?.fetchHostTimeNanos()?.getOrNull() ?: System.nanoTime()
    }

    private fun isSameScheduledListenerPlayback(latestRoom: RoomState?, scheduledRoom: RoomState): Boolean =
        latestRoom?.playback == PlaybackState.PLAYING &&
            latestRoom.currentTrackId == scheduledRoom.currentTrackId &&
            latestRoom.sequenceNumber == scheduledRoom.sequenceNumber

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
        cancelPendingListenerStart()
        listenerSync?.cancelPendingCorrections()
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
                        cancelPendingListenerStart()
                        playback.seekTo(it.hostPositionMs)
                        playback.pause()
                    }
                }

            is RoomControlMessage.Seek -> reduceListenerRoom(
                sessionId,
                RoomEvent.Seek(message.positionMs, message.effectiveAtHostTimeNanos, message.sequenceNumber),
            )
                ?.let {
                    cancelPendingListenerStart()
                    val hostNowNanos = estimatedListenerHostNowNanos()
                    val targetPositionMs = PlaybackTimeline.from(it)?.targetPositionMs(hostNowNanos) ?: message.positionMs
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
                        if (isActiveListenerSession(sessionId)) {
                            cancelPendingListenerStart()
                            playback.stop()
                        }
                    } else if (next.playback == PlaybackState.PLAYING) {
                        maybeStartListenerPlayback(endpoint, token, next, sessionId)
                    }
                }
            }

            is RoomControlMessage.Error -> {
                if (message.code == ROOM_ENDED_CODE && isActiveListenerSession(sessionId)) {
                    scope.launch { leaveListenerRoom(message.message.ifBlank { "The host ended the room." }) }
                } else {
                    _state.update {
                        if (isActiveListenerSession(sessionId)) it.copy(errorMessage = message.message) else it
                    }
                }
            }
            is RoomControlMessage.SyncSample -> {
                if (message.t2HostNanos != 0L || message.t3HostNanos != 0L) {
                    handleSyncReply(message, sessionId)
                }
            }
            is RoomControlMessage.StartTalk,
            is RoomControlMessage.EndTalk,
            is RoomControlMessage.Voice,
            -> handleIncomingVoiceControl(message)
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
        leaveListenerRoom(errorMessage = null)
    }

    private suspend fun leaveListenerRoom(errorMessage: String?) {
        stopTalkingInternal()
        cancelListenerControlStream()
        client.disconnect()
        playback.stop()
        playback.setVolumeMultiplier(1f)
        voiceSink?.release()
        _state.update {
            it.copy(
                screen = PinnaScreen.Home,
                hostEndpoint = null,
                listenerRoomState = null,
                listenerSync = ListenerSyncStatus(),
                controlStreamState = ControlStreamState.Disconnected,
                talkerDeviceId = null,
                errorMessage = errorMessage,
            )
        }
    }

    suspend fun shutdown() {
        stopTalkingInternal()
        voiceSink?.release()
        cancelListenerControlStream()
        hostStartJob?.cancel()
        hostStartJob = null
        hostRoomJob?.cancel()
        hostRoomJob = null
        hostVoiceJob?.cancel()
        hostVoiceJob = null
        playbackErrorJob?.cancel()
        playbackErrorJob = null
        scope.coroutineContext.cancelChildren()
        server.stop()
        client.shutdown()
        stopHotspot()
        playback.stop()
        playback.setVolumeMultiplier(1f)
        _state.update {
            it.copy(
                screen = PinnaScreen.Home,
                hostEndpoint = null,
                hostPayload = null,
                hostRoomState = null,
                listenerRoomState = null,
                listenerSync = ListenerSyncStatus(),
                controlStreamState = ControlStreamState.Disconnected,
                talkerDeviceId = null,
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
                RoomEvent.Pause(playback.currentPositionMs(), System.nanoTime(), nextSequence),
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
                RoomEvent.Play(track.id, playback.currentPositionMs(), effectiveAt, nextSequence),
            )
            server.broadcast(RoomControlMessage.Play(track.id, nextRoom.hostPositionMs, nextRoom.effectiveAtHostTimeNanos, nextRoom.sequenceNumber))
            scheduleHostStart(track.id, track.localUri, nextRoom)
            _state.update { it.copy(hostRoomState = nextRoom) }
        }
    }

    suspend fun seekHostBy(deltaMs: Long) {
        val room = state.value.hostRoomState ?: return
        val track = room.queue.firstOrNull { it.id == room.currentTrackId } ?: return
        val unboundedTarget = (playback.currentPositionMs() + deltaMs).coerceAtLeast(0)
        val target = if (track.durationMs > 0) unboundedTarget.coerceAtMost(track.durationMs) else unboundedTarget
        val nextRoom = RoomReducer.reduce(
            room,
            RoomEvent.Seek(target, System.nanoTime(), room.sequenceNumber + 1),
        )
        hostStartJob?.cancel()
        hostStartJob = null
        playback.seekTo(target)
        server.broadcast(RoomControlMessage.Seek(target, nextRoom.effectiveAtHostTimeNanos, nextRoom.sequenceNumber))
        _state.update { it.copy(hostRoomState = nextRoom) }
    }

    suspend fun skipHostTrack(direction: Int) {
        if (direction == 0) return
        val room = state.value.hostRoomState ?: return
        if (room.queue.size < 2) return
        val currentIndex = room.queue.indexOfFirst { it.id == room.currentTrackId }.coerceAtLeast(0)
        val nextIndex = (currentIndex + if (direction > 0) 1 else -1).mod(room.queue.size)
        val track = room.queue[nextIndex]
        val effectiveAt = System.nanoTime() + hostStartLeadNanos
        val nextRoom = RoomReducer.reduce(
            room,
            RoomEvent.Play(track.id, 0, effectiveAt, room.sequenceNumber + 1),
        )
        server.broadcast(RoomControlMessage.Play(track.id, 0, effectiveAt, nextRoom.sequenceNumber))
        scheduleHostStart(
            track.id,
            track.localUri,
            nextRoom,
            prepareAhead = room.playback != PlaybackState.PLAYING,
        )
        _state.update { it.copy(hostRoomState = nextRoom) }
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

    /** Stable id for this device, used for half-duplex talk arbitration. */
    val localDeviceId: String get() = deviceId

    /** Begins push-to-talk if the floor is free. Captured frames are streamed to the room. */
    fun startTalking() {
        if (!talkArbiter.requestTalk(deviceId, System.currentTimeMillis())) return
        _state.update { it.copy(talkerDeviceId = deviceId) }
        playback.setVolumeMultiplier(TALK_DUCK_VOLUME)
        scope.launch { sendTalkControl(RoomControlMessage.StartTalk(deviceId)) }
        val frames = Channel<ByteArray>(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        voiceFrames = frames
        voiceSendJob?.cancel()
        voiceSendJob = scope.launch {
            for (frame in frames) {
                val encoded = Base64.getEncoder().encodeToString(frame)
                sendTalkControl(RoomControlMessage.Voice(deviceId, voiceSequence.incrementAndGet(), encoded))
            }
        }
        val startFailure = runCatching {
            voiceSource?.start { frame ->
                talkArbiter.noteActivity(deviceId, System.currentTimeMillis())
                frames.trySend(frame)
            }
        }.exceptionOrNull()
        if (startFailure != null) {
            stopTalkingInternal()
            _state.update { it.copy(errorMessage = "Could not start the microphone. Check microphone permission and try again.") }
        }
    }

    /** Ends push-to-talk and releases the floor. */
    fun stopTalking() {
        val wasTalking = talkArbiter.isTalking(deviceId)
        stopTalkingInternal()
        if (wasTalking) scope.launch { sendTalkControl(RoomControlMessage.EndTalk(deviceId)) }
    }

    private fun stopTalkingInternal() {
        voiceSource?.stop()
        voiceFrames?.close()
        voiceFrames = null
        voiceSendJob?.cancel()
        voiceSendJob = null
        talkArbiter.endTalk(deviceId)
        playback.setVolumeMultiplier(1f)
        _state.update { if (it.talkerDeviceId == deviceId) it.copy(talkerDeviceId = null) else it }
    }

    private suspend fun sendTalkControl(message: RoomControlMessage) {
        // Host fans out to listeners directly; a listener sends to the host, which rebroadcasts.
        if (state.value.hostRoomState != null) {
            server.broadcast(message)
        } else {
            client.send(message).onFailure { failure ->
                _state.update { it.copy(errorMessage = failure.message ?: "Could not send push-to-talk audio.") }
            }
        }
    }

    private fun handleIncomingVoiceControl(message: RoomControlMessage) {
        when (message) {
            is RoomControlMessage.StartTalk -> {
                if (message.deviceId == deviceId) return
                if (!talkArbiter.requestTalk(message.deviceId, System.currentTimeMillis())) return
                incomingVoiceSequences.remove(message.deviceId)
                playback.setVolumeMultiplier(TALK_DUCK_VOLUME)
                _state.update { it.copy(talkerDeviceId = message.deviceId) }
            }
            is RoomControlMessage.EndTalk -> {
                if (message.deviceId == deviceId) return
                if (!talkArbiter.isTalking(message.deviceId)) return
                talkArbiter.endTalk(message.deviceId)
                incomingVoiceSequences.remove(message.deviceId)
                playback.setVolumeMultiplier(1f)
                _state.update { if (it.talkerDeviceId == message.deviceId) it.copy(talkerDeviceId = null) else it }
            }
            is RoomControlMessage.Voice -> {
                if (message.deviceId == deviceId) return
                if (!talkArbiter.isTalking(message.deviceId)) return
                val previousSequence = incomingVoiceSequences[message.deviceId] ?: Long.MIN_VALUE
                if (message.sequence <= previousSequence) return
                incomingVoiceSequences[message.deviceId] = message.sequence
                talkArbiter.noteActivity(message.deviceId, System.currentTimeMillis())
                val pcm = runCatching { Base64.getDecoder().decode(message.pcmBase64) }.getOrNull() ?: return
                if (pcm.size > MAX_VOICE_PCM_BYTES) return
                voiceSink?.play(pcm)
            }
            else -> Unit
        }
    }

    private fun startHostVoiceObserver() {
        hostVoiceJob?.cancel()
        val activeServer = server as? HttpLocalRoomServer ?: return
        hostVoiceJob = scope.launch {
            activeServer.incomingControl.collect { message -> handleIncomingVoiceControl(message) }
        }
    }

    private fun startListenerSync(endpoint: LocalRoomEndpoint, token: String, sessionId: Long) {
        listenerSync = ListenerSyncController(playback, scope = scope)
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

    private fun scheduleHostStart(
        trackId: String,
        uri: String,
        room: RoomState,
        prepareAhead: Boolean = true,
    ) {
        hostStartJob?.cancel()
        if (prepareAhead) playback.prepare(trackId, uri, room.hostPositionMs)
        hostStartJob = scope.launch {
            val delayMs = ((room.effectiveAtHostTimeNanos - System.nanoTime()).coerceAtLeast(0) / 1_000_000.0).roundToLong()
            if (delayMs > 0) delay(delayMs)
            val startPositionMs = PlaybackTimeline.from(room)?.targetPositionMs(System.nanoTime()) ?: room.hostPositionMs
            if (prepareAhead) {
                playback.playPrepared(trackId, uri, startPositionMs)
            } else {
                playback.play(trackId, uri, startPositionMs)
            }
        }
    }

    private fun cancelPendingListenerStart() {
        listenerStartJob?.cancel()
        listenerStartJob = null
    }

    companion object {
        const val HOST_START_LEAD_NANOS: Long = 2_000_000_000
        const val SYNC_SAMPLE_INTERVAL_MS: Long = 2_000
        const val RECONNECT_BASE_MS: Long = 500
        const val RECONNECT_MAX_ATTEMPTS: Int = 6
        const val ROOM_ENDED_CODE: String = "room_ended"
        const val DEFAULT_LISTENER_NAME: String = "Android listener"
        const val TALK_DUCK_VOLUME: Float = 0.25f
        const val MAX_VOICE_PCM_BYTES: Int = 1_536
    }
}

private fun listenerMediaUrl(endpoint: LocalRoomEndpoint, trackId: String): String =
    "http://${endpoint.host}:${endpoint.port}/media/${URLEncoder.encode(trackId, "UTF-8")}"

private fun localPathFromUri(uri: String): String {
    if (uri.startsWith("file://")) return uri.removePrefix("file://").let(::File).path
    return uri
}
