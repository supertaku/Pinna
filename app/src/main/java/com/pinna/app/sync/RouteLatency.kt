package com.pinna.app.sync

enum class AudioRoute {
    WIRED,
    SPEAKER,
    BLUETOOTH,
    UNKNOWN,
}

/**
 * Bluetooth audio output adds variable codec/transport latency that local-network sync cannot
 * compensate for. This advisor maps the active output route to a user-facing warning so the
 * listener room can surface a clear diagnostic. Pure so it can be unit tested.
 */
object RouteLatencyAdvisor {
    fun warning(route: AudioRoute): String? = when (route) {
        AudioRoute.BLUETOOTH ->
            "Bluetooth output adds delay. Use wired or speaker output for tighter sync."
        AudioRoute.WIRED,
        AudioRoute.SPEAKER,
        AudioRoute.UNKNOWN,
        -> null
    }
}
