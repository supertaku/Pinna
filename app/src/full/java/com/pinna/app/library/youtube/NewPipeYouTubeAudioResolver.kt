package com.pinna.app.library.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.stream.StreamInfo

/**
 * NewPipeExtractor-backed resolver. Initializes the extractor once with an OkHttp downloader, then for
 * a given URL extracts the stream metadata and picks the highest-bitrate audio-only stream. Runs off
 * the main thread. This is the fragile, extractor-coupled piece and is the most likely to need updates
 * when YouTube changes.
 */
class NewPipeYouTubeAudioResolver(
    downloader: Downloader,
) : YouTubeAudioResolver {

    init {
        synchronized(initLock) {
            if (!initialized) {
                NewPipe.init(downloader)
                initialized = true
            }
        }
    }

    override suspend fun resolve(url: String): Result<ResolvedRemoteAudio> = withContext(Dispatchers.IO) {
        runCatching {
            val normalized = YouTubeUrlValidator.normalize(url)
                ?: error("That doesn't look like a YouTube link.")
            val info = StreamInfo.getInfo(ServiceList.YouTube, normalized)
            val audio = info.audioStreams
                .filter { !it.content.isNullOrBlank() }
                .maxByOrNull { it.averageBitrate }
                ?: error("No downloadable audio was found for this video.")
            val format = audio.format
            ResolvedRemoteAudio(
                title = info.name.orEmpty().ifBlank { "YouTube audio" },
                uploader = info.uploaderName?.ifBlank { null },
                durationMs = info.duration.coerceAtLeast(0) * 1_000,
                streamUrl = audio.content,
                mimeType = format?.mimeType ?: "audio/mp4",
                fileExtension = format?.suffix ?: "m4a",
            )
        }
    }

    private companion object {
        val initLock = Any()

        @Volatile
        var initialized = false
    }
}
