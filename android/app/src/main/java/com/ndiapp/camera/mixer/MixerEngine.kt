package com.ndiapp.camera.mixer

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ndiapp.camera.media.AudioCapture
import com.ndiapp.camera.ndi.NdiNative
import com.ndiapp.camera.ndi.NdiSender
import java.io.File
import java.nio.ByteBuffer

/**
 * Mezclador de dos entradas con salida NDI.
 *
 * El vídeo de cada entrada llega como RGBA y se entrega al compositor GL, que
 * compone el programa con su propio reloj. Nada de lo que entra se manda directo
 * a NDI: la única salida es el cuadro compuesto.
 *
 * El audio se mezcla aparte, con su propio reloj: el micrófono del teléfono (A)
 * y el audio de la fuente NDI (B) salen sumados por el canal de audio de la
 * misma fuente NDI del programa.
 */
class MixerEngine(private val context: Context) {

    companion object {
        private const val TAG = "MixerEngine"
        private const val PREVIEW_INTERVAL_MS = 120L
    }

    /** Cuadro del programa para la vista previa de la interfaz (hilo principal). */
    var onPreview: ((Bitmap) -> Unit)? = null

    var onStatus: ((String) -> Unit)? = null

    /** Niveles de audio para los medidores; llega en el hilo principal. */
    var onAudioLevels: ((AudioMixer.Levels) -> Unit)? = null

    @Volatile
    var running = false
        private set

    /** Entrada que está en el aire: [GlCompositor.INPUT_A] o [GlCompositor.INPUT_B]. */
    @Volatile
    var program: Int = GlCompositor.INPUT_A
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private var sender: NdiSender? = null
    private var compositor: GlCompositor? = null
    private val audio = AudioMixer()
    private var microphone: AudioCapture? = null
    private var fps = 30
    private var name = "NDI App (Mezcla)"

    private var previewBitmap: Bitmap? = null
    private var previewBuffer: ByteBuffer? = null
    private var previewAt = 0L

    /** Grabación del programa, con el vídeo compuesto y la mezcla de audio. */
    private var recorder: ProgramRecorder? = null
    private var lastRecording: File? = null

    /** Estado del keyer, para aplicarlo al compositor cuando arranca. */
    private var keyMode = GlCompositor.KEY_OFF
    private var keyRed = 0f
    private var keyGreen = 1f
    private var keyBlue = 0f
    private var keySimilarity = 0.18f
    private var keySoftness = 0.12f

    val recording: Boolean
        get() = recorder != null

    /** Segundos de la grabación en curso, para el contador. */
    val recordingSeconds: Int
        get() = recorder?.elapsedSeconds ?: 0

    /** Último archivo grabado (o el que se está grabando). */
    val recordingPath: String?
        get() = (recorder?.file ?: lastRecording)?.absolutePath

    val mix: Float
        get() = compositor?.mix ?: 0f

    val cameraFrames: Long
        get() = compositor?.framesA ?: 0L

    val ndiFrames: Long
        get() = compositor?.framesB ?: 0L

    /** Entrada del micrófono en uso («USB», «Sin procesar»…), vacía si no abrió. */
    val micInput: String
        get() = microphone?.inputLabel.orEmpty()

    /** Capa de B sobre A en el programa: MIX, PiP u OVERLAY. */
    val layerMode: Int
        get() = compositor?.layerMode ?: GlCompositor.LAYER_MIX

    val layerScale: Float
        get() = compositor?.layerScale ?: 0.34f

    val layerAnchor: Int
        get() = compositor?.layerAnchor ?: GlCompositor.ANCHOR_BOTTOM_RIGHT

    val layerOpacity: Float
        get() = compositor?.layerOpacity ?: 1f

    val layerBorder: Boolean
        get() = compositor?.layerBorder ?: true

    fun setLayerMode(mode: Int) {
        compositor?.setLayerMode(mode)
    }

    fun setLayerScale(scale: Float) {
        compositor?.setLayerScale(scale)
    }

    fun setLayerAnchor(anchor: Int) {
        compositor?.setLayerAnchor(anchor)
    }

