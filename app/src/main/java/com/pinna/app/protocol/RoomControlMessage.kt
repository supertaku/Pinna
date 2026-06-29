package com.pinna.app.protocol

import com.pinna.app.core.model.Track

sealed interface RoomControlMessage {
    data class Join(val deviceId: String, val displayName: String) : RoomControlMessage
    data class Ready(val deviceId: String, val bufferedUntilMs: Long) : RoomControlMessage
    data class Play(
        val trackId: String,
        val positionMs: Long,
        val effectiveAtHostTimeNanos: Long,
        val sequenceNumber: Long,
    ) : RoomControlMessage

    data class Pause(
        val positionMs: Long,
        val effectiveAtHostTimeNanos: Long,
        val sequenceNumber: Long,
    ) : RoomControlMessage

    data class Seek(
        val positionMs: Long,
        val effectiveAtHostTimeNanos: Long,
        val sequenceNumber: Long,
    ) : RoomControlMessage

    data class QueueUpdate(val queue: List<Track>, val sequenceNumber: Long) : RoomControlMessage
    data class SyncSample(val t1ClientNanos: Long, val t2HostNanos: Long, val t3HostNanos: Long) : RoomControlMessage

    /** Push-to-talk: a participant began talking. Half-duplex — only one talker at a time. */
    data class StartTalk(val deviceId: String) : RoomControlMessage

    /** Push-to-talk: a participant stopped talking. */
    data class EndTalk(val deviceId: String) : RoomControlMessage

    /** A single push-to-talk audio frame (base64 PCM 16 kHz mono) from [deviceId]. */
    data class Voice(val deviceId: String, val sequence: Long, val pcmBase64: String) : RoomControlMessage

    data class Error(val code: String, val message: String) : RoomControlMessage
}
