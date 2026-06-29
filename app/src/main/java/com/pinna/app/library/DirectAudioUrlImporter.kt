package com.pinna.app.library

import android.content.Context
import android.media.MediaMetadataRetriever
import com.pinna.app.core.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLDecoder
import java.util.UUID

/**
 * License-clean URL import: downloads a direct HTTPS audio link (podcast episode, Internet Archive
 * file, Creative-Commons host, the user's own cloud) into app-private storage. No third-party
 * extraction library, no streaming-service scraping — the user supplies a direct link they have the
 * right to use, exactly like the local-file import. Available in every flavor.
 */
class DirectAudioUrlImporter(
    context: Context,
    private val httpClient: OkHttpClient = OkHttpClient(),
) : RemoteTrackImporter {
    private val appContext = context.applicationContext
    private val importDirectory = File(appContext.filesDir, "pinna-tracks")

    override suspend fun importFromUrl(url: String): Result<Track> = withContext(Dispatchers.IO) {
        runCatching {
            require(AudioUrlValidator.isHttpsUrl(url)) { "Paste a direct https link to an audio file." }

            importDirectory.mkdirs()
            val id = UUID.randomUUID().toString()
            var extension = "audio"
            var mimeType = "audio/*"
            val target = File(importDirectory, "$id.placeholder")

            httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                require(response.isSuccessful) { "Download failed (HTTP ${response.code})." }
                val contentType = response.header("Content-Type")
                require(AudioUrlValidator.looksLikeAudio(contentType, url)) {
                    "That link is not a direct audio file."
                }
                extension = AudioUrlValidator.resolveExtension(url, contentType)
                mimeType = contentType?.substringBefore(';')?.trim()?.ifBlank { null } ?: "audio/*"
                val body = response.body ?: error("Empty download response.")
                target.outputStream().use { output -> body.byteStream().copyTo(output) }
            }

            require(target.length() > 0L) { "Downloaded audio was empty." }
            val finalFile = File(importDirectory, "$id.$extension")
            if (target.renameTo(finalFile).not()) {
                target.copyTo(finalFile, overwrite = true)
                target.delete()
            }

            Track(
                id = id,
                title = titleFor(url),
                artist = null,
                durationMs = durationMsOf(finalFile),
                mimeType = mimeType,
                localUri = finalFile.absolutePath,
                sizeBytes = finalFile.length(),
            )
        }
    }

    private fun titleFor(url: String): String {
        val path = runCatching { java.net.URI(url.trim()).path }.getOrNull().orEmpty()
        val name = path.substringAfterLast('/').substringBeforeLast('.')
        val decoded = runCatching { URLDecoder.decode(name, "UTF-8") }.getOrDefault(name)
        return decoded.ifBlank { "Imported audio" }
    }

    private fun durationMsOf(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            retriever.release()
        }
    }
}
