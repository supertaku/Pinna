package com.pinna.app.voice

/**
 * Captures microphone audio as raw PCM frames while talking. Implementations run capture off the main
 * thread and invoke [onFrame] per frame. Kept behind an interface so the controller's push-to-talk
 * logic can be unit tested without the Android microphone.
 */
interface VoiceSource {
    fun start(onFrame: (ByteArray) -> Unit)
    fun stop()
}

/**
 * Plays incoming push-to-talk PCM frames over the current audio route. Implementations mix voice on
 * top of music playback.
 */
interface VoiceSink {
    fun play(pcm: ByteArray)
    fun release()
}
