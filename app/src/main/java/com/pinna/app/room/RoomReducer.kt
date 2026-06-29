package com.pinna.app.room

import com.pinna.app.core.model.PlaybackState

object RoomReducer {
    fun reduce(state: RoomState, event: RoomEvent): RoomState {
        if (state.isClosed && event !is RoomEvent.CreateRoom) return state
        return when (event) {
            is RoomEvent.CreateRoom -> RoomState(
                roomId = event.roomId,
                hostDeviceId = event.hostDeviceId,
                queue = event.queue,
                currentTrackId = event.queue.firstOrNull()?.id,
            )

            is RoomEvent.ListenerJoined -> state.withFreshSequence(event.sequenceNumber) {
                val existing = listeners.firstOrNull { it.deviceId == event.deviceId }
                val nextListeners = if (existing == null) {
                    listeners + ListenerState(event.deviceId, event.displayName)
                } else {
                    listeners.map {
                        if (it.deviceId == event.deviceId) it.copy(displayName = event.displayName) else it
                    }
                }
                copy(listeners = nextListeners)
            }

            is RoomEvent.ListenerReady -> state.withFreshSequence(event.sequenceNumber) {
                if (listeners.none { it.deviceId == event.deviceId }) return@withFreshSequence this
                copy(
                    listeners = listeners.map {
                        if (it.deviceId == event.deviceId) {
                            it.copy(
                                isReady = true,
                                bufferedUntilMs = event.bufferedUntilMs,
                                lastSeenHostTimeNanos = event.hostTimeNanos,
                            )
                        } else {
                            it
                        }
                    },
                )
            }

            is RoomEvent.ListenerLeft -> state.withFreshSequence(event.sequenceNumber) {
                copy(listeners = listeners.filterNot { it.deviceId == event.deviceId })
            }

            is RoomEvent.Play -> state.withFreshSequence(event.sequenceNumber) {
                if (queue.none { it.id == event.trackId }) return@withFreshSequence this
                copy(
                    currentTrackId = event.trackId,
                    playback = PlaybackState.PLAYING,
                    hostPositionMs = event.positionMs,
                    effectiveAtHostTimeNanos = event.effectiveAtHostTimeNanos,
                )
            }

            is RoomEvent.Pause -> state.withFreshSequence(event.sequenceNumber) {
                copy(
                    playback = PlaybackState.PAUSED,
                    hostPositionMs = event.positionMs,
                    effectiveAtHostTimeNanos = event.effectiveAtHostTimeNanos,
                )
            }

            is RoomEvent.Seek -> state.withFreshSequence(event.sequenceNumber) {
                copy(
                    hostPositionMs = event.positionMs,
                    effectiveAtHostTimeNanos = event.effectiveAtHostTimeNanos,
                )
            }

            is RoomEvent.QueueUpdated -> state.withFreshSequence(event.sequenceNumber) {
                val current = currentTrackId?.takeIf { id -> event.queue.any { it.id == id } }
                    ?: event.queue.firstOrNull()?.id
                copy(
                    queue = event.queue,
                    currentTrackId = current,
                    playback = if (current == null) PlaybackState.IDLE else playback,
                    hostPositionMs = if (current == null) 0 else hostPositionMs,
                )
            }

            is RoomEvent.EndRoom -> state.withFreshSequence(event.sequenceNumber) {
                copy(
                    listeners = emptyList(),
                    playback = PlaybackState.ENDED,
                    isClosed = true,
                )
            }
        }
    }

    private inline fun RoomState.withFreshSequence(
        incomingSequence: Long,
        transform: RoomState.() -> RoomState,
    ): RoomState {
        if (incomingSequence <= sequenceNumber) return this
        val transformed = transform()
        if (transformed === this || transformed == this) return this
        return transformed.copy(sequenceNumber = incomingSequence)
    }
}
