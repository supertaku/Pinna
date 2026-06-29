package com.pinna.app.room

import com.pinna.app.core.model.PlaybackState
import com.pinna.app.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomReducerTest {
    private val track = Track(
        id = "track-1",
        title = "Pulse Test",
        artist = "Pinna",
        durationMs = 180_000,
        mimeType = "audio/mpeg",
        localUri = "content://track-1",
        sizeBytes = 1_000_000,
    )

    @Test
    fun createRoom_setsHostStateAndQueue() {
        val state = RoomReducer.reduce(
            RoomState.initial(),
            RoomEvent.CreateRoom(roomId = "room-1", hostDeviceId = "host-1", queue = listOf(track)),
        )

        assertEquals("room-1", state.roomId)
        assertEquals("host-1", state.hostDeviceId)
        assertEquals(listOf(track), state.queue)
        assertEquals(PlaybackState.IDLE, state.playback)
    }

    @Test
    fun duplicateJoinEvent_isIdempotent() {
        val created = RoomReducer.reduce(
            RoomState.initial(),
            RoomEvent.CreateRoom(roomId = "room-1", hostDeviceId = "host-1", queue = listOf(track)),
        )

        val once = RoomReducer.reduce(created, RoomEvent.ListenerJoined("listener-1", "Mia", sequenceNumber = 1))
        val twice = RoomReducer.reduce(once, RoomEvent.ListenerJoined("listener-1", "Mia", sequenceNumber = 2))

        assertEquals(1, twice.listeners.size)
        assertEquals("Mia", twice.listeners.single().displayName)
    }

    @Test
    fun stalePlaybackEvent_isIgnored() {
        val playing = RoomReducer.reduce(
            RoomReducer.reduce(
                RoomState.initial(),
                RoomEvent.CreateRoom(roomId = "room-1", hostDeviceId = "host-1", queue = listOf(track)),
            ),
            RoomEvent.Play(trackId = "track-1", positionMs = 10_000, effectiveAtHostTimeNanos = 5_000, sequenceNumber = 10),
        )

        val stale = RoomReducer.reduce(
            playing,
            RoomEvent.Pause(positionMs = 11_000, effectiveAtHostTimeNanos = 6_000, sequenceNumber = 9),
        )

        assertEquals(PlaybackState.PLAYING, stale.playback)
        assertEquals(10_000, stale.hostPositionMs)
        assertEquals(10, stale.sequenceNumber)
    }

    @Test
    fun playUnknownTrack_doesNotAdvanceState() {
        val created = RoomReducer.reduce(
            RoomState.initial(),
            RoomEvent.CreateRoom(roomId = "room-1", hostDeviceId = "host-1", queue = listOf(track)),
        )

        val state = RoomReducer.reduce(
            created,
            RoomEvent.Play(trackId = "missing-track", positionMs = 0, effectiveAtHostTimeNanos = 5_000, sequenceNumber = 1),
        )

        assertEquals(PlaybackState.IDLE, state.playback)
        assertEquals(0, state.sequenceNumber)
        assertEquals("track-1", state.currentTrackId)
    }

    @Test
    fun readyForUnknownListener_doesNotAdvanceSequence() {
        val created = RoomReducer.reduce(
            RoomState.initial(),
            RoomEvent.CreateRoom(roomId = "room-1", hostDeviceId = "host-1", queue = listOf(track)),
        )

        val state = RoomReducer.reduce(
            created,
            RoomEvent.ListenerReady(
                deviceId = "unknown",
                bufferedUntilMs = 30_000,
                hostTimeNanos = 5_000,
                sequenceNumber = 1,
            ),
        )

        assertEquals(0, state.sequenceNumber)
        assertEquals(emptyList<ListenerState>(), state.listeners)
    }

    @Test
    fun endRoom_disconnectsListenersAndMarksClosed() {
        val joined = RoomReducer.reduce(
            RoomReducer.reduce(
                RoomState.initial(),
                RoomEvent.CreateRoom(roomId = "room-1", hostDeviceId = "host-1", queue = listOf(track)),
            ),
            RoomEvent.ListenerJoined("listener-1", "Mia", sequenceNumber = 1),
        )

        val closed = RoomReducer.reduce(joined, RoomEvent.EndRoom(sequenceNumber = 2))

        assertTrue(closed.isClosed)
        assertEquals(emptyList<ListenerState>(), closed.listeners)
        assertEquals(PlaybackState.ENDED, closed.playback)
    }
}
