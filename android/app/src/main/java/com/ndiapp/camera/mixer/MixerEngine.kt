package com.ndiapp.camera.mixer

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ndiapp.camera.ndi.NdiNative
import com.ndiapp.camera.ndi.NdiSender
import java.nio.ByteBuffer

/**
 * Mezclador de dos entradas con salida NDI.
 *
 * El vídeo de cada entrada llega como RGBA y se entrega al compositor GL, que
 * compone el programa con su propio reloj. Nada de lo que entra se manda directo
 * a NDI: la única salida es el cuadro compuesto.
 */
class MixerEngine {

    companion object {
        private const val TAG = "MixerEngine"
        private const val PREVIEW_INTERVAL_MS = 120L
    }

    /** Cuadro del programa para la vista previa de la interfaz (hilo principal). */
    var onPreview: ((Bitmap) -> Unit)? = null

    var onStatus: ((String) -> Unit)? = null

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
    private var fps = 30
    private var name = "NDI App (Mezcla)"

    private var previewBitmap: Bitmap? = null
    private var previewBuffer: ByteBuffer? = null
    private var previewAt = 0L

    val mix: Float
        get() = compositor?.mix ?: 0f

    val cameraFrames: Long
        get() = compositor?.framesA ?: 0L

    val ndiFrames: Long
        get() = compositor?.framesB ?: 0L

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
        running = true
        program = GlCompositor.INPUT_A
        onStatus?.invoke("Mezcla al aire por NDI")
        return true
    }

    fun stop() {
        val gl = compositor
        compositor = null
        gl?.stop()
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
