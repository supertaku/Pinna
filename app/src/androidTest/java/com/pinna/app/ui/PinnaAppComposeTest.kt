package com.pinna.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.pinna.app.connectivity.LocalHotspotCoordinator
import com.pinna.app.connectivity.LocalHotspotSession
import com.pinna.app.connectivity.LocalHotspotState
import com.pinna.app.core.model.PlaybackState
import com.pinna.app.core.model.Track
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
import com.pinna.app.runtime.PinnaScreen
import com.pinna.app.runtime.PinnaSessionController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class PinnaAppComposeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val track = Track(
        id = "track-1",
        title = "Track",
        artist = null,
        durationMs = 1_000,
        mimeType = "audio/mpeg",
        localUri = "track.mp3",
        sizeBytes = 10,
    )

    @Test
    fun createRoomIsDisabledWithNoTracks() {
        val controller = newController()

        composeRule.setContent { PinnaApp(controller) }
        composeRule.onNodeWithText("Host a room").performClick()

        composeRule.onNodeWithTag("create-room-button").assertIsNotEnabled()
    }

    @Test
    fun importedTrackEnablesCreateRoom() {
        val controller = newController()
        controller.addImportedTracks(listOf(track))
        controller.show(PinnaScreen.HostSetup)

        composeRule.setContent { PinnaApp(controller) }

        composeRule.onNodeWithTag("create-room-button").assertIsEnabled()
    }

    @Test
    fun invalidJoinPayloadShowsError() {
        val controller = newController()

        composeRule.setContent { PinnaApp(controller) }
        composeRule.onNodeWithText("Join a room").performClick()
        composeRule.onNodeWithTag("manual-fallback-button").performClick()
        composeRule.onNodeWithTag("manual-payload-input").performTextInput("invalid")
        composeRule.onNodeWithTag("join-room-button").performClick()

        composeRule.onNodeWithTag("error-message").assertTextContains("QR code is not a Pinna room.")
    }

    @Test
    fun hostRoomShowsQrShareDialog() = runBlocking {
        val controller = newController()
        controller.addImportedTracks(listOf(track))
        controller.createRoom()

        composeRule.setContent { PinnaApp(controller) }
        composeRule.onNodeWithText("Show QR").performClick()

        composeRule.onNodeWithTag("room-qr-code").assertExists()
        composeRule.onNodeWithText("Copy payload").assertExists()
    }

    @Test
    fun hostRoomShowsActiveHotspotCredentials() = runBlocking {
        val hotspot = FakeLocalHotspotCoordinator()
        val controller = newController(hotspot = hotspot)
        controller.addImportedTracks(listOf(track))
        controller.createRoom(useHotspot = true)

        composeRule.setContent { PinnaApp(controller) }

        composeRule.onNodeWithTag("hotspot-ssid").assertTextContains("Pinna")
        composeRule.onNodeWithTag("hotspot-passphrase").assertTextContains("secret-pass")
    }

    @Test
    fun listenerRoomShowsSyncCalibrationAndQuality() = runBlocking {
        val controller = newController()
        val payload = QrJoinPayloadCodec.encode(
            RoomJoinPayload(
                version = 1,
                roomId = "room-1",
                host = "127.0.0.1",
                port = 1234,
                token = "token",
                expiresAtEpochMillis = 4_000,
                fingerprint = "fp",
            ),
        )
        controller.joinRoom(payload, nowEpochMillis = 1_000)

        composeRule.setContent { PinnaApp(controller) }

        composeRule.onNodeWithTag("sync-quality-chip").assertExists()
        composeRule.onNodeWithTag("connection-chip").assertExists()
        composeRule.onNodeWithTag("manual-offset-slider").assertExists()
        composeRule.onNodeWithTag("reset-offset-button").performClick()
        composeRule.onNodeWithTag("manual-offset-value").assertTextContains("0 ms")
    }

    private fun newController(hotspot: LocalHotspotCoordinator? = null): PinnaSessionController = PinnaSessionController(
        server = FakeLocalRoomServer(),
        client = FakeLocalRoomClient(),
        playback = FakePlaybackController(),
        hotspotCoordinator = hotspot,
        hostName = "host-1",
    )
}

private class FakeLocalRoomServer : LocalRoomServer {
    override var endpoint: LocalRoomEndpoint? = null
        private set

    override suspend fun start(roomState: RoomState, token: String, tracks: Map<String, String>): LocalRoomEndpoint {
        endpoint = LocalRoomEndpoint("127.0.0.1", 1234, roomState.roomId)
        return endpoint!!
    }

    override suspend fun stop() {
        endpoint = null
    }

    override suspend fun broadcast(message: RoomControlMessage) = Unit
}

private class FakeLocalRoomClient : LocalRoomClient {
    override val controlMessages = MutableSharedFlow<RoomControlMessage>()
    override val controlStreamState = MutableStateFlow<ControlStreamState>(ControlStreamState.Disconnected)

    override suspend fun connect(endpoint: LocalRoomEndpoint, token: String): Result<RoomState> =
        Result.success(RoomState(roomId = endpoint.roomId, hostDeviceId = "host-1"))

    override suspend fun openControlStream(endpoint: LocalRoomEndpoint, token: String): Result<Unit> {
        controlStreamState.value = ControlStreamState.Connected
        return Result.success(Unit)
    }

    override suspend fun send(message: RoomControlMessage): Result<Unit> = Result.success(Unit)

    override suspend fun disconnect() {
        controlStreamState.value = ControlStreamState.Disconnected
    }
}

private class FakePlaybackController : PlaybackController {
    override val snapshots = MutableStateFlow(PlaybackSnapshot())

    override fun play(trackId: String, uri: String, positionMs: Long, requestHeaders: Map<String, String>) {
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

private class FakeLocalHotspotCoordinator : LocalHotspotCoordinator {
    private val session = LocalHotspotSession(ssid = "Pinna", passphrase = "secret-pass")
    private val _state = MutableStateFlow<LocalHotspotState>(LocalHotspotState.Stopped)
    override val state = _state

    override suspend fun start(): Result<LocalHotspotSession> {
        _state.value = LocalHotspotState.Active(session)
        return Result.success(session)
    }

    override suspend fun stop() {
        _state.value = LocalHotspotState.Stopped
    }
}
