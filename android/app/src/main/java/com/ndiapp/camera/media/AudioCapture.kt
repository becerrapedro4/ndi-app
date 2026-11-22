package com.ndiapp.camera.media

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlin.math.abs

/**
 * Captura el micrófono como PCM float intercalado a 48 kHz.
 *
 * Usa la fuente menos procesada que el equipo admita (UNPROCESSED evita el AGC
 * y la reducción de ruido), pide el micrófono USB si hay uno conectado, aplica
 * una ganancia de entrada y publica el nivel de cada bloque para el medidor.
 */
class AudioCapture(
    context: Context,
    private val onBuffer: (pcm: FloatArray, samples: Int, channels: Int, sampleRate: Int) -> Unit,
    private val onLevel: ((left: Float, right: Float) -> Unit)? = null
) {

    companion object {
        private const val TAG = "AudioCapture"
        private const val SAMPLE_RATE = 48_000
        private const val CHANNELS = 2

        /** De la menos procesada a la más procesada: se usa la primera que abra. */
        private val sourceCandidates = intArrayOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.CAMCORDER
        )
    }

    private val appContext = context.applicationContext
    private var thread: Thread? = null
    private var record: AudioRecord? = null

    @Volatile
    private var running = false

    /** Ganancia lineal de entrada (1 = sin cambios). */
    @Volatile
    var gain: Float = 1f

    /** Entrada en uso, para mostrarla en el visor («USB», «Sin procesar»…). */
    @Volatile
    var inputLabel: String = ""
        private set

    fun start() {
        if (running) return
        val recorder = openRecorder() ?: return
        record = recorder
        running = true
        recorder.startRecording()

        thread = Thread {
            // Un solo búfer reutilizado: NDI y el AAC copian el bloque antes de
            // volver, así no se reserva memoria cada 20 ms.
            val buffer = FloatArray(960 * CHANNELS)
            while (running) {
                val read = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (read > 0) {
                    applyGain(buffer, read)
                    publishLevel(buffer, read)
                    onBuffer(buffer, read / CHANNELS, CHANNELS, SAMPLE_RATE)
                }
            }
        }.also {
            it.name = "ndi-audio"
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
    }

    private fun openRecorder(): AudioRecord? {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        if (minBuffer <= 0) {
            Log.w(TAG, "AudioRecord no soporta PCM float")
            return null
        }
        val bufferSize = maxOf(minBuffer, 8192) * 2
        val usb = usbInput()
        for (source in sourceCandidates) {
            val recorder = runCatching {
                AudioRecord(
                    source,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_FLOAT,
                    bufferSize
                )
            }.getOrNull() ?: continue
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                continue
            }
            if (usb != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                runCatching { recorder.setPreferredDevice(usb) }
                    .onFailure { Log.w(TAG, "Sin micrófono USB: ${it.message}") }
            }
            inputLabel = if (usb != null) "USB" else sourceName(source)
            return recorder
        }
        Log.w(TAG, "Ninguna fuente de audio disponible")
        return null
    }

    /** Micrófono USB conectado, si lo hay. */
    private fun usbInput(): AudioDeviceInfo? {
        val manager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return null
        return runCatching {
            manager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { device ->
                device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    device.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
            }
        }.getOrNull()
    }

    private fun sourceName(source: Int): String = when (source) {
        MediaRecorder.AudioSource.UNPROCESSED -> "Sin procesar"
        MediaRecorder.AudioSource.MIC -> "Micrófono"
        else -> "Cámara"
    }

    /** Ganancia de entrada con saturación dura: sube el nivel sin distorsionar. */
    private fun applyGain(buffer: FloatArray, count: Int) {
        val value = gain.coerceIn(0.1f, 8f)
        if (abs(value - 1f) < 0.01f) return
        for (index in 0 until count) {
            buffer[index] = (buffer[index] * value).coerceIn(-1f, 1f)
        }
    }

    /** Pico por canal del bloque, para el medidor del visor. */
    private fun publishLevel(buffer: FloatArray, count: Int) {
        val callback = onLevel ?: return
        var left = 0f
        var right = 0f
        var index = 0
        while (index + 1 < count) {
            left = maxOf(left, abs(buffer[index]))
            right = maxOf(right, abs(buffer[index + 1]))
            index += CHANNELS
        }
        callback(left, right)
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
