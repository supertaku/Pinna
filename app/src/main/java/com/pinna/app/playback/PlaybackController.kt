package com.pinna.app.playback

import com.pinna.app.core.model.PlaybackState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

data class PlaybackSnapshot(
    val state: PlaybackState = PlaybackState.IDLE,
    val trackId: String? = null,
    val positionMs: Long = 0,
    val bufferedPositionMs: Long = 0,
)

interface PlaybackController {
    val snapshots: StateFlow<PlaybackSnapshot>
    val errors: Flow<String> get() = emptyFlow()
    fun prepare(trackId: String, uri: String, positionMs: Long = 0, requestHeaders: Map<String, String> = emptyMap()) = Unit
    fun play(trackId: String, uri: String, positionMs: Long = 0, requestHeaders: Map<String, String> = emptyMap())
    fun playPrepared(trackId: String, uri: String, positionMs: Long = 0, requestHeaders: Map<String, String> = emptyMap()) =
        play(trackId, uri, positionMs, requestHeaders)
    fun pause()
    fun resume()
    fun seekTo(positionMs: Long)
    fun currentPositionMs(): Long = snapshots.value.positionMs
    fun setPlaybackSpeed(speed: Float) = Unit
    fun setVolumeMultiplier(multiplier: Float) = Unit
    fun stop()
}
