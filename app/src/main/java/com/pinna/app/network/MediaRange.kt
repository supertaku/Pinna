package com.pinna.app.network

data class MediaRange(
    val startInclusive: Long,
    val endInclusive: Long,
    val totalSizeBytes: Long,
) {
    val contentLength: Long = endInclusive - startInclusive + 1

    companion object {
        fun parse(header: String?, totalSizeBytes: Long): MediaRange? {
            if (totalSizeBytes <= 0) return null
            if (header.isNullOrBlank()) {
                return MediaRange(0, totalSizeBytes - 1, totalSizeBytes)
            }
            if (!header.startsWith("bytes=")) return null
            val range = header.removePrefix("bytes=")
            val parts = range.split("-", limit = 2)
            if (parts.size != 2) return null
            val start = parts[0].toLongOrNull() ?: return null
            val end = if (parts[1].isBlank()) totalSizeBytes - 1 else parts[1].toLongOrNull() ?: return null
            if (start < 0 || end < start || end >= totalSizeBytes) return null
            return MediaRange(start, end, totalSizeBytes)
        }
    }
}
