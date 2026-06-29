package com.pinna.app.playback

import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Foreground media service that hosts the active [MediaSession] so playback continues with
 * lock-screen/notification controls while the app is backgrounded. The session and its ExoPlayer are
 * created and released by [Media3PlaybackController]; this service only surfaces it and provides the
 * foreground notification. It is never exported.
 */
class PinnaPlaybackService : MediaSessionService() {
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        PlaybackServiceHolder.session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = PlaybackServiceHolder.session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }
}
