package com.pinna.app.cache

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

class TemporaryRoomCache(private val root: Path) {
    fun put(roomId: String, trackId: String, bytes: ByteArray, nowEpochMillis: Long, ttlMillis: Long) {
        val entry = pathFor(roomId, trackId)
        entry.parent.createDirectories()
        val expiresAt = nowEpochMillis + ttlMillis
        entry.writeBytes(expiresAt.toString().encodeToByteArray() + byteArrayOf('\n'.code.toByte()) + bytes)
    }

    fun get(roomId: String, trackId: String, nowEpochMillis: Long): ByteArray? {
        val entry = pathFor(roomId, trackId)
        if (!entry.exists()) return null
        val bytes = runCatching { entry.readBytes() }.getOrNull() ?: return null
        val newline = bytes.indexOf('\n'.code.toByte())
        if (newline <= 0) {
            entry.deleteIfExists()
            return null
        }
        val expiresAt = bytes.copyOfRange(0, newline).decodeToString().toLongOrNull()
        if (expiresAt == null || expiresAt <= nowEpochMillis) {
            entry.deleteIfExists()
            return null
        }
        return bytes.copyOfRange(newline + 1, bytes.size)
    }

    fun exists(roomId: String, trackId: String, nowEpochMillis: Long): Boolean =
        get(roomId, trackId, nowEpochMillis) != null

    fun clearRoom(roomId: String) {
        val roomDirectory = safeSegment(roomId).let(root::resolve)
        if (!roomDirectory.exists()) return
        Files.walk(roomDirectory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { it.deleteIfExists() }
        }
    }

    fun sweepExpired(nowEpochMillis: Long) {
        if (!root.exists()) return
        Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) }.forEach { file ->
                val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@forEach
                val newline = bytes.indexOf('\n'.code.toByte())
                val expiresAt = if (newline > 0) bytes.copyOfRange(0, newline).decodeToString().toLongOrNull() else null
                if (expiresAt == null || expiresAt <= nowEpochMillis) file.deleteIfExists()
            }
        }
    }

    private fun pathFor(roomId: String, trackId: String): Path =
        root.resolve(safeSegment(roomId)).resolve("${safeSegment(trackId)}.cache")

    private fun safeSegment(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
        return digest
    }
}
