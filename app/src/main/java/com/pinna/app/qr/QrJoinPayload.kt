package com.pinna.app.qr

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class RoomJoinPayload(
    val version: Int,
    val roomId: String,
    val host: String,
    val port: Int,
    val token: String,
    val expiresAtEpochMillis: Long,
    val fingerprint: String,
) {
    fun redactedForLogs(): String =
        "RoomJoinPayload(version=$version, roomId=$roomId, host=$host, port=$port, token=<redacted>, expiresAtEpochMillis=$expiresAtEpochMillis, fingerprint=$fingerprint)"
}

sealed interface QrDecodeResult {
    data class Valid(val payload: RoomJoinPayload) : QrDecodeResult
    data class Invalid(val reason: String) : QrDecodeResult
    data object Expired : QrDecodeResult
    data class UnsupportedVersion(val version: Int) : QrDecodeResult
}

object QrJoinPayloadCodec {
    private const val PREFIX = "pinna://join?"
    private const val SUPPORTED_VERSION = 1
    const val ROOM_FINGERPRINT = "local-room"

    fun encode(payload: RoomJoinPayload): String {
        val fields = listOf(
            "v" to payload.version.toString(),
            "room" to payload.roomId,
            "host" to payload.host,
            "port" to payload.port.toString(),
            "token" to payload.token,
            "exp" to payload.expiresAtEpochMillis.toString(),
            "fp" to payload.fingerprint,
        )
        return PREFIX + fields.joinToString("&") { (key, value) ->
            "${encodePart(key)}=${encodePart(value)}"
        }
    }

    fun decode(raw: String, nowEpochMillis: Long): QrDecodeResult {
        if (!raw.startsWith(PREFIX)) return QrDecodeResult.Invalid("QR code is not a Pinna room.")
        val fields = try {
            raw.removePrefix(PREFIX)
                .split("&")
                .filter { it.isNotBlank() }
                .mapNotNull {
                    val parts = it.split("=", limit = 2)
                    if (parts.size != 2) null else decodePart(parts[0]) to decodePart(parts[1])
                }
                .toMap()
        } catch (_: IllegalArgumentException) {
            return QrDecodeResult.Invalid("Room QR contains invalid encoding.")
        }

        val version = fields["v"]?.toIntOrNull()
            ?: return QrDecodeResult.Invalid("Room QR is missing a protocol version.")
        if (version != SUPPORTED_VERSION) return QrDecodeResult.UnsupportedVersion(version)

        val payload = RoomJoinPayload(
            version = version,
            roomId = fields["room"].orEmpty(),
            host = fields["host"].orEmpty(),
            port = fields["port"]?.toIntOrNull() ?: -1,
            token = fields["token"].orEmpty(),
            expiresAtEpochMillis = fields["exp"]?.toLongOrNull() ?: -1,
            fingerprint = fields["fp"].orEmpty(),
        )

        if (
            payload.roomId.isBlank() ||
            payload.host.isBlank() ||
            payload.port !in 1..65535 ||
            payload.token.isBlank() ||
            payload.fingerprint.isBlank()
        ) {
            return QrDecodeResult.Invalid("Room QR is missing connection details.")
        }
        if (payload.fingerprint != ROOM_FINGERPRINT) {
            return QrDecodeResult.Invalid("Room QR fingerprint is not recognized.")
        }
        if (payload.expiresAtEpochMillis <= nowEpochMillis) return QrDecodeResult.Expired
        return QrDecodeResult.Valid(payload)
    }

    private fun encodePart(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun decodePart(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}
