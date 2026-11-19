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
 * Captura el audio que reproducen otras apps (vídeo, música, navegador) y lo
 * entrega como PCM float intercalado a 48 kHz.
 *
 * Requiere Android 10 (API 29) y una [MediaProjection] activa. Las apps pueden
 * excluir su audio de la captura; en ese caso se oye silencio, no un error, así
 * que además de las muestras se expone [buffers], [takePeak] y [failure] para
 * poder decir en la interfaz qué está pasando exactamente.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class SystemAudioCapture(
    private val projection: MediaProjection,
    /** Motivo exacto de un fallo de captura, para mostrarlo en la interfaz. */
    private val onError: ((String) -> Unit)? = null,
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

    /** Búferes entregados por Android desde el inicio (0 = no llegan muestras). */
    @Volatile
    var buffers: Long = 0L
        private set

    /** Motivo del fallo, o null mientras la captura siga viva. */
    @Volatile
    var failure: String? = null
        private set

    @Volatile
    private var peakWindow: Float = 0f

    /** Descripción del formato aceptado por el dispositivo. */
    val formatLabel: String
        get() = "48 kHz estéreo · ${if (floatEncoding) "float" else "16 bits"}"

    /** Nivel máximo recibido desde la llamada anterior, en valor absoluto. */
    fun takePeak(): Float {
        val peak = peakWindow
        peakWindow = 0f
        return peak
    }

    fun start(): Boolean {
        if (running) return true
        buffers = 0L
        failure = null
        peakWindow = 0f

        val config = try {
            AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
        } catch (t: Throwable) {
            Log.w(TAG, "No se pudo configurar la captura de reproducción", t)
            fail(
                if (t is SecurityException) {
                    "Android denegó la captura de reproducción: falta el permiso de micrófono " +
                        "(RECORD_AUDIO) o la proyección ya no es válida"
                } else {
                    "No se pudo configurar la captura de reproducción (${t.javaClass.simpleName})"
                }
            )
            return false
        }

        val recorder = open(config, AudioFormat.ENCODING_PCM_FLOAT)
            ?: open(config, AudioFormat.ENCODING_PCM_16BIT)?.also { floatEncoding = false }
        if (recorder == null) {
            fail("Este dispositivo no permite capturar el audio de otras apps (AudioRecord no se pudo crear)")
            return false
        }

        record = recorder
        val startError = runCatching { recorder.startRecording() }.exceptionOrNull()
        val started = startError == null && recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING
        if (!started) {
            Log.w(TAG, "AudioRecord de reproducción no arrancó", startError)
            fail(
                if (startError is SecurityException) {
                    "Android denegó el inicio de la captura: el permiso de micrófono no está concedido"
                } else {
                    "Android no dejó iniciar la captura de reproducción" +
                        (startError?.javaClass?.simpleName?.let { " ($it)" } ?: "")
                }
            )
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
                    }.getOrDefault(Int.MIN_VALUE)
                    when {
                        read > 0 -> {
                            var peak = 0f
                            for (i in 0 until read) {
                                val value = if (buffer[i] < 0f) -buffer[i] else buffer[i]
                                if (value > peak) peak = value
                            }
                            if (peak > peakWindow) peakWindow = peak
                            buffers++
                            onBuffer(buffer.copyOf(read), read / CHANNELS, CHANNELS, SAMPLE_RATE)
                        }

                        read == 0 -> Unit

                        read == Int.MIN_VALUE -> {
                            fail("Android rechazó la lectura del audio de reproducción")
                            break
                        }

                        else -> {
                            fail(describe(read))
                            break
                        }
                    }
                }
            } else {
                val buffer = ShortArray(1024 * CHANNELS)
                while (running) {
                    val read = runCatching {
                        recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    }.getOrDefault(Int.MIN_VALUE)
                    when {
                        read > 0 -> {
                            val pcm = FloatArray(read)
                            var peak = 0f
                            for (i in 0 until read) {
                                val sample = buffer[i] / 32768f
                                pcm[i] = sample
                                val value = if (sample < 0f) -sample else sample
                                if (value > peak) peak = value
                            }
                            if (peak > peakWindow) peakWindow = peak
                            buffers++
                            onBuffer(pcm, read / CHANNELS, CHANNELS, SAMPLE_RATE)
                        }

                        read == 0 -> Unit

                        read == Int.MIN_VALUE -> {
                            fail("Android rechazó la lectura del audio de reproducción")
                            break
                        }

                        else -> {
                            fail(describe(read))
                            break
                        }
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

    private fun describe(code: Int): String = when (code) {
        AudioRecord.ERROR_INVALID_OPERATION ->
            "Android rechazó la lectura del audio (ERROR_INVALID_OPERATION): detén y vuelve a iniciar la captura"

        AudioRecord.ERROR_BAD_VALUE ->
            "Android rechazó el formato de audio (ERROR_BAD_VALUE)"

        AudioRecord.ERROR_DEAD_OBJECT ->
            "Android cerró la captura de audio (ERROR_DEAD_OBJECT): vuelve a iniciar"

        AudioRecord.ERROR ->
            "Error al leer el audio de reproducción (ERROR)"

        else -> "La captura de audio falló (código $code)"
    }

    private fun fail(message: String) {
        failure = message
        Log.w(TAG, message)
        onError?.invoke(message)
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
