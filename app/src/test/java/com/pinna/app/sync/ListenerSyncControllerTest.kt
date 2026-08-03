@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.pinna.app.sync

import com.pinna.app.core.model.PlaybackState
import com.pinna.app.playback.PlaybackController
import com.pinna.app.playback.PlaybackSnapshot
import com.pinna.app.protocol.RoomControlMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenerSyncControllerTest {
    private val timeline = PlaybackTimeline(
        trackId = "track-1",
        basePositionMs = 1_000,
        effectiveAtHostTimeNanos = 2_000_000_000,
        sequenceNumber = 4,
    )

    @Test
    fun sampleReplyOverFakeWebSocketUpdatesClockOffset() {
        val playback = RecordingPlaybackController(positionMs = 1_000)
        var clientNow = 1_000_000_000L
        val controller = ListenerSyncController(playback = playback, nowClientNanos = { clientNow })

        // Listener sent t1 at 1_000_000_000; host stamped t2/t3; reply observed at t4 = 1_020_000_000.
        clientNow = 1_020_000_000
        val measurement = controller.onSampleReply(
            RoomControlMessage.SyncSample(
                t1ClientNanos = 1_000_000_000,
                t2HostNanos = 5_010_000_000,
                t3HostNanos = 5_010_000_000,
            ),
        )

        assertNotNull(measurement)
        // offset ≈ host - client ≈ 4_000_000_000, round-trip 20ms.
        assertEquals(4_000_000_000, controller.estimatedHostNowNanos() - clientNow)
        assertEquals(20_000_000, measurement!!.roundTripNanos)
    }

    @Test
    fun outOfOrderSampleIsRejected() {
        val playback = RecordingPlaybackController(positionMs = 1_000)
        val controller = ListenerSyncController(playback = playback, nowClientNanos = { 0 })

        val measurement = controller.onSampleReply(
            RoomControlMessage.SyncSample(
                t1ClientNanos = 100,
                t2HostNanos = 50,
                t3HostNanos = 10,
            ),
        )

        assertNull(measurement)
    }

    @Test
    fun driftCorrectionUsesEstimatedHostTimeAndCountsCorrections() {
        val playback = RecordingPlaybackController(positionMs = 1_120)
        var clientNow = 0L
        val controller = ListenerSyncController(playback = playback, nowClientNanos = { clientNow })
        // Establish a zero offset so estimated host time equals client time.
        controller.onSampleReply(RoomControlMessage.SyncSample(0, 5_000_000, 5_000_000).also { clientNow = 10_000_000 })

        clientNow = 2_000_000_000
        val action = controller.correctDrift(timeline, manualOffsetMs = 0)

        assertEquals(DriftAction.SEEK, action)
        assertTrue(playback.calls.contains("seek:1000"))
        assertEquals(1, controller.correctionCount)
    }

    @Test
    fun ignoredDriftDoesNotCount() {
        val playback = RecordingPlaybackController(positionMs = 1_000)
        val controller = ListenerSyncController(playback = playback, nowClientNanos = { 2_000_000_000 })

        val action = controller.correctDrift(timeline, manualOffsetMs = 0)

        assertEquals(DriftAction.IGNORE, action)
        assertEquals(0, controller.correctionCount)
    }

    @Test
    fun nudgeResetReturnsPlaybackSpeedToOneAfterWindow() {
        val scope = TestScope(StandardTestDispatcher())
        val playback = RecordingPlaybackController(positionMs = 1_050)
        val controller = ListenerSyncController(
            playback = playback,
            nowClientNanos = { 2_000_000_000 },
            scope = scope,
        )

        val action = controller.correctDrift(timeline, manualOffsetMs = 0)
        scope.runCurrent()
        scope.testScheduler.advanceTimeBy(1_499)
        scope.runCurrent()

        assertEquals(DriftAction.NUDGE_SPEED, action)
        assertEquals(listOf("speed:0.98"), playback.calls)

        scope.testScheduler.advanceTimeBy(1)
        scope.runCurrent()

        assertEquals(listOf("speed:0.98", "speed:1.0"), playback.calls)
    }

    @Test
    fun newerCorrectionCancelsPreviousNudgeReset() {
        val scope = TestScope(StandardTestDispatcher())
        val playback = RecordingPlaybackController(positionMs = 1_050)
        val controller = ListenerSyncController(
            playback = playback,
            nowClientNanos = { 2_000_000_000 },
            scope = scope,
        )

        controller.correctDrift(timeline, manualOffsetMs = 0)
        scope.runCurrent()
        scope.testScheduler.advanceTimeBy(1_000)
        playback.snapshots.value = playback.snapshots.value.copy(positionMs = 950)
        controller.correctDrift(timeline, manualOffsetMs = 0)
        scope.runCurrent()
        scope.testScheduler.advanceTimeBy(500)
        scope.runCurrent()

        assertEquals(listOf("speed:0.98", "speed:1.02"), playback.calls)

        scope.testScheduler.advanceTimeBy(1_000)
        scope.runCurrent()

        assertEquals(listOf("speed:0.98", "speed:1.02", "speed:1.0"), playback.calls)
    }

    @Test
    fun cancelPendingCorrectionsCancelsScheduledNudgeReset() {
        val scope = TestScope(StandardTestDispatcher())
        val playback = RecordingPlaybackController(positionMs = 1_050)
        val controller = ListenerSyncController(
            playback = playback,
            nowClientNanos = { 2_000_000_000 },
            scope = scope,
        )

        controller.correctDrift(timeline, manualOffsetMs = 0)
        scope.runCurrent()
        controller.cancelPendingCorrections()
        scope.testScheduler.advanceTimeBy(1_500)
        scope.runCurrent()

        assertEquals(listOf("speed:0.98", "speed:1.0"), playback.calls)
    }
}

private class RecordingPlaybackController(positionMs: Long) : PlaybackController {
    override val snapshots = MutableStateFlow(
        PlaybackSnapshot(state = PlaybackState.PLAYING, trackId = "track-1", positionMs = positionMs),
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
