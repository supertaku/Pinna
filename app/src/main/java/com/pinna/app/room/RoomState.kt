package com.pinna.app.room

import com.pinna.app.core.model.PlaybackState
import com.pinna.app.core.model.Track

data class ListenerState(
    val deviceId: String,
    val displayName: String,
    val isReady: Boolean = false,
    val bufferedUntilMs: Long = 0,
    val lastSeenHostTimeNanos: Long = 0,
)

data class RoomState(
    val roomId: String = "",
    val hostDeviceId: String = "",
    val queue: List<Track> = emptyList(),
    val currentTrackId: String? = null,
    val playback: PlaybackState = PlaybackState.IDLE,
    val hostPositionMs: Long = 0,
    val effectiveAtHostTimeNanos: Long = 0,
    val sequenceNumber: Long = 0,
    val listeners: List<ListenerState> = emptyList(),
    val isClosed: Boolean = false,
) {
    companion object {
        fun initial(): RoomState = RoomState()
    }
}
