package com.pinna.app.library

import android.content.Context
import android.net.Uri
import com.pinna.app.core.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class AndroidTrackImporter(context: Context) : TrackImporter {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val metadataReader = TrackMetadataReader(resolver)
    private val importDirectory = File(appContext.filesDir, "pinna-tracks")

    override suspend fun import(candidate: ImportedTrackCandidate): Result<Track> = withContext(Dispatchers.IO) {
        runCatching {
            require(candidate.sourceUri.isNotBlank()) { "Could not open selected audio file." }
            val sourceUri = Uri.parse(candidate.sourceUri)
            val metadata = metadataReader.read(sourceUri, candidate.displayName.ifBlank { "Imported audio" })
            val mimeType = candidate.mimeType.ifBlank { metadata.mimeType }
            require(mimeType.startsWith("audio/")) { "Only audio files can be imported." }

            importDirectory.mkdirs()
            val id = UUID.randomUUID().toString()
            val target = File(importDirectory, "$id.audio")
            resolver.openInputStream(sourceUri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Could not open selected audio file.")

            require(target.length() > 0L) { "Selected audio file is empty." }

            Track(
                id = id,
                title = metadata.displayName.ifBlank { candidate.displayName.ifBlank { "Imported audio" } },
                artist = null,
                durationMs = metadata.durationMs,
                mimeType = mimeType,
                localUri = target.absolutePath,
                sizeBytes = target.length(),
            )
        }
    }
}
