package com.pinna.app.library

import android.content.Context
import okhttp3.OkHttpClient

/**
 * Play flavor: license-clean URL import only (direct HTTPS audio links). No YouTube extraction and no
 * GPL-licensed dependency, so this build is eligible for Google Play.
 */
object RemoteImporterFactory {
    fun create(context: Context, httpClient: OkHttpClient): RemoteTrackImporter =
        DirectAudioUrlImporter(context, httpClient)
}
