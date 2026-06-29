package com.pinna.app.library

import com.pinna.app.core.model.Track

data class ImportedTrackCandidate(
    val sourceUri: String = "",
    val displayName: String = "",
    val mimeType: String = "",
    val sizeBytes: Long = -1,
)

interface TrackImporter {
    suspend fun import(candidate: ImportedTrackCandidate): Result<Track>
}
