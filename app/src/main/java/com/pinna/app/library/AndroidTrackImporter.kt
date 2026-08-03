package com.pinna.app.library

import android.content.Context
import androidx.core.net.toUri
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
        var target: File? = null
        runCatching {
            require(candidate.sourceUri.isNotBlank()) { "Could not open selected audio file." }
            val sourceUri = candidate.sourceUri.toUri()
            val metadata = metadataReader.read(sourceUri, candidate.displayName.ifBlank { "Imported audio" })
            val mimeType = candidate.mimeType.ifBlank { metadata.mimeType }
            require(mimeType.startsWith("audio/")) { "Only audio files can be imported." }

            importDirectory.mkdirs()
            val id = UUID.randomUUID().toString()
            target = File(importDirectory, "$id.audio")
            val importedFile = requireNotNull(target)
            resolver.openInputStream(sourceUri)?.use { input ->
                importedFile.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Could not open selected audio file.")

            require(importedFile.length() > 0L) { "Selected audio file is empty." }

            Track(
                id = id,
                title = metadata.displayName.ifBlank { candidate.displayName.ifBlank { "Imported audio" } },
                artist = null,
                durationMs = metadata.durationMs,
                mimeType = mimeType,
                localUri = importedFile.absolutePath,
                sizeBytes = importedFile.length(),
            )
        }.onFailure { target?.delete() }
    }
}
