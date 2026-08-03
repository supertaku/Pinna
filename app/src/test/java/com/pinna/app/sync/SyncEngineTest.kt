package com.pinna.app.sync

import com.pinna.app.core.model.PlaybackState
import com.pinna.app.core.time.ClockSyncMeasurement
import com.pinna.app.core.time.SyncClockModel
import com.pinna.app.playback.PlaybackController
import com.pinna.app.playback.PlaybackSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncEngineTest {
    private val timeline = PlaybackTimeline(
        trackId = "track-1",
        basePositionMs = 1_000,
        effectiveAtHostTimeNanos = 2_000_000_000,
        sequenceNumber = 4,
    )

    @Test
    fun scheduledStartDelayUsesClockOffset() {
        val clockModel = SyncClockModel().apply {
            add(ClockSyncMeasurement(offsetNanos = 100_000_000, roundTripNanos = 10_000_000))
        }

        val delayMs = SyncEngine.clientDelayUntilHostTimeMs(
            targetHostTimeNanos = 2_000_000_000,
            nowClientNanos = 1_400_000_000,
            clockModel = clockModel,
        )

        assertEquals(500, delayMs)
    }

    @Test
    fun manualOffsetModifiesTargetPosition() {
        val target = SyncEngine.targetPositionMs(
            timeline = timeline,
            estimatedHostNowNanos = 2_500_000_000,
            manualOffsetMs = -75,
        )

        assertEquals(1_425, target)
    }

    @Test
    fun driftUnder20MsDoesNothing() {
        val playback = FakePlaybackController(positionMs = 1_014)
        val engine = SyncEngine(playback)

        val action = engine.correctDrift(timeline, estimatedHostNowNanos = 2_000_000_000)

        assertEquals(DriftAction.IGNORE, action)
        assertEquals(emptyList<String>(), playback.calls)
    }

    @Test
    fun driftBetween20And80MsNudgesSpeed() {
        val playback = FakePlaybackController(positionMs = 1_050)
        val engine = SyncEngine(playback)

        val action = engine.correctDrift(timeline, estimatedHostNowNanos = 2_000_000_000)

        assertEquals(DriftAction.NUDGE_SPEED, action)
        assertEquals(listOf("speed:0.98"), playback.calls)
    }

    @Test
    fun nudgeSpeedSetsBoundedSlowAndFastSpeed() {
        val fastPlayback = FakePlaybackController(positionMs = 950)
        val slowPlayback = FakePlaybackController(positionMs = 1_050)

        val fastAction = SyncEngine(fastPlayback).correctDrift(timeline, estimatedHostNowNanos = 2_000_000_000)
        val slowAction = SyncEngine(slowPlayback).correctDrift(timeline, estimatedHostNowNanos = 2_000_000_000)

        assertEquals(DriftAction.NUDGE_SPEED, fastAction)
        assertEquals(DriftAction.NUDGE_SPEED, slowAction)
        assertEquals(listOf("speed:1.02"), fastPlayback.calls)
        assertEquals(listOf("speed:0.98"), slowPlayback.calls)
    }

    @Test
    fun driftBetween80And250MsSeeksToTarget() {
        val playback = FakePlaybackController(positionMs = 1_120)
        val engine = SyncEngine(playback)

        val action = engine.correctDrift(timeline, estimatedHostNowNanos = 2_000_000_000)

        assertEquals(DriftAction.SEEK, action)
        assertEquals(listOf("speed:1.0", "seek:1000"), playback.calls)
    }

    @Test
    fun seekCorrectionResetsSpeedToNormal() {
        val playback = FakePlaybackController(positionMs = 1_120)
        val engine = SyncEngine(playback)

        val action = engine.correctDrift(timeline, estimatedHostNowNanos = 2_000_000_000)

        assertEquals(DriftAction.SEEK, action)
        assertEquals(listOf("speed:1.0", "seek:1000"), playback.calls)
    }

    @Test
    fun driftOver250MsRebuffersAtTarget() {
        val playback = FakePlaybackController(positionMs = 1_300)
        val engine = SyncEngine(playback)

        val action = engine.correctDrift(timeline, estimatedHostNowNanos = 2_000_000_000)

        assertEquals(DriftAction.REBUFFER, action)
        assertEquals(listOf("speed:1.0", "pause", "seek:1000", "resume"), playback.calls)
    }

    @Test
    fun rebufferDoesNotResumeWhenPlaybackIsNotPlaying() {
        val playback = FakePlaybackController(positionMs = 1_300, state = PlaybackState.PAUSED)
        val engine = SyncEngine(playback)

        val action = engine.correctDrift(timeline, estimatedHostNowNanos = 2_000_000_000)

        assertEquals(DriftAction.REBUFFER, action)
        assertEquals(listOf("speed:1.0", "pause", "seek:1000"), playback.calls)
    }
}

private class FakePlaybackController(
    positionMs: Long,
    state: PlaybackState = PlaybackState.PLAYING,
) : PlaybackController {
    override val snapshots = MutableStateFlow(
        PlaybackSnapshot(state = state, trackId = "track-1", positionMs = positionMs),
    )
    val calls = mutableListOf<String>()

    override fun play(trackId: String, uri: String, positionMs: Long, requestHeaders: Map<String, String>) {
        calls += "play:$positionMs"
    }

    override fun pause() {
        calls += "pause"
    }

    override fun seekTo(positionMs: Long) {
        calls += "seek:$positionMs"
    }

    override fun resume() {
        calls += "resume"
    }

    override fun stop() {
        calls += "stop"
    }

    override fun setPlaybackSpeed(speed: Float) {
        calls += "speed:$speed"
    }
}
