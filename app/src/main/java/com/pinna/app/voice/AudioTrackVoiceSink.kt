package com.pinna.app.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Plays incoming push-to-talk PCM frames (16 kHz mono) over a streaming [AudioTrack]. Uses the
 * voice-communication usage so the system ducks music while voice plays. Device-only.
 */
class AudioTrackVoiceSink : VoiceSink {
    @Volatile
    private var track: AudioTrack? = null

    @Synchronized
    private fun ensureTrack(): AudioTrack {
        track?.let { return it }
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(AudioRecordVoiceSource.FRAME_BYTES * 4)
        val created = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE_HZ)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(minBuffer)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        created.play()
        track = created
        return created
    }

    override fun play(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        runCatching { ensureTrack().write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING) }
    }

    override fun release() {
        track?.let { active ->
            runCatching { active.stop() }
            active.release()
        }
        track = null
    }

    private companion object {
        const val SAMPLE_RATE_HZ = AudioRecordVoiceSource.SAMPLE_RATE_HZ
    }
}
