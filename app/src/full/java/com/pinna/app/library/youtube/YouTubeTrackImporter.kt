package com.pinna.app.library.youtube

import android.content.Context
import com.pinna.app.core.model.Track
import com.pinna.app.library.RemoteTrackImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.UUID

/**
 * Imports a YouTube link's audio into app-private storage: validate URL → resolve best audio stream →
 * download into filesDir/pinna-tracks → return a [Track]. The native container (m4a/opus/webm) is
 * stored as-is; no transcoding. Downloads are HTTPS to Google's CDN.
 */
class YouTubeTrackImporter(
    context: Context,
    private val resolver: YouTubeAudioResolver,
    private val httpClient: OkHttpClient = OkHttpClient(),
) : RemoteTrackImporter {
    private val appContext = context.applicationContext
    private val importDirectory = File(appContext.filesDir, "pinna-tracks")

    override suspend fun importFromUrl(url: String): Result<Track> = withContext(Dispatchers.IO) {
        var partialFile: File? = null
        runCatching {
            require(YouTubeUrlValidator.isYouTubeUrl(url)) { "Paste a valid YouTube link." }
            val resolved = resolver.resolve(url).getOrElse { throw it }
            require(resolved.streamUrl.isNotBlank()) { "No downloadable audio was found." }
            require(resolved.streamUrl.startsWith("https://", ignoreCase = true)) {
                "YouTube audio downloads must use HTTPS."
            }

            importDirectory.mkdirs()
            val id = UUID.randomUUID().toString()
            val extension = resolved.fileExtension.ifBlank { "m4a" }
            val target = File(importDirectory, "$id.$extension")
            partialFile = target
            httpClient.newCall(Request.Builder().url(resolved.streamUrl).build()).execute().use { response ->
                require(response.isSuccessful) { "Download failed (HTTP ${response.code})." }
                require(response.request.url.isHttps) { "YouTube audio downloads must remain on HTTPS." }
                val body = response.body ?: error("Empty download response.")
                target.outputStream().use { output -> body.byteStream().copyTo(output) }
            }
            require(target.length() > 0L) { "Downloaded audio was empty." }

            Track(
                id = id,
                title = resolved.title.ifBlank { "YouTube audio" },
                artist = resolved.uploader,
                durationMs = resolved.durationMs.coerceAtLeast(0),
                mimeType = resolved.mimeType.ifBlank { "audio/mp4" },
                localUri = target.absolutePath,
                sizeBytes = target.length(),
            )
        }.onFailure { partialFile?.delete() }
    }
}
