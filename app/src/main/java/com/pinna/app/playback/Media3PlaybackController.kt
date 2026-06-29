package com.pinna.app.playback

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import com.pinna.app.core.model.PlaybackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

class Media3PlaybackController(context: Context) : PlaybackController {
    private val appContext = context.applicationContext
    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
    private val player = ExoPlayer.Builder(appContext)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(appContext)
                .setDataSourceFactory(httpDataSourceFactory),
        )
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ true,
        )
        .build()
    private val mediaSession = MediaSession.Builder(appContext, player).build()
    private val _snapshots = MutableStateFlow(PlaybackSnapshot())

    override val snapshots: StateFlow<PlaybackSnapshot> = _snapshots

    init {
        PlaybackServiceHolder.session = mediaSession
        player.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    publishSnapshot()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    publishSnapshot()
                }
            },
        )
    }

    override fun play(trackId: String, uri: String, positionMs: Long, requestHeaders: Map<String, String>) {
        httpDataSourceFactory.setDefaultRequestProperties(requestHeaders)
        val mediaUri = if (uri.startsWith("http://") || uri.startsWith("https://") || uri.startsWith("content://")) {
            uri
        } else {
            File(uri).toURI().toString()
        }
        player.setMediaItem(MediaItem.fromUri(mediaUri))
        player.prepare()
        player.seekTo(positionMs)
        player.play()
        ensurePlaybackServiceStarted()
        _snapshots.value = PlaybackSnapshot(PlaybackState.PLAYING, trackId, positionMs)
    }

    override fun pause() {
        player.pause()
        publishSnapshot(stateOverride = PlaybackState.PAUSED)
    }

    override fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
        _snapshots.value = _snapshots.value.copy(positionMs = positionMs)
    }

    override fun setPlaybackSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
    }

    override fun stop() {
        player.stop()
        _snapshots.value = PlaybackSnapshot()
    }

    fun release() {
        PlaybackServiceHolder.session = null
        runCatching { appContext.stopService(Intent(appContext, PinnaPlaybackService::class.java)) }
        mediaSession.release()
        player.release()
    }

    private fun ensurePlaybackServiceStarted() {
        // Best-effort: hosts the foreground media notification so playback survives backgrounding.
        // Guarded so a service-start failure can never break in-app playback.
        runCatching {
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, PinnaPlaybackService::class.java),
            )
        }
    }

    private fun publishSnapshot(stateOverride: PlaybackState? = null) {
        val state = stateOverride ?: when {
            player.isPlaying -> PlaybackState.PLAYING
            player.playbackState == Player.STATE_BUFFERING -> PlaybackState.BUFFERING
            player.playbackState == Player.STATE_ENDED -> PlaybackState.ENDED
            else -> PlaybackState.PAUSED
        }
        _snapshots.value = _snapshots.value.copy(
            state = state,
            positionMs = player.currentPosition.coerceAtLeast(0),
            bufferedPositionMs = player.bufferedPosition.coerceAtLeast(0),
        )
    }
}
