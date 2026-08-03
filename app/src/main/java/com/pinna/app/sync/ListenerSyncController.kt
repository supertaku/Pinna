package com.pinna.app.sync

import com.pinna.app.core.time.ClockSyncMeasurement
import com.pinna.app.core.time.ClockSyncSample
import com.pinna.app.core.time.SyncClockModel
import com.pinna.app.playback.PlaybackController
import com.pinna.app.protocol.RoomControlMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Owns the per-listener clock model and drift-correction loop. The host answers each
 * [RoomControlMessage.SyncSample] request with its own monotonic timestamps; this controller folds
 * those NTP-style samples into a [SyncClockModel] and drives [SyncEngine] corrections.
 *
 * Time sources are injectable so the loop can be unit tested without real sockets or the Android
 * framework.
 */
class ListenerSyncController(
    private val playback: PlaybackController,
    private val clockModel: SyncClockModel = SyncClockModel(),
    private val maxRoundTripNanos: Long = 200_000_000,
    private val nowClientNanos: () -> Long = System::nanoTime,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val engine = SyncEngine(playback)
    private var nudgeResetJob: Job? = null

    var correctionCount: Int = 0
        private set

    var lastAction: DriftAction? = null
        private set

    var lastRoundTripNanos: Long? = null
        private set

    var lastDriftMs: Long? = null
        private set

    val isReady: Boolean
        get() = clockModel.isReady

    val estimatedOffsetNanos: Long
        get() = clockModel.estimatedOffsetNanos

    /** Folds a host-stamped sync reply into the clock model, returning the accepted measurement. */
    fun onSampleReply(sample: RoomControlMessage.SyncSample): ClockSyncMeasurement? {
        val completed = ClockSyncSample(
            t1ClientNanos = sample.t1ClientNanos,
            t2HostNanos = sample.t2HostNanos,
            t3HostNanos = sample.t3HostNanos,
            t4ClientNanos = nowClientNanos(),
        )
        val measurement = completed.toMeasurement(maxRoundTripNanos) ?: return null
        clockModel.add(measurement)
        lastRoundTripNanos = measurement.roundTripNanos
        return measurement
    }

    fun estimatedHostNowNanos(): Long = clockModel.clientTimeToHostTimeNanos(nowClientNanos())

    fun targetPositionMs(timeline: PlaybackTimeline, manualOffsetMs: Long): Long =
        SyncEngine.targetPositionMs(timeline, estimatedHostNowNanos(), manualOffsetMs)

    fun correctDrift(timeline: PlaybackTimeline, manualOffsetMs: Long): DriftAction {
        val estimatedHostNow = estimatedHostNowNanos()
        val target = SyncEngine.targetPositionMs(timeline, estimatedHostNow, manualOffsetMs)
        lastDriftMs = playback.snapshots.value.positionMs - target
        val action = engine.correctDrift(timeline, estimatedHostNow, manualOffsetMs)
        lastAction = action
        when (action) {
            DriftAction.NUDGE_SPEED -> scheduleNudgeReset()
            DriftAction.SEEK,
            DriftAction.REBUFFER,
            -> cancelNudgeReset()
            DriftAction.IGNORE -> Unit
        }
        if (action != DriftAction.IGNORE) correctionCount++
        return action
    }

    fun quality(): SyncQuality =
        SyncQualityClassifier.classify(
            roundTripNanos = lastRoundTripNanos,
            lastAction = lastAction,
            isReady = isReady,
        )

    fun cancelPendingCorrections() {
        cancelNudgeReset(resetPlaybackSpeed = true)
    }

    private fun scheduleNudgeReset() {
        nudgeResetJob?.cancel()
        nudgeResetJob = scope.launch {
            delay(NUDGE_RESET_DELAY_MS)
            playback.setPlaybackSpeed(1.0f)
            nudgeResetJob = null
        }
    }

    private fun cancelNudgeReset(resetPlaybackSpeed: Boolean = false) {
        val hadPendingReset = nudgeResetJob != null
        nudgeResetJob?.cancel()
        nudgeResetJob = null
        if (resetPlaybackSpeed && hadPendingReset) playback.setPlaybackSpeed(1.0f)
    }

    companion object {
        const val NUDGE_RESET_DELAY_MS: Long = 1_500
    }
}
