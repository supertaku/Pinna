package com.pinna.app

import android.app.Application
import com.pinna.app.runtime.PinnaRuntime

/**
 * Process owner for the active room and media session. Activities are disposable UI clients: a
 * configuration change or task recreation must not stop hosting, close listener sockets, or release
 * playback while the foreground media service is keeping the process alive.
 */
class PinnaApplication : Application() {
    val runtime: PinnaRuntime by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { PinnaRuntime(this) }
}
