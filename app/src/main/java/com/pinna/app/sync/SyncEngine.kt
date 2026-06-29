package com.pinna.app.sync

import com.pinna.app.core.time.SyncClockModel
import com.pinna.app.playback.PlaybackController
import kotlin.math.roundToLong

class SyncEngine(
    private val playback: PlaybackController,
) {
    fun correctDrift(
        timeline: PlaybackTimeline,
        estimatedHostNowNanos: Long,
        manualOffsetMs: Long = 0,
    ): DriftAction {
        val actualPositionMs = playback.snapshots.value.positionMs
        val targetPositionMs = targetPositionMs(timeline, estimatedHostNowNanos, manualOffsetMs)
        val driftMs = actualPositionMs - targetPositionMs
        return when (val action = DriftCorrectionPolicy.classify(driftMs)) {
            DriftAction.IGNORE -> action
            DriftAction.NUDGE_SPEED -> {
                playback.setPlaybackSpeed(if (driftMs > 0) 0.98f else 1.02f)
                action
            }
            DriftAction.SEEK -> {
                playback.seekTo(targetPositionMs)
                action
            }
            DriftAction.REBUFFER -> {
                playback.pause()
                playback.seekTo(targetPositionMs)
                action
            }
        }
    }

    companion object {
        fun clientDelayUntilHostTimeMs(
            targetHostTimeNanos: Long,
            nowClientNanos: Long,
            clockModel: SyncClockModel,
        ): Long {
            val estimatedHostNowNanos = clockModel.clientTimeToHostTimeNanos(nowClientNanos)
            return ((targetHostTimeNanos - estimatedHostNowNanos).coerceAtLeast(0) / 1_000_000.0).roundToLong()
        }

        fun targetPositionMs(
            timeline: PlaybackTimeline,
            estimatedHostNowNanos: Long,
            manualOffsetMs: Long = 0,
        ): Long = (timeline.targetPositionMs(estimatedHostNowNanos) + manualOffsetMs).coerceAtLeast(0)
    }
}
