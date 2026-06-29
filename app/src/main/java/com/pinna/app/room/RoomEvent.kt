package com.pinna.app.room

import com.pinna.app.core.model.Track

sealed interface RoomEvent {
    data class CreateRoom(
        val roomId: String,
        val hostDeviceId: String,
        val queue: List<Track>,
    ) : RoomEvent

    data class ListenerJoined(
        val deviceId: String,
        val displayName: String,
        val sequenceNumber: Long,
    ) : RoomEvent

    data class ListenerReady(
        val deviceId: String,
        val bufferedUntilMs: Long,
        val hostTimeNanos: Long,
        val sequenceNumber: Long,
    ) : RoomEvent

    data class ListenerLeft(
        val deviceId: String,
        val sequenceNumber: Long,
    ) : RoomEvent

    data class Play(
        val trackId: String,
        val positionMs: Long,
        val effectiveAtHostTimeNanos: Long,
        val sequenceNumber: Long,
    ) : RoomEvent

    data class Pause(
        val positionMs: Long,
        val effectiveAtHostTimeNanos: Long,
        val sequenceNumber: Long,
    ) : RoomEvent

    data class Seek(
        val positionMs: Long,
        val effectiveAtHostTimeNanos: Long,
        val sequenceNumber: Long,
    ) : RoomEvent

    data class QueueUpdated(
        val queue: List<Track>,
        val sequenceNumber: Long,
    ) : RoomEvent

    data class EndRoom(val sequenceNumber: Long) : RoomEvent
}
