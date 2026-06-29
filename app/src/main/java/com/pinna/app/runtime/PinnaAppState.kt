package com.pinna.app.runtime

import com.pinna.app.connectivity.ConnectivityDiagnostic
import com.pinna.app.connectivity.ConnectivityDiagnosticsClassifier
import com.pinna.app.connectivity.ConnectivitySignals
import com.pinna.app.connectivity.LocalHotspotSession
import com.pinna.app.connectivity.LocalHotspotState
import com.pinna.app.connectivity.NetworkTransport
import com.pinna.app.core.model.Track
import com.pinna.app.network.ControlStreamState
import com.pinna.app.network.LocalRoomEndpoint
import com.pinna.app.room.RoomState
import com.pinna.app.sync.SyncQuality

enum class PinnaScreen {
    Home,
    HostSetup,
    HostRoom,
    Scanner,
    ManualJoin,
    ListenerRoom,
    Diagnostics,
}

/**
 * Listener-facing sync diagnostics surfaced in the room and diagnostics screens. All values are
 * derived from the [com.pinna.app.sync.ListenerSyncController] and refreshed on each sync sample
 * and drift correction.
 */
data class ListenerSyncStatus(
    val quality: SyncQuality = SyncQuality.UNSTABLE,
    val manualOffsetMs: Long = 0,
    val estimatedOffsetNanos: Long = 0,
    val roundTripNanos: Long? = null,
    val driftMs: Long? = null,
    val correctionCount: Int = 0,
    val bufferedPositionMs: Long = 0,
    val routeWarning: String? = null,
) {
    companion object {
        const val MIN_MANUAL_OFFSET_MS = -300L
        const val MAX_MANUAL_OFFSET_MS = 300L
    }
}

data class PinnaAppState(
    val screen: PinnaScreen = PinnaScreen.Home,
    val importedTracks: List<Track> = emptyList(),
    val hostEndpoint: LocalRoomEndpoint? = null,
    val hostPayload: String? = null,
    val hostRoomState: RoomState? = null,
    val listenerRoomState: RoomState? = null,
    val listenerSync: ListenerSyncStatus = ListenerSyncStatus(),
    val controlStreamState: ControlStreamState = ControlStreamState.Disconnected,
    val talkerDeviceId: String? = null,
    val hotspotState: LocalHotspotState = LocalHotspotState.Stopped,
    val hotspotSession: LocalHotspotSession? = null,
    val networkHelpMessage: String? = null,
    val diagnostic: ConnectivityDiagnostic = ConnectivityDiagnosticsClassifier.classify(
        ConnectivitySignals(activeTransport = NetworkTransport.WIFI),
    ),
    val errorMessage: String? = null,
    val isBusy: Boolean = false,
) {
    val canCreateRoom: Boolean = importedTracks.isNotEmpty() && !isBusy
    val isHotspotAvailable: Boolean = hotspotState !is LocalHotspotState.Unavailable
}
