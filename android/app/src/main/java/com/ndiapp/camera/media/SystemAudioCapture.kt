package com.ndiapp.camera.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Captura el audio que reproducen otras apps (vídeo, música, navegador) mientras se
 * comparte la pantalla, y lo entrega como PCM float intercalado a 48 kHz.
 *
 * Requiere Android 10 (API 29) y una [MediaProjection] activa. Las apps pueden
 * excluir su audio de la captura; en ese caso se oye silencio, no un error.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class SystemAudioCapture(
    private val projection: MediaProjection,
    private val onBuffer: (pcm: FloatArray, samples: Int, channels: Int, sampleRate: Int) -> Unit
) {

    companion object {
        private const val TAG = "SystemAudioCapture"
        private const val SAMPLE_RATE = 48_000
        private const val CHANNELS = 2
        private const val CHANNEL_MASK = AudioFormat.CHANNEL_IN_STEREO
    }

    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private var floatEncoding = true

    @Volatile
    private var running = false

    fun start(): Boolean {
        if (running) return true

        val config = try {
            AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
        } catch (t: Throwable) {
            Log.w(TAG, "No se pudo configurar la captura de reproducción", t)
            return false
        }

        val recorder = open(config, AudioFormat.ENCODING_PCM_FLOAT)
            ?: open(config, AudioFormat.ENCODING_PCM_16BIT)?.also { floatEncoding = false }
            ?: return false

        record = recorder
        val started = runCatching { recorder.startRecording() }.isSuccess &&
            recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING
        if (!started) {
            Log.w(TAG, "AudioRecord de reproducción no arrancó")
            stop()
            return false
        }
        running = true

        thread = Thread {
            if (floatEncoding) {
                val buffer = FloatArray(1024 * CHANNELS)
                while (running) {
                    val read = runCatching {
                        recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    }.getOrDefault(-1)
                    if (read > 0) {
                        onBuffer(buffer.copyOf(read), read / CHANNELS, CHANNELS, SAMPLE_RATE)
                    } else if (read < 0) {
                        break
                    }
                }
            } else {
                val buffer = ShortArray(1024 * CHANNELS)
                while (running) {
                    val read = runCatching {
                        recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    }.getOrDefault(-1)
                    if (read > 0) {
                        val pcm = FloatArray(read)
                        for (i in 0 until read) pcm[i] = buffer[i] / 32768f
                        onBuffer(pcm, read / CHANNELS, CHANNELS, SAMPLE_RATE)
                    } else if (read < 0) {
                        break
                    }
                }
            }
        }.also {
            it.name = "ndi-system-audio"
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
        return true
    }

    private fun open(config: AudioPlaybackCaptureConfiguration, encoding: Int): AudioRecord? {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_MASK, encoding)
        if (minBuffer <= 0) return null
        val recorder = runCatching {
            AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_MASK)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuffer, 8192) * 2)
                .setAudioPlaybackCaptureConfig(config)
                .build()
        }.onFailure { Log.w(TAG, "AudioRecord no disponible (encoding $encoding)", it) }
            .getOrNull() ?: return null

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return null
        }
        return recorder
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