    fun setLayerOpacity(opacity: Float) {
        compositor?.setLayerOpacity(opacity)
    }

    fun setLayerBorder(visible: Boolean) {
        compositor?.setLayerBorder(visible)
    }

    /** Keyer sobre la entrada B; [mode] es KEY_OFF, KEY_CHROMA o KEY_LUMA. */
    fun setKey(
        mode: Int,
        red: Float,
        green: Float,
        blue: Float,
        similarity: Float,
        softness: Float
    ) {
        keyMode = mode
        keyRed = red
        keyGreen = green
        keyBlue = blue
        keySimilarity = similarity
        keySoftness = softness
        compositor?.setKey(mode, red, green, blue, similarity, softness)
    }

    /**
     * Empieza a grabar el programa en MP4. El compositor dibuja en la superficie
     * del codificador y la mezcla de audio se copia a su pista AAC.
     */
    fun startRecording(): Boolean {
        if (!running || recorder != null) return false
        val gl = compositor ?: return false
        val created = runCatching {
            ProgramRecorder(
                file = ProgramRecorder.newOutputFile(context),
                width = gl.outputWidth,
                height = gl.outputHeight,
                fps = fps
            )
        }.onFailure { Log.w(TAG, "No se pudo abrir el grabador", it) }
            .getOrNull()
        if (created == null) {
            onStatus?.invoke("No se pudo abrir el grabador del programa")
            return false
        }
        if (!gl.attachRecorder(created.inputSurface)) {
            created.stop()
            onStatus?.invoke("El compositor no pudo entregar el cuadro al grabador")
            return false
        }
        created.start()
        recorder = created
        lastRecording = null
        onStatus?.invoke("Grabando el programa · ${created.file.name}")
        return true
    }

    /** Corta la grabación y devuelve el MP4, o null si no quedó nada. */
    fun stopRecording(): File? {
        val active = recorder ?: return null
        recorder = null
        compositor?.detachRecorder()
        val file = runCatching { active.stop() }.getOrNull()
        if (file != null) {
            lastRecording = file
            onStatus?.invoke("Grabación guardada · ${file.name}")
        } else {
            onStatus?.invoke("La grabación quedó vacía: no llegó ningún cuadro")
        }
        return file
    }

    /** Ganancia del canal A (micrófono) y del canal B (NDI). */
    var micGain: Float
        get() = audio.micGain
        set(value) {
            audio.micGain = value
        }

    var ndiGain: Float
        get() = audio.ndiGain
        set(value) {
            audio.ndiGain = value
        }

    var micOpen: Boolean
        get() = audio.micOpen
        set(value) {
            audio.micOpen = value
        }

    var ndiOpen: Boolean
        get() = audio.ndiOpen
        set(value) {
            audio.ndiOpen = value
        }

    /** Arranca la salida NDI y el reloj del compositor. */
    fun start(width: Int, height: Int, framesPerSecond: Int): Boolean {
        if (running) return true
        if (!NdiNative.init()) {
            onStatus?.invoke("El runtime de NDI no está disponible")
            return false
        }
        val output = NdiSender(name)
        if (!output.isValid) {
            onStatus?.invoke("No se pudo crear la fuente NDI del mezclador")
            return false
        }
        val gl = GlCompositor()
        fps = framesPerSecond.coerceIn(1, 60)
        gl.onFrame = { rgba, w, h ->
            sender?.sendVideo(rgba, w, h, fps)
            publishPreview(rgba, w, h)
        }
        if (!gl.start(width, height, fps)) {
            output.close()
            onStatus?.invoke("El compositor GL no pudo arrancar")
            return false
        }
        sender = output
        compositor = gl
        gl.setKey(keyMode, keyRed, keyGreen, keyBlue, keySimilarity, keySoftness)
        running = true
        program = GlCompositor.INPUT_A
        startAudio()
        onStatus?.invoke("Mezcla al aire por NDI")
        return true
    }

