package com.pinna.app.playback

import android.app.Service
import android.content.Intent
import android.os.IBinder

class PinnaPlaybackService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
