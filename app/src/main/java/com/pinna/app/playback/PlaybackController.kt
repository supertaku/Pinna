package com.pinna.app.playback

import com.pinna.app.core.model.PlaybackState
import kotlinx.coroutines.flow.StateFlow

data class PlaybackSnapshot(
    val state: PlaybackState = PlaybackState.IDLE,
    val trackId: String? = null,
    val positionMs: Long = 0,
    val bufferedPositionMs: Long = 0,
)

interface PlaybackController {
    val snapshots: StateFlow<PlaybackSnapshot>
    fun play(trackId: String, uri: String, positionMs: Long = 0, requestHeaders: Map<String, String> = emptyMap())
    fun pause()
    fun seekTo(positionMs: Long)
    fun setPlaybackSpeed(speed: Float) = Unit
    fun stop()
}