    /**
     * Arranca la mezcla de audio y el micrófono. El micrófono se abre acá porque
     * la mezcla reemplaza a la fuente directa de la cámara, que era la dueña de
     * la captura de audio.
     */
    private fun startAudio(attempt: Int = 0) {
        if (!running || microphone != null) return
        audio.onMixed = { pcm, samples, channels, rate ->
            sender?.sendAudio(pcm, samples, channels, rate)
            // La grabación lleva exactamente el mismo audio que sale por NDI.
            recorder?.submitAudio(pcm, samples, channels, rate)
        }
        audio.onLevels = { levels ->
            mainHandler.post { if (running) onAudioLevels?.invoke(levels) }
        }
        audio.start()
        val capture = AudioCapture(
            context,
            onBuffer = { pcm, samples, channels, rate ->
                audio.submitMic(pcm, samples, channels, rate)
            }
        )
        val opened = runCatching { capture.start() }.isSuccess &&
            capture.inputLabel.isNotEmpty()
        if (!opened) {
            capture.stop()
            // La captura anterior de la cámara puede tardar en soltar el micrófono.
            if (attempt < 2 && running) {
                mainHandler.postDelayed({ startAudio(attempt + 1) }, 600L)
            } else {
                onStatus?.invoke("Mezcla al aire, pero el micrófono no abrió")
            }
            return
        }
        microphone = capture
    }

    /** Audio recibido por NDI (entrada B). */
    fun submitNdiAudio(pcm: FloatArray, samples: Int, channels: Int, sampleRate: Int) {
        audio.submitNdi(pcm, samples, channels, sampleRate)
    }

    fun stop() {
        val gl = compositor
        compositor = null
        if (recorder != null) {
            val active = recorder
            recorder = null
            gl?.detachRecorder()
            lastRecording = runCatching { active?.stop() }.getOrNull() ?: lastRecording
        }
        gl?.stop()
        audio.stop()
        audio.onMixed = null
        audio.onLevels = null
        microphone?.stop()
        microphone = null
        sender?.close()
        sender = null
        running = false
        program = GlCompositor.INPUT_A
        previewBitmap = null
        previewBuffer = null
    }

    /** Cuadro de la cámara (entrada A). */
    fun submitCamera(rgba: ByteArray, width: Int, height: Int) {
        compositor?.submit(GlCompositor.INPUT_A, rgba, width, height)
    }

    /** Cuadro recibido por NDI (entrada B). */
    fun submitNdi(rgba: ByteArray, width: Int, height: Int) {
        compositor?.submit(GlCompositor.INPUT_B, rgba, width, height)
    }

    fun cutTo(slot: Int) {
        program = if (slot == GlCompositor.INPUT_B) GlCompositor.INPUT_B else GlCompositor.INPUT_A
        compositor?.cutTo(program)
    }

    fun fadeTo(slot: Int, milliseconds: Int) {
        program = if (slot == GlCompositor.INPUT_B) GlCompositor.INPUT_B else GlCompositor.INPUT_A
        compositor?.fadeTo(program, milliseconds)
    }

    /** Convierte el programa a un mapa de bits para la vista previa, con límite de tasa. */
    private fun publishPreview(rgba: ByteArray, width: Int, height: Int) {
        val callback = onPreview ?: return
        val now = System.currentTimeMillis()
        if (now - previewAt < PREVIEW_INTERVAL_MS) return
        previewAt = now
        val bytes = width * height * 4
        if (rgba.size < bytes) return
        val bitmap = previewBitmap?.takeIf { it.width == width && it.height == height }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                previewBitmap = it
            }
        val buffer = previewBuffer?.takeIf { it.capacity() >= bytes }
            ?: ByteBuffer.allocateDirect(bytes).also { previewBuffer = it }
        try {
            buffer.clear()
            buffer.put(rgba, 0, bytes)
            buffer.rewind()
            bitmap.copyPixelsFromBuffer(buffer)
        } catch (t: Throwable) {
            Log.w(TAG, "No se pudo preparar la vista previa", t)
            return
        }
        mainHandler.post { onPreview?.invoke(bitmap) }
    }
}
