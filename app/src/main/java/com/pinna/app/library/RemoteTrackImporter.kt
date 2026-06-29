package com.pinna.app.library

import com.pinna.app.core.model.Track

/**
 * Imports a track from a remote URL (e.g. a pasted link) into app-private storage. Parallel to
 * [TrackImporter] (which handles Storage Access Framework local files) so the controller can dispatch
 * by source. Implementations download into the same private library directory and return a [Track].
 */
interface RemoteTrackImporter {
    suspend fun importFromUrl(url: String): Result<Track>
}
