package com.ndiapp.camera.media

import android.content.Context
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import com.ndiapp.camera.ndi.NdiNative
import com.ndiapp.camera.ndi.NdiSender
import kotlin.math.log10

/**
 * Fuente NDI de solo audio: el micrófono o un archivo de audio.
 *
 * NDI necesita algo de vídeo para que los receptores muestren la fuente, así que
 * se manda un cuadro negro a 8 fps; el audio va por el canal de audio de la
 * misma fuente.
 */
class AudioNdiSource(private val context: Context) {

    var onStatus: ((String) -> Unit)? = null

    /** Datos técnicos de la captura del sistema (muestras, nivel, formato). */
    var onSystemDetail: ((String) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private var sender: NdiSender? = null
    private var mic: AudioCapture? = null
    private var file: MediaAudioStream? = null
    private var system: SystemAudioCapture? = null

    @Volatile
    private var audioChunks = 0

    private val stillWidth = 1280
    private val stillHeight = 720
    private val still = ByteArray(stillWidth * stillHeight * 4).apply {
        var index = 3
        while (index < size) {
            this[index] = 0xFF.toByte()
            index += 4
        }
    }

    private var lastSystemMessage = ""

    /** Revisa cada 2 s si llegan muestras y con qué nivel, para decir el motivo exacto. */
    private val diagnose = object : Runnable {
        override fun run() {
            val capture = system ?: return
            val buffers = capture.buffers
            val peak = capture.takePeak()
            val db = if (peak > 0f) {
                (20.0 * log10(peak.toDouble())).toInt().coerceIn(-120, 0)
            } else {
                -120
            }
            onSystemDetail?.invoke(
                if (buffers == 0L) {
                    "Muestras recibidas: 0 · ${capture.formatLabel}\n\nSi la captura está activa pero no hay sonido: deja sonando algo en Spotify u otra app, y si sigue así, detén y vuelve a iniciar la captura en la app."
                } else {
                    "Muestras: $buffers · nivel $db dBFS · ${capture.formatLabel}"
                }
            )
            val failure = capture.failure
            val message = when {
                failure != null -> failure
                buffers == 0L ->
                    "La captura está activa pero Android no entrega muestras: deja sonando algo y, " +
                        "si sigue así, detén y vuelve a iniciar"

                peak < 0.0005f ->
                    "Llega silencio: la app que suena bloquea su audio (allowAudioPlaybackCapture) " +
                        "o no está reproduciendo nada. Prueba con YouTube o un reproductor"

                else -> "Enviando el audio del sistema por NDI · nivel $db dBFS"
            }
            if (message != lastSystemMessage) {
                lastSystemMessage = message
                onStatus?.invoke(message)
            }
            handler.postDelayed(this, 2000)
        }
    }

    private val pump = object : Runnable {
        override fun run() {
            if (sender == null) return
            sender?.sendVideo(still, stillWidth, stillHeight, 8)
            handler.postDelayed(this, 125)
        }
    }

    val isRunning: Boolean
        get() = sender != null

    /** Emite el micrófono del dispositivo. */
    fun startMicrophone(name: String): Boolean {
        stop()
        val created = createSender(name) ?: return false
        sender = created
        val capture = AudioCapture(context, onBuffer = { pcm, samples, channels, rate ->
            sender?.sendAudio(pcm, samples, channels, rate)
        })
        capture.start()
        mic = capture
        handler.post(pump)
        onStatus?.invoke("Enviando el micrófono por NDI")
        return true
    }

    /** Emite la pista de audio de un archivo, en bucle. */
    fun startFile(uri: Uri, name: String): Boolean {
        stop()
        val created = createSender(name) ?: return false
        val stream = MediaAudioStream(context) { pcm, samples, channels, rate ->
            sender?.sendAudio(pcm, samples, channels, rate)
        }
        if (!stream.open(uri)) {
            created.close()
            onStatus?.invoke("Ese archivo no tiene pista de audio")
            return false
        }
        sender = created
        stream.setLooping(true)
        stream.seekTo(0)
        stream.play()
        file = stream
        handler.post(pump)
        onStatus?.invoke("Enviando el archivo por NDI")
        return true
    }

    /** Emite el audio que reproducen otras apps (Android 10 o superior). */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun startSystemAudio(projection: MediaProjection, name: String): Boolean {
        stop()
        val created = createSender(name) ?: return false
        sender = created
        audioChunks = 0
        val capture = SystemAudioCapture(
            projection,
            onError = { message ->
                lastSystemMessage = message
                onStatus?.invoke(message)
            }
        ) { pcm, samples, channels, rate ->
            audioChunks++
            sender?.sendAudio(pcm, samples, channels, rate)
        }
        if (!capture.start()) {
            val reason = capture.failure ?: "No se pudo capturar el audio del sistema"
            created.close()
            sender = null
            onStatus?.invoke(reason)
            return false
        }
        system = capture
        lastSystemMessage = ""
        handler.post(pump)
        handler.postDelayed(diagnose, 1500)
        return true
    }

    fun stop() {
        handler.removeCallbacks(pump)
        handler.removeCallbacks(diagnose)
        if (system != null) {
            lastSystemMessage = ""
            onSystemDetail?.invoke("")
        }
        mic?.stop()
        mic = null
        system?.stop()
        system = null
        file?.close()
        file = null
        sender?.close()
        sender = null
        audioChunks = 0
    }

    private fun createSender(name: String): NdiSender? {
        if (!NdiNative.init()) {
            onStatus?.invoke("El runtime de NDI no está disponible")
            return null
        }
        val created = NdiSender(name)
        if (!created.isValid) {
            onStatus?.invoke("No se pudo crear la fuente NDI")
            return null
        }
        return created
    }
}
