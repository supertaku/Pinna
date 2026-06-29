package com.pinna.app.sync

import com.pinna.app.core.model.PlaybackState
import com.pinna.app.room.RoomState
import kotlin.math.roundToLong

data class PlaybackTimeline(
    val trackId: String,
    val basePositionMs: Long,
    val effectiveAtHostTimeNanos: Long,
    val sequenceNumber: Long,
    val playbackRate: Float = 1f,
) {
    fun targetPositionMs(estimatedHostNowNanos: Long): Long {
        val elapsedNanos = (estimatedHostNowNanos - effectiveAtHostTimeNanos).coerceAtLeast(0)
        val elapsedMs = (elapsedNanos / 1_000_000.0 * playbackRate).roundToLong()
        return basePositionMs + elapsedMs
    }

    fun driftAction(actualPositionMs: Long, estimatedHostNowNanos: Long): DriftAction {
        val target = targetPositionMs(estimatedHostNowNanos)
        return DriftCorrectionPolicy.classify(actualPositionMs - target)
    }

    fun isFreshComparedTo(currentSequenceNumber: Long): Boolean = sequenceNumber > currentSequenceNumber

    companion object {
        fun from(roomState: RoomState): PlaybackTimeline? {
            val trackId = roomState.currentTrackId ?: return null
            if (roomState.playback != PlaybackState.PLAYING) return null
            return PlaybackTimeline(
                trackId = trackId,
                basePositionMs = roomState.hostPositionMs,
                effectiveAtHostTimeNanos = roomState.effectiveAtHostTimeNanos,
                sequenceNumber = roomState.sequenceNumber,
            )
        }
    }
}
