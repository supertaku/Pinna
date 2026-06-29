package com.pinna.app.sync

import com.pinna.app.core.model.PlaybackState
import com.pinna.app.core.model.Track
import com.pinna.app.room.RoomState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTimelineTest {
    private val track = Track(
        id = "track-1",
        title = "Click",
        artist = null,
        durationMs = 10_000,
        mimeType = "audio/mpeg",
        localUri = "track.mp3",
        sizeBytes = 100,
    )

    @Test
    fun targetPositionIncludesElapsedHostTime() {
        val timeline = PlaybackTimeline(
            trackId = "track-1",
            basePositionMs = 1_000,
            effectiveAtHostTimeNanos = 10_000_000_000,
            sequenceNumber = 4,
        )

        val target = timeline.targetPositionMs(10_250_000_000)

        assertEquals(1_250, target)
    }

    @Test
    fun targetPositionDoesNotGoBackwardsBeforeEffectiveTime() {
        val timeline = PlaybackTimeline(
            trackId = "track-1",
            basePositionMs = 1_000,
            effectiveAtHostTimeNanos = 10_000_000_000,
            sequenceNumber = 4,
        )

        val target = timeline.targetPositionMs(9_500_000_000)

        assertEquals(1_000, target)
    }

    @Test
    fun driftCorrectionClassifiesSmallAndLargeDrift() {
        val timeline = PlaybackTimeline(
            trackId = "track-1",
            basePositionMs = 1_000,
            effectiveAtHostTimeNanos = 10_000_000_000,
            sequenceNumber = 4,
        )

        assertEquals(DriftAction.IGNORE, timeline.driftAction(actualPositionMs = 1_257, estimatedHostNowNanos = 10_250_000_000))
        assertEquals(DriftAction.REBUFFER, timeline.driftAction(actualPositionMs = 1_650, estimatedHostNowNanos = 10_250_000_000))
    }

    @Test
    fun createsTimelineOnlyForPlayableRoomState() {
        val playing = RoomState(
            roomId = "room-1",
            hostDeviceId = "host-1",
            queue = listOf(track),
            currentTrackId = "track-1",
            playback = PlaybackState.PLAYING,
            hostPositionMs = 500,
            effectiveAtHostTimeNanos = 1_000,
            sequenceNumber = 2,
        )
        val paused = playing.copy(playback = PlaybackState.PAUSED)

        assertEquals("track-1", PlaybackTimeline.from(playing)?.trackId)
        assertEquals(null, PlaybackTimeline.from(paused))
    }

    @Test
    fun staleEventsAreRejectedBySequenceNumber() {
        val timeline = PlaybackTimeline(
            trackId = "track-1",
            basePositionMs = 1_000,
            effectiveAtHostTimeNanos = 10_000_000_000,
            sequenceNumber = 4,
        )

        assertFalse(timeline.isFreshComparedTo(currentSequenceNumber = 4))
        assertTrue(timeline.isFreshComparedTo(currentSequenceNumber = 3))
    }
}
