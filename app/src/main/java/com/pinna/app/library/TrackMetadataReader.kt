package com.pinna.app.library

import android.content.ContentResolver
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns

data class TrackMetadata(
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val durationMs: Long,
)

class TrackMetadataReader(private val resolver: ContentResolver) {
    fun read(uri: Uri, fallbackName: String = "Imported audio"): TrackMetadata {
        val queryMetadata = readOpenableMetadata(uri)
        val mimeType = resolver.getType(uri).orEmpty()
            .ifBlank { queryMetadata.mimeType }
            .ifBlank { "audio/*" }
        val duration = readDuration(uri)
        return TrackMetadata(
            displayName = queryMetadata.displayName.ifBlank { fallbackName },
            mimeType = mimeType,
            sizeBytes = queryMetadata.sizeBytes,
            durationMs = duration,
        )
    }

    private fun readOpenableMetadata(uri: Uri): TrackMetadata {
        var displayName = uri.lastPathSegment?.substringAfterLast('/') ?: ""
        var sizeBytes = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) displayName = cursor.getString(nameIndex).orEmpty()
                if (sizeIndex >= 0) sizeBytes = cursor.getLong(sizeIndex)
            }
        }
        return TrackMetadata(displayName = displayName, mimeType = "", sizeBytes = sizeBytes, durationMs = 0)
    }

    private fun readDuration(uri: Uri): Long {
        return runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                    retriever.setDataSource(descriptor.fileDescriptor)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                } ?: 0
            } finally {
                retriever.release()
            }
        }.getOrDefault(0)
    }
}
