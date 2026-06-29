package com.pinna.app.library

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.pinna.app.core.model.Track

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String?,
    val durationMs: Long,
    val mimeType: String,
    val localPath: String,
    val sizeBytes: Long,
    val createdAtEpochMillis: Long,
    val lastPlayedAtEpochMillis: Long,
) {
    fun toTrack(): Track = Track(
        id = id,
        title = title,
        artist = artist,
        durationMs = durationMs,
        mimeType = mimeType,
        localUri = localPath,
        sizeBytes = sizeBytes,
    )

    companion object {
        fun fromTrack(
            track: Track,
            createdAtEpochMillis: Long,
            lastPlayedAtEpochMillis: Long = createdAtEpochMillis,
        ): TrackEntity = TrackEntity(
            id = track.id,
            title = track.title,
            artist = track.artist,
            durationMs = track.durationMs,
            mimeType = track.mimeType,
            localPath = track.localUri,
            sizeBytes = track.sizeBytes,
            createdAtEpochMillis = createdAtEpochMillis,
            lastPlayedAtEpochMillis = lastPlayedAtEpochMillis,
        )
    }
}
