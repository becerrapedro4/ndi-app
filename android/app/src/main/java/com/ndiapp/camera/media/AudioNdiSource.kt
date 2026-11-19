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

/**
 * Fuente NDI de solo audio: el micrófono o un archivo de audio.
 *
 * NDI necesita algo de vídeo para que los receptores muestren la fuente, así que
 * se manda un cuadro negro a 8 fps; el audio va por el canal de audio de la
 * misma fuente.
 */
class AudioNdiSource(private val context: Context) {

    var onStatus: ((String) -> Unit)? = null

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
        val capture = AudioCapture { pcm, samples, channels, rate ->
            sender?.sendAudio(pcm, samples, channels, rate)
        }
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
        val capture = SystemAudioCapture(projection) { pcm, samples, channels, rate ->
            audioChunks++
            sender?.sendAudio(pcm, samples, channels, rate)
        }
        if (!capture.start()) {
            created.close()
            sender = null
            onStatus?.invoke("No se pudo capturar el audio del sistema")
            return false
        }
        system = capture
        handler.post(pump)
        onStatus?.invoke("Enviando el audio del sistema por NDI")
        handler.postDelayed({
            if (system === capture && audioChunks == 0) {
                onStatus?.invoke(
                    "No llega audio del sistema: la app que suena puede bloquear la captura"
                )
            }
        }, 4000)
        return true
    }

    fun stop() {
        handler.removeCallbacks(pump)
        mic?.stop()
        mic = null
        system?.stop()
        system = null
        file?.close()
        file = null
        sender?.close()
        sender = null
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
