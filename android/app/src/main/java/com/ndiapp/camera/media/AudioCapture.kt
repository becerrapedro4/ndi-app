package com.ndiapp.camera.media

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/** Captura el micrófono como PCM float intercalado a 48 kHz. */
class AudioCapture(
    private val onBuffer: (pcm: FloatArray, samples: Int, channels: Int, sampleRate: Int) -> Unit
) {

    companion object {
        private const val TAG = "AudioCapture"
        private const val SAMPLE_RATE = 48_000
        private const val CHANNELS = 2
    }

    private var thread: Thread? = null
    private var record: AudioRecord? = null

    @Volatile
    private var running = false

    fun start() {
        if (running) return
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        if (minBuffer <= 0) {
            Log.w(TAG, "AudioRecord no soporta PCM float")
            return
        }
        val bufferSize = maxOf(minBuffer, 8192) * 2
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.CAMCORDER,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_FLOAT,
                bufferSize
            )
        } catch (t: Throwable) {
            Log.w(TAG, "No se pudo crear AudioRecord", t)
            return
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            Log.w(TAG, "AudioRecord no inicializado")
            return
        }

        record = recorder
        running = true
        recorder.startRecording()

        thread = Thread {
            val buffer = FloatArray(960 * CHANNELS)
            while (running) {
                val read = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (read > 0) {
                    onBuffer(buffer.copyOf(read), read / CHANNELS, CHANNELS, SAMPLE_RATE)
                }
            }
        }.also {
            it.name = "ndi-audio"
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
    }

    fun stop() {
        running = false
        thread?.let { runCatching { it.join(400) } }
        thread = null
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
    }
}
