package com.pinna.app.ui

import com.pinna.app.connectivity.ConnectivityDiagnostic
import com.pinna.app.connectivity.ConnectivityDiagnosticsClassifier
import com.pinna.app.connectivity.ConnectivitySignals
import com.pinna.app.connectivity.NetworkTransport
import com.pinna.app.core.model.PlaybackState
import com.pinna.app.core.model.Track

data class HostSetupUiState(
    val tracks: List<Track> = emptyList(),
    val isImporting: Boolean = false,
    val error: String? = null,
) {
    val canCreateRoom: Boolean = tracks.isNotEmpty() && !isImporting
}

data class HostRoomUiState(
    val roomId: String,
    val currentTrack: Track?,
    val playbackState: PlaybackState,
    val listenerCount: Int,
    val diagnostic: ConnectivityDiagnostic,
) {
    companion object {
        fun preview(track: Track?, roomCode: String): HostRoomUiState = HostRoomUiState(
            roomId = roomCode,
            currentTrack = track,
            playbackState = PlaybackState.PAUSED,
            listenerCount = 0,
            diagnostic = ConnectivityDiagnosticsClassifier.classify(
                ConnectivitySignals(
                    activeTransport = NetworkTransport.WIFI,
                    sameSubnet = true,
                    hostReachable = true,
                    portOpen = true,
                ),
            ),
        )
    }
}

data class ListenerRoomUiState(
    val hostName: String,
    val currentTrack: Track?,
    val playbackState: PlaybackState,
    val syncLabel: String,
    val diagnostic: ConnectivityDiagnostic,
)

data class DiagnosticsUiState(
    val rows: List<DiagnosticRow>,
)

data class DiagnosticRow(
    val label: String,
    val value: String,
    val isHealthy: Boolean,
)
