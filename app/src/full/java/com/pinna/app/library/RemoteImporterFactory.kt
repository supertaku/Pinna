package com.pinna.app.library

import android.content.Context
import com.pinna.app.core.model.Track
import com.pinna.app.library.youtube.NewPipeYouTubeAudioResolver
import com.pinna.app.library.youtube.OkHttpDownloader
import com.pinna.app.library.youtube.YouTubeTrackImporter
import com.pinna.app.library.youtube.YouTubeUrlValidator
import okhttp3.OkHttpClient

/**
 * Full (sideload/F-Droid) flavor: routes YouTube links to on-device extraction (NewPipeExtractor,
 * GPL-3.0) and any other direct HTTPS audio link to the license-clean importer.
 */
object RemoteImporterFactory {
    fun create(context: Context, httpClient: OkHttpClient): RemoteTrackImporter {
        val direct = DirectAudioUrlImporter(context, httpClient)
        val youtube = YouTubeTrackImporter(
            context = context,
            resolver = NewPipeYouTubeAudioResolver(OkHttpDownloader(httpClient)),
            httpClient = httpClient,
        )
        return object : RemoteTrackImporter {
            override suspend fun importFromUrl(url: String): Result<Track> =
                if (YouTubeUrlValidator.isYouTubeUrl(url)) {
                    youtube.importFromUrl(url)
                } else {
                    direct.importFromUrl(url)
                }
        }
    }
}
