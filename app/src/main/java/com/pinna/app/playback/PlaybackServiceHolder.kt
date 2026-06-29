package com.pinna.app.playback

import androidx.media3.session.MediaSession

/**
 * Process-level handoff for the active [MediaSession]. The session (and its ExoPlayer) is owned by
 * [Media3PlaybackController] because listener playback needs per-request bearer headers configured on
 * the controller's data-source factory. [PinnaPlaybackService] reads the session here so it can host
 * the foreground media notification without owning the player's lifecycle.
 */
object PlaybackServiceHolder {
    @Volatile
    var session: MediaSession? = null
}
