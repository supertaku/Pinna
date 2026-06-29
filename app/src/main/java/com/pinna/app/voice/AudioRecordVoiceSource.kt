package com.pinna.app.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * Captures microphone audio as 16 kHz mono PCM in ~20 ms frames using [AudioRecord] on a high-priority
 * thread. The caller MUST hold the RECORD_AUDIO runtime permission before calling [start]. Device-only;
 * the controller's push-to-talk logic is tested against the [VoiceSource] interface with a fake.
 */
class AudioRecordVoiceSource : VoiceSource {
    private val sampleRate = SAMPLE_RATE_HZ
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val encoding = AudioFormat.ENCODING_PCM_16BIT

    @Volatile
    private var record: AudioRecord? = null

    @Volatile
    private var captureThread: Thread? = null

    @SuppressLint("MissingPermission")
    override fun start(onFrame: (ByteArray) -> Unit) {
        stop()
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
        if (minBuffer <= 0) return
        val frameBytes = FRAME_BYTES
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRate,
            channelConfig,
            encoding,
            maxOf(minBuffer, frameBytes * 4),
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return
        }
        record = recorder
        recorder.startRecording()
        val thread = Thread {
            val buffer = ByteArray(frameBytes)
            while (!Thread.currentThread().isInterrupted) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) break
                onFrame(if (read == buffer.size) buffer.copyOf() else buffer.copyOf(read))
            }
        }.apply {
            name = "PinnaMicCapture"
            priority = Thread.MAX_PRIORITY
        }
        captureThread = thread
        thread.start()
    }

    override fun stop() {
        captureThread?.interrupt()
        captureThread = null
        record?.let { recorder ->
            runCatching { recorder.stop() }
            recorder.release()
        }
        record = null
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16_000

        // 20 ms * 16000 Hz * 2 bytes/sample (16-bit) / 1000 ms = 640 bytes.
        const val FRAME_BYTES = 640
    }
}
