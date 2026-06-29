package com.pinna.app.network

import com.pinna.app.core.model.PlaybackState
import com.pinna.app.core.model.Track
import com.pinna.app.core.model.toNetworkVisibleTrack
import com.pinna.app.room.ListenerState
import com.pinna.app.room.RoomState
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object RoomHttpRoutes {
    const val AUTHORIZATION = "authorization"
    const val RANGE = "range"

    fun bearerToken(headers: Map<String, String>): String? {
        val value = headers[AUTHORIZATION] ?: return null
        return value.removePrefix("Bearer ").takeIf { it != value && it.isNotBlank() }
    }

    fun encodeRoomState(state: RoomState): String = fields(
        "roomId" to state.roomId,
        "hostDeviceId" to state.hostDeviceId,
        "currentTrackId" to state.currentTrackId.orEmpty(),
        "playback" to state.playback.name,
        "hostPositionMs" to state.hostPositionMs.toString(),
        "effectiveAtHostTimeNanos" to state.effectiveAtHostTimeNanos.toString(),
        "sequenceNumber" to state.sequenceNumber.toString(),
        "isClosed" to state.isClosed.toString(),
        "queue" to state.queue.joinToString("~", transform = ::encodeTrack),
        "listeners" to state.listeners.joinToString("~", transform = ::encodeListener),
    )

    fun decodeRoomState(raw: String): RoomState {
        val values = raw.split("&")
            .filter { it.isNotBlank() }
            .mapNotNull {
                val parts = it.split("=", limit = 2)
                if (parts.size == 2) decodePart(parts[0]) to decodePart(parts[1]) else null
            }
            .toMap()

        return RoomState(
            roomId = values["roomId"].orEmpty(),
            hostDeviceId = values["hostDeviceId"].orEmpty(),
            queue = values["queue"].orEmpty().split("~").filter { it.isNotBlank() }.mapNotNull(::decodeTrack),
            currentTrackId = values["currentTrackId"]?.ifBlank { null },
            playback = values["playback"]?.let { runCatching { PlaybackState.valueOf(it) }.getOrNull() } ?: PlaybackState.IDLE,
            hostPositionMs = values["hostPositionMs"]?.toLongOrNull() ?: 0,
            effectiveAtHostTimeNanos = values["effectiveAtHostTimeNanos"]?.toLongOrNull() ?: 0,
            sequenceNumber = values["sequenceNumber"]?.toLongOrNull() ?: 0,
            listeners = values["listeners"].orEmpty().split("~").filter { it.isNotBlank() }.mapNotNull(::decodeListener),
            isClosed = values["isClosed"].toBoolean(),
        )
    }

    fun encodeHostTime(hostTimeNanos: Long): String = fields("hostTimeNanos" to hostTimeNanos.toString())

    fun decodeHostTime(raw: String): Long {
        return raw.split("&")
            .filter { it.isNotBlank() }
            .mapNotNull {
                val parts = it.split("=", limit = 2)
                if (parts.size == 2) decodePart(parts[0]) to decodePart(parts[1]) else null
            }
            .toMap()["hostTimeNanos"]
            ?.toLongOrNull()
            ?: error("Host time response is invalid.")
    }

    private fun fields(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (key, value) -> "${encodePart(key)}=${encodePart(value)}" }

    private fun encodeTrack(track: Track): String {
        val publicTrack = track.toNetworkVisibleTrack()
        return listOf(
            publicTrack.id,
            publicTrack.title,
            publicTrack.artist.orEmpty(),
            publicTrack.durationMs.toString(),
            publicTrack.mimeType,
            publicTrack.localUri,
            publicTrack.sizeBytes.toString(),
        ).joinToString("^") { encodePart(it) }
    }

    private fun decodeTrack(raw: String): Track? {
        val parts = raw.split("^").map(::decodePart)
        if (parts.size != 7) return null
        return Track(
            id = parts[0],
            title = parts[1],
            artist = parts[2].ifBlank { null },
            durationMs = parts[3].toLongOrNull() ?: return null,
            mimeType = parts[4],
            localUri = parts[5],
            sizeBytes = parts[6].toLongOrNull() ?: return null,
        )
    }

    private fun encodeListener(listener: ListenerState): String = listOf(
        listener.deviceId,
        listener.displayName,
        listener.isReady.toString(),
        listener.bufferedUntilMs.toString(),
        listener.lastSeenHostTimeNanos.toString(),
    ).joinToString("^") { encodePart(it) }

    private fun decodeListener(raw: String): ListenerState? {
        val parts = raw.split("^").map(::decodePart)
        if (parts.size != 5) return null
        return ListenerState(
            deviceId = parts[0],
            displayName = parts[1],
            isReady = parts[2].toBoolean(),
            bufferedUntilMs = parts[3].toLongOrNull() ?: return null,
            lastSeenHostTimeNanos = parts[4].toLongOrNull() ?: return null,
        )
    }

    private fun encodePart(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    private fun decodePart(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}
