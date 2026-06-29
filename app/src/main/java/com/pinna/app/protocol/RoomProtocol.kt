package com.pinna.app.protocol

import com.pinna.app.core.model.Track
import com.pinna.app.core.model.toNetworkVisibleTrack
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object RoomProtocol {
    fun encode(message: RoomControlMessage): String = when (message) {
        is RoomControlMessage.Join -> fields("type" to "join", "deviceId" to message.deviceId, "displayName" to message.displayName)
        is RoomControlMessage.Ready -> fields("type" to "ready", "deviceId" to message.deviceId, "bufferedUntilMs" to message.bufferedUntilMs.toString())
        is RoomControlMessage.Play -> fields(
            "type" to "play",
            "trackId" to message.trackId,
            "positionMs" to message.positionMs.toString(),
            "effectiveAtHostTimeNanos" to message.effectiveAtHostTimeNanos.toString(),
            "sequenceNumber" to message.sequenceNumber.toString(),
        )
        is RoomControlMessage.Pause -> fields(
            "type" to "pause",
            "positionMs" to message.positionMs.toString(),
            "effectiveAtHostTimeNanos" to message.effectiveAtHostTimeNanos.toString(),
            "sequenceNumber" to message.sequenceNumber.toString(),
        )
        is RoomControlMessage.Seek -> fields(
            "type" to "seek",
            "positionMs" to message.positionMs.toString(),
            "effectiveAtHostTimeNanos" to message.effectiveAtHostTimeNanos.toString(),
            "sequenceNumber" to message.sequenceNumber.toString(),
        )
        is RoomControlMessage.QueueUpdate -> fields(
            "type" to "queue",
            "sequenceNumber" to message.sequenceNumber.toString(),
            "queue" to message.queue.joinToString("~") { track ->
                val publicTrack = track.toNetworkVisibleTrack()
                listOf(
                    publicTrack.id,
                    publicTrack.title,
                    publicTrack.artist.orEmpty(),
                    publicTrack.durationMs.toString(),
                    publicTrack.mimeType,
                    publicTrack.localUri,
                    publicTrack.sizeBytes.toString(),
                ).joinToString("^") { encodePart(it) }
            },
        )
        is RoomControlMessage.SyncSample -> fields(
            "type" to "sync",
            "t1ClientNanos" to message.t1ClientNanos.toString(),
            "t2HostNanos" to message.t2HostNanos.toString(),
            "t3HostNanos" to message.t3HostNanos.toString(),
        )
        is RoomControlMessage.Error -> fields("type" to "error", "code" to message.code, "message" to message.message)
    }

    fun decode(raw: String): RoomControlMessage {
        val values = try {
            raw.split("&")
                .filter { it.isNotBlank() }
                .mapNotNull {
                    val parts = it.split("=", limit = 2)
                    if (parts.size == 2) decodePart(parts[0]) to decodePart(parts[1]) else null
                }
                .toMap()
        } catch (_: IllegalArgumentException) {
            return RoomControlMessage.Error("invalid_message", "Invalid message encoding.")
        }

        return when (values["type"]) {
            null -> RoomControlMessage.Error("invalid_message", "Missing message type.")
            "join" -> RoomControlMessage.Join(values["deviceId"].orEmpty(), values["displayName"].orEmpty())
            "ready" -> RoomControlMessage.Ready(values["deviceId"].orEmpty(), values["bufferedUntilMs"]?.toLongOrNull() ?: 0)
            "play" -> RoomControlMessage.Play(
                trackId = values["trackId"].orEmpty(),
                positionMs = values["positionMs"]?.toLongOrNull() ?: 0,
                effectiveAtHostTimeNanos = values["effectiveAtHostTimeNanos"]?.toLongOrNull() ?: 0,
                sequenceNumber = values["sequenceNumber"]?.toLongOrNull() ?: 0,
            )
            "pause" -> RoomControlMessage.Pause(
                positionMs = values["positionMs"]?.toLongOrNull() ?: 0,
                effectiveAtHostTimeNanos = values["effectiveAtHostTimeNanos"]?.toLongOrNull() ?: 0,
                sequenceNumber = values["sequenceNumber"]?.toLongOrNull() ?: 0,
            )
            "seek" -> RoomControlMessage.Seek(
                positionMs = values["positionMs"]?.toLongOrNull() ?: 0,
                effectiveAtHostTimeNanos = values["effectiveAtHostTimeNanos"]?.toLongOrNull() ?: 0,
                sequenceNumber = values["sequenceNumber"]?.toLongOrNull() ?: 0,
            )
            "queue" -> RoomControlMessage.QueueUpdate(
                sequenceNumber = values["sequenceNumber"]?.toLongOrNull() ?: 0,
                queue = values["queue"].orEmpty().split("~").filter { it.isNotBlank() }.mapNotNull(::decodeTrack),
            )
            "sync" -> RoomControlMessage.SyncSample(
                t1ClientNanos = values["t1ClientNanos"]?.toLongOrNull() ?: 0,
                t2HostNanos = values["t2HostNanos"]?.toLongOrNull() ?: 0,
                t3HostNanos = values["t3HostNanos"]?.toLongOrNull() ?: 0,
            )
            "error" -> RoomControlMessage.Error(values["code"].orEmpty(), values["message"].orEmpty())
            else -> RoomControlMessage.Error("invalid_message", "Unsupported message type.")
        }
    }

    private fun fields(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (key, value) -> "${encodePart(key)}=${encodePart(value)}" }

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

    private fun encodePart(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    private fun decodePart(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}
