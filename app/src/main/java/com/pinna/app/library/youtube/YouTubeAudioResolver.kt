package com.pinna.app.library.youtube

/**
 * Resolved audio-only stream for a remote video, produced by [YouTubeAudioResolver] before download.
 */
data class ResolvedRemoteAudio(
    val title: String,
    val uploader: String?,
    val durationMs: Long,
    val streamUrl: String,
    val mimeType: String,
    val fileExtension: String,
)

/**
 * Resolves a YouTube watch URL to its best audio-only stream. Network-bound; kept behind an interface
 * so the importer can be exercised with a fake and the (fragile, extractor-specific) implementation
 * stays isolated.
 */
interface YouTubeAudioResolver {
    suspend fun resolve(url: String): Result<ResolvedRemoteAudio>
}
