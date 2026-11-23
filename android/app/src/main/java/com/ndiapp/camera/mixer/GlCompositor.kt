package com.ndiapp.camera.mixer

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.EGLExt
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/**
 * Compositor GLES2 de dos entradas con reloj propio.
 *
 * Cada entrada llega como RGBA y se sube a su propia textura; en cada tic del
 * reloj se dibujan las dos en un cuadro 16:9 (encajadas sin deformar, con franjas
 * negras si la relación no coincide) y se leen de vuelta a RGBA para NDI. El
 * mezclado es un crossfade: B se dibuja encima de A con alfa = [mix], así un
 * CUT es un fade de duración cero.
 *
 * El reloj es del compositor, no de las fuentes: si una entrada se congela, la
 * otra sigue saliendo y NDI no se queda sin cuadros.
 *
 * Además del cruce, B puede ir como capa sobre A: en [LAYER_PIP] en un recuadro
 * con tamaño, esquina, opacidad y marco; en [LAYER_OVERLAY] a tamaño ajustable y
 * translúcida sobre el cuadro completo.
 */
class GlCompositor {

    companion object {
        const val INPUT_A = 0
        const val INPUT_B = 1

        private const val TAG = "GlCompositor"

        private const val VERTEX_SHADER = """
            attribute vec2 aPos;
            uniform vec2 uScale;
            uniform vec2 uOffset;
            varying vec2 vUV;
            void main() {
                gl_Position = vec4(
                    aPos.x * uScale.x + uOffset.x,
                    aPos.y * uScale.y + uOffset.y,
                    0.0, 1.0
                );
                vUV = vec2(aPos.x * 0.5 + 0.5, aPos.y * 0.5 + 0.5);
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTex;
            uniform float uAlpha;
            uniform vec3 uTint;
            uniform int uKeyMode;
            uniform vec3 uKeyColor;
            uniform float uKeySimilarity;
            uniform float uKeySoftness;
            varying vec2 vUV;

            // Crominancia (Cb, Cr) del color: la clave se mide sólo ahí, así un
            // verde apagado o con brillo distinto sigue cayendo dentro del key.
            vec2 chroma(vec3 c) {
                return vec2(
                    -0.168736 * c.r - 0.331264 * c.g + 0.5 * c.b,
                    0.5 * c.r - 0.418688 * c.g - 0.081312 * c.b
                );
            }

            void main() {
                vec4 color = texture2D(uTex, vUV);
                float alpha = color.a * uAlpha;
                if (uKeyMode > 0) {
                    float distance;
                    if (uKeyMode == 1) {
                        distance = length(chroma(color.rgb) - chroma(uKeyColor));
                    } else {
                        float luma = dot(color.rgb, vec3(0.299, 0.587, 0.114));
                        float keyLuma = dot(uKeyColor, vec3(0.299, 0.587, 0.114));
                        distance = abs(luma - keyLuma);
                    }
                    alpha *= smoothstep(
                        uKeySimilarity,
                        uKeySimilarity + max(uKeySoftness, 0.001),
                        distance
                    );
                }
                gl_FragColor = vec4(color.rgb * uTint, alpha);
            }
        """

        /** Keyer apagado, croma o luma. */
        const val KEY_OFF = 0
        const val KEY_CHROMA = 1
        const val KEY_LUMA = 2

        /** Capas del programa: cruce a pantalla completa, PiP u overlay. */
        const val LAYER_MIX = 0
        const val LAYER_PIP = 1
        const val LAYER_OVERLAY = 2

        /** Esquina o centro donde se ancla la capa B. */
        const val ANCHOR_TOP_LEFT = 0
        const val ANCHOR_TOP_RIGHT = 1
        const val ANCHOR_BOTTOM_LEFT = 2
        const val ANCHOR_BOTTOM_RIGHT = 3
        const val ANCHOR_CENTER = 4

        /** Separación de la capa con el borde del cuadro, en NDC. */
        private const val LAYER_MARGIN = 0.06f

        /** Grosor del marco de la capa, en NDC (≈ 3 px a 1080p). */
        private const val LAYER_BORDER = 0.0035f
    }

    /** Cuadro compuesto listo para enviar. El búfer se reutiliza entre cuadros. */
    var onFrame: ((rgba: ByteArray, width: Int, height: Int) -> Unit)? = null

    /** Capa de B sobre A: [LAYER_MIX], [LAYER_PIP] o [LAYER_OVERLAY]. */
    @Volatile
    var layerMode: Int = LAYER_MIX
        private set

    /** Tamaño de la capa B: fracción del ancho de salida (1 = cuadro completo). */
    @Volatile
    var layerScale: Float = 0.34f
        private set

    /** Ancla de la capa B: una de las constantes ANCHOR_*. */
    @Volatile
    var layerAnchor: Int = ANCHOR_BOTTOM_RIGHT
        private set

    /** Opacidad de la capa B sobre el fondo (0 = invisible, 1 = opaca). */
    @Volatile
    var layerOpacity: Float = 1f
        private set

    /** Dibuja un marco alrededor de la capa PiP. */
    @Volatile
    var layerBorder: Boolean = true
        private set

    /** Keyer aplicado a la entrada B: [KEY_OFF], [KEY_CHROMA] o [KEY_LUMA]. */
    @Volatile
    var keyMode: Int = KEY_OFF
        private set

    /** Color de referencia del keyer (0..1 por canal). */
    @Volatile
    private var keyRed = 0f

    @Volatile
    private var keyGreen = 1f

    @Volatile
    private var keyBlue = 0f

    @Volatile
    private var keySimilarity = 0.18f

    @Volatile
    private var keySoftness = 0.12f

    /** Tamaño del programa, para quien lo graba. */
    val outputWidth: Int
        get() = outWidth

    val outputHeight: Int
        get() = outHeight

    /** Mezcla actual (0 = A, 1 = B). La lee la interfaz para el fader. */
    @Volatile
    var mix: Float = 0f
        private set

    /** Cuadros recibidos de cada entrada: sirve para saber si hay señal. */
    @Volatile
    var framesA: Long = 0L
        private set

    @Volatile
    var framesB: Long = 0L
        private set

    @Volatile
    private var running = false

    private var outWidth = 1920
    private var outHeight = 1080

    @Volatile
    private var targetFps = 30

    /** Opacidad suavizada de la capa: aparece y desaparece sin saltos. */
    private var layerAlpha = 0f

    private val fadeLock = Any()
    private var mixTarget = 0f
    private var fadeFrom = 0f
    private var fadeStartNs = 0L
    private var fadeDurationNs = 0L

    private val slotLock = Any()

    /** Último cuadro recibido de cada entrada, ya copiado a un búfer propio. */
    private class Slot {
        var rgba = ByteArray(0)
        var width = 0
        var height = 0
        var seq = 0L
    }

    private val slots = arrayOf(Slot(), Slot())

    private var thread: Thread? = null
    private val ready = CountDownLatch(1)

    private var display: EGLDisplay? = null
    private var context: EGLContext? = null
    private var surface: EGLSurface? = null
    private var eglConfig: EGLConfig? = null

    /** Salida adicional hacia el codificador de grabación, si está grabando. */
    private var recorderSurface: EGLSurface? = null
    private var recorderStartNs = 0L

    /** Tareas que hay que ejecutar en el hilo GL (crear o cerrar la grabación). */
    private val taskLock = Any()
    private val tasks = ArrayList<() -> Unit>()
    private var program = 0
    private var aPos = 0
    private var uScale = 0
    private var uOffset = 0
    private var uAlpha = 0
    private var uTint = 0
    private var uTex = 0
    private var uKeyMode = 0
    private var uKeyColor = 0
    private var uKeySimilarity = 0
    private var uKeySoftness = 0
    private var whiteTexture = 0
    private val textures = IntArray(2) { 0 }
    private val textureWidth = IntArray(2)
    private val textureHeight = IntArray(2)
    private val uploadedSeq = LongArray(2) { -1L }
    private val uploadBuffers = arrayOfNulls<ByteBuffer>(2)
    private var framebuffer = 0
    private var framebufferTexture = 0
    private var quad: FloatBuffer = FloatBuffer.allocate(0)
    private var readBuffer: ByteBuffer = ByteBuffer.allocateDirect(0)
    private var outBytes = ByteArray(0)

    /**
     * Arranca el reloj del compositor y devuelve si el contexto GL quedó listo.
     * Los cuadros se entregan por [onFrame] desde el hilo del compositor.
     */
    fun start(width: Int, height: Int, fps: Int): Boolean {
        if (running) return true
        outWidth = width.coerceAtLeast(2) and -2
        outHeight = height.coerceAtLeast(2) and -2
        targetFps = fps.coerceIn(1, 60)
        val worker = Thread { renderLoop() }
        worker.name = "mixer-gl"
        worker.isDaemon = true
        thread = worker
        running = true
        worker.start()
        val ok = runCatching { ready.await(3, TimeUnit.SECONDS) }.getOrDefault(false)
        if (!ok || !running) {
            stop()
            return false
        }
        return true
    }

    /** Copia un cuadro de la entrada [slot] (INPUT_A o INPUT_B) para el próximo tic. */
    fun submit(slot: Int, rgba: ByteArray, width: Int, height: Int) {
        if (!running || width <= 0 || height <= 0) return
        val needed = width * height * 4
        if (rgba.size < needed) return
        val target = slots[slot.coerceIn(0, 1)]
        synchronized(slotLock) {
            if (target.rgba.size < needed) target.rgba = ByteArray(needed)
            System.arraycopy(rgba, 0, target.rgba, 0, needed)
            target.width = width
            target.height = height
            target.seq++
        }
        if (slot == INPUT_A) framesA++ else framesB++
    }

    /** CUT: pasa a [slot] sin transición. */
    fun cutTo(slot: Int) {
        val value = if (slot == INPUT_A) 0f else 1f
        synchronized(fadeLock) {
            mixTarget = value
            fadeFrom = value
            fadeDurationNs = 0L
            mix = value
        }
    }

    /** Fade a [slot] en [milliseconds]; 0 equivale a un CUT. */
    fun fadeTo(slot: Int, milliseconds: Int) {
        val value = if (slot == INPUT_A) 0f else 1f
        if (milliseconds <= 0) {
            cutTo(slot)
            return
        }
        synchronized(fadeLock) {
            fadeFrom = mix
            mixTarget = value
            fadeStartNs = System.nanoTime()
            fadeDurationNs = milliseconds * 1_000_000L
        }
    }

    /** Elige cómo se compone B sobre A. */
    fun setLayerMode(mode: Int) {
        layerMode = mode.coerceIn(LAYER_MIX, LAYER_OVERLAY)
    }

    /** Tamaño de la capa B como fracción del ancho de salida. */
    fun setLayerScale(scale: Float) {
        layerScale = scale.coerceIn(0.1f, 1.4f)
    }

    /** Esquina (o centro) donde se ancla la capa B. */
    fun setLayerAnchor(anchor: Int) {
        layerAnchor = anchor.coerceIn(ANCHOR_TOP_LEFT, ANCHOR_CENTER)
    }

    /** Opacidad de la capa B sobre el fondo. */
    fun setLayerOpacity(opacity: Float) {
        layerOpacity = opacity.coerceIn(0f, 1f)
    }

    fun setLayerBorder(visible: Boolean) {
        layerBorder = visible
    }

    /**
     * Keyer sobre la entrada B: [KEY_CHROMA] descarta el color de referencia
     * (pantalla verde o azul) y [KEY_LUMA] descarta por brillo. Sólo afecta a B,
     * tanto en el cruce como en las capas; A siempre sale entera.
     */
    fun setKey(mode: Int, red: Float, green: Float, blue: Float, similarity: Float, softness: Float) {
        keyMode = mode.coerceIn(KEY_OFF, KEY_LUMA)
        keyRed = red.coerceIn(0f, 1f)
        keyGreen = green.coerceIn(0f, 1f)
        keyBlue = blue.coerceIn(0f, 1f)
        keySimilarity = similarity.coerceIn(0.01f, 1f)
        keySoftness = softness.coerceIn(0.001f, 1f)
    }

    /**
     * Conecta la superficie del codificador de grabación. A partir de ahí cada
     * cuadro del programa se dibuja también ahí, sin pasar por el procesador.
     */
    fun attachRecorder(target: Surface): Boolean {
        if (!running) return false
        var attached = false
        val done = runOnGl {
            val eglDisplay = display ?: return@runOnGl
            val config = eglConfig ?: return@runOnGl
            detachRecorderLocked()
            val created = EGL14.eglCreateWindowSurface(
                eglDisplay, config, target, intArrayOf(EGL14.EGL_NONE), 0
            )
            if (created == null || created == EGL14.EGL_NO_SURFACE) {
                Log.w(TAG, "No se pudo crear la superficie de grabación")
                return@runOnGl
            }
            recorderSurface = created
            recorderStartNs = System.nanoTime()
            attached = true
        }
        if (!done || !attached) {
            runOnGl { detachRecorderLocked() }
            return false
        }
        return true
    }

    /** Desconecta la grabación y devuelve la superficie de lectura. */
    fun detachRecorder() {
        if (!running) {
            recorderSurface = null
            return
        }
        runOnGl { detachRecorderLocked() }
    }

    private fun detachRecorderLocked() {
        val eglDisplay = display ?: return
        val target = recorderSurface ?: return
        recorderSurface = null
        runCatching {
            EGL14.eglMakeCurrent(eglDisplay, surface, surface, context)
            EGL14.eglDestroySurface(eglDisplay, target)
        }
    }

    /** Pide al hilo GL que ejecute [block] y espera un momento a que termine. */
    private fun runOnGl(block: () -> Unit): Boolean {
        if (!running) return false
        val latch = CountDownLatch(1)
        synchronized(taskLock) {
            tasks.add {
                runCatching(block)
                latch.countDown()
            }
        }
        return runCatching { latch.await(1500, TimeUnit.MILLISECONDS) }.getOrDefault(false)
    }

    private fun drainTasks() {
        val pending: List<() -> Unit> = synchronized(taskLock) {
            if (tasks.isEmpty()) return
            val copy = ArrayList(tasks)
            tasks.clear()
            copy
        }
        for (task in pending) {
            runCatching { task() }
        }
    }

    private fun setKeyUniforms(allow: Boolean) {
        val active = allow && keyMode != KEY_OFF
        GLES20.glUniform1i(uKeyMode, if (active) keyMode else KEY_OFF)
        GLES20.glUniform3f(uKeyColor, keyRed, keyGreen, keyBlue)
        GLES20.glUniform1f(uKeySimilarity, keySimilarity)
        GLES20.glUniform1f(uKeySoftness, keySoftness)
    }

    fun stop() {
        if (!running && thread == null) return
        running = false
        thread?.let { worker ->
            worker.interrupt()
            runCatching { worker.join(1500) }
        }
        thread = null
        mix = 0f
        layerAlpha = 0f
        synchronized(fadeLock) {
            mixTarget = 0f
            fadeFrom = 0f
            fadeDurationNs = 0L
        }
    }

    // ------------------------------------------------------------- reloj GL --

    private fun renderLoop() {
        val ok = runCatching {
            createContext() && createProgram() && createTarget()
        }.getOrDefault(false)
        ready.countDown()
        if (!ok) {
            Log.w(TAG, "No se pudo preparar el compositor GL")
            release()
            return
        }
        var next = System.nanoTime()
        val tick = 1_000_000_000L / targetFps.coerceIn(1, 60)
        while (running) {
            drainTasks()
            val now = System.nanoTime()
            if (now < next) {
                LockSupport.parkNanos(next - now)
                continue
            }
            runCatching { drawFrame(now) }.onFailure { error ->
                Log.w(TAG, "Cuadro del compositor fallido", error)
            }
            next += tick
            // Si el teléfono no da abasto, se salta el atraso en vez de acumularlo.
            if (next < System.nanoTime()) next = System.nanoTime()
        }
        release()
    }

    private fun drawFrame(now: Long) {
        updateMix(now)
        upload(INPUT_A)
        upload(INPUT_B)
        updateLayerAlpha()

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glViewport(0, 0, outWidth, outHeight)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)

        // A ocupa el cuadro; en MIX se cruza con B a pantalla completa y en las
        // capas B se dibuja encima con su tamaño, posición y opacidad.
        setKeyUniforms(false)
        drawSlot(INPUT_A, 1f)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        val mode = layerMode
        if (mode == LAYER_MIX) {
            setKeyUniforms(true)
            drawSlot(INPUT_B, mix)
            setKeyUniforms(false)
        } else if (layerAlpha > 0.004f) {
            drawLayer(
                INPUT_B,
                layerScale,
                if (mode == LAYER_OVERLAY) ANCHOR_CENTER else layerAnchor,
                layerAlpha,
                mode == LAYER_PIP && layerBorder
            )
        }
        GLES20.glDisable(GLES20.GL_BLEND)

        GLES20.glFinish()
        readBuffer.clear()
        GLES20.glReadPixels(
            0, 0, outWidth, outHeight,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, readBuffer
        )
        readBuffer.position(0)
        readBuffer.get(outBytes, 0, outBytes.size)
        onFrame?.invoke(outBytes, outWidth, outHeight)

        // Segundo destino: el codificador de grabación recibe el mismo cuadro.
        val recorder = recorderSurface
        if (recorder != null) drawProgramToRecorder(recorder, now)
    }

    /** Dibuja el programa ya compuesto en la superficie de la grabación. */
    private fun drawProgramToRecorder(recorder: EGLSurface, now: Long) {
        val eglDisplay = display ?: return
        val eglContext = context ?: return
        if (!EGL14.eglMakeCurrent(eglDisplay, recorder, recorder, eglContext)) return
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, outWidth, outHeight)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        setKeyUniforms(false)
        setupQuad(1f, 1f, 0f, 0f, 1f)
        setTint(1f, 1f, 1f)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, framebufferTexture)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        // El reloj de la grabación arranca con la primera superficie: el vídeo
        // empieza en 0 y coincide con el audio, que también empieza en 0.
        EGLExt.eglPresentationTimeANDROID(eglDisplay, recorder, now - recorderStartNs)
        EGL14.eglSwapBuffers(eglDisplay, recorder)
        runCatching { EGL14.eglMakeCurrent(eglDisplay, surface, surface, eglContext) }
    }

    /** Acerca la opacidad de la capa a su objetivo: 0 en MIX, su valor en capas. */
    private fun updateLayerAlpha() {
        val target = if (layerMode == LAYER_MIX) 0f else layerOpacity
        val delta = target - layerAlpha
        layerAlpha += if (delta > -0.01f && delta < 0.01f) delta else delta * 0.22f
        if (layerAlpha < 0.004f) layerAlpha = 0f
    }

    private fun updateMix(now: Long) {
        val value = synchronized(fadeLock) {
            if (fadeDurationNs <= 0L) {
                mixTarget
            } else {
                val progress = ((now - fadeStartNs).toFloat() / fadeDurationNs)
                    .coerceIn(0f, 1f)
                val current = fadeFrom + (mixTarget - fadeFrom) * progress
                if (progress >= 1f) fadeDurationNs = 0L
                current
            }
        }
        mix = value
    }

    private fun upload(slot: Int) {
        // El cuadro se copia al búfer de subida con el candado tomado: la fuente
        // sigue escribiendo su propio búfer y no puede dejar una imagen a medias.
        var width = 0
        var height = 0
        var seq = 0L
        var buffer: ByteBuffer? = null
        synchronized(slotLock) {
            val source = slots[slot]
            width = source.width
            height = source.height
            seq = source.seq
            if (width > 0 && height > 0 && seq > 0L && uploadedSeq[slot] != seq) {
                val needed = width * height * 4
                val target = uploadBuffers[slot]
                    ?.takeIf { it.capacity() >= needed }
                    ?: ByteBuffer.allocateDirect(needed)
                        .order(ByteOrder.nativeOrder())
                        .also { uploadBuffers[slot] = it }
                target.clear()
                target.put(source.rgba, 0, needed)
                target.position(0)
                buffer = target
            }
        }
        val staged = buffer ?: return
        var texture = textures[slot]
        if (texture == 0) {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            texture = ids[0]
            textures[slot] = texture
            textureWidth[slot] = 0
            textureHeight[slot] = 0
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        if (textureWidth[slot] != width || textureHeight[slot] != height) {
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
            )
            textureWidth[slot] = width
            textureHeight[slot] = height
        }
        GLES20.glTexSubImage2D(
            GLES20.GL_TEXTURE_2D, 0, 0, 0, width, height,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, staged
        )
        uploadedSeq[slot] = seq
    }

    /** Dibuja la entrada a cuadro completo, encajada sin deformarse. */
    private fun drawSlot(slot: Int, alpha: Float) {
        val texture = textures[slot]
        val texWidth = textureWidth[slot]
        val texHeight = textureHeight[slot]
        if (texture == 0 || texWidth <= 0 || texHeight <= 0) return
        val inputAspect = texWidth.toFloat() / texHeight.toFloat()
        val outputAspect = outWidth.toFloat() / outHeight.toFloat()
        var scaleX = 1f
        var scaleY = 1f
        if (inputAspect > outputAspect) {
            scaleY = outputAspect / inputAspect
        } else {
            scaleX = inputAspect / outputAspect
        }
        setupQuad(scaleX, scaleY, 0f, 0f, alpha)
        setTint(1f, 1f, 1f)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    /**
     * Dibuja la entrada [slot] como capa sobre el fondo.
     *
     * La caja ocupa [scale] del ancho de salida con la relación de aspecto de la
     * salida; la imagen se encaja dentro sin deformarse. [anchor] elige la esquina
     * (o el centro) y el margen fijo la separa del borde. Si [border] está activo se
     * pinta antes un marco claro del tamaño de la imagen.
     */
    private fun drawLayer(
        slot: Int,
        scale: Float,
        anchor: Int,
        alpha: Float,
        border: Boolean
    ) {
        val texture = textures[slot]
        val texWidth = textureWidth[slot]
        val texHeight = textureHeight[slot]
        if (texture == 0 || texWidth <= 0 || texHeight <= 0) return

        val boxWidth = (scale * outWidth).coerceAtMost(outWidth * 1.4f)
        val boxHeight = boxWidth * outHeight / outWidth
        val inputAspect = texWidth.toFloat() / texHeight.toFloat()
        val boxAspect = boxWidth / boxHeight
        var drawWidth = boxWidth
        var drawHeight = boxWidth / inputAspect
        if (inputAspect < boxAspect) {
            drawHeight = boxHeight
            drawWidth = boxHeight * inputAspect
        }
        // La escala es media anchura en NDC: 1 coincide con el cuadro completo.
        val halfX = drawWidth / outWidth
        val halfY = drawHeight / outHeight
        val centerX = when (anchor) {
            ANCHOR_TOP_LEFT, ANCHOR_BOTTOM_LEFT -> -1f + LAYER_MARGIN + halfX
            ANCHOR_TOP_RIGHT, ANCHOR_BOTTOM_RIGHT -> 1f - LAYER_MARGIN - halfX
            else -> 0f
        }
        val centerY = when (anchor) {
            ANCHOR_TOP_LEFT, ANCHOR_TOP_RIGHT -> 1f - LAYER_MARGIN - halfY
            ANCHOR_BOTTOM_LEFT, ANCHOR_BOTTOM_RIGHT -> -1f + LAYER_MARGIN + halfY
            else -> 0f
        }

        if (border) {
            setKeyUniforms(false)
            drawSolid(centerX, centerY, halfX + LAYER_BORDER, halfY + LAYER_BORDER)
        }
        setKeyUniforms(true)
        setupQuad(halfX, halfY, centerX, centerY, alpha)
        setTint(1f, 1f, 1f)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    /** Rectángulo de color plano, usado para el marco de la capa. */
    private fun drawSolid(centerX: Float, centerY: Float, halfX: Float, halfY: Float) {
        if (whiteTexture == 0) return
        setupQuad(halfX, halfY, centerX, centerY, 1f)
        setTint(0.55f, 0.78f, 1f)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, whiteTexture)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun setupQuad(halfX: Float, halfY: Float, centerX: Float, centerY: Float, alpha: Float) {
        GLES20.glUniform2f(uScale, halfX, halfY)
        GLES20.glUniform2f(uOffset, centerX, centerY)
        GLES20.glUniform1f(uAlpha, alpha.coerceIn(0f, 1f))
    }

    private fun setTint(red: Float, green: Float, blue: Float) {
        GLES20.glUniform3f(uTint, red, green, blue)
    }

    // ------------------------------------------------------------------ EGL --

    private fun createContext(): Boolean {
        val eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return false
        display = eglDisplay

        val configAttrs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT or EGL14.EGL_WINDOW_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, configAttrs, 0, configs, 0, 1, count, 0) ||
            count[0] == 0
        ) {
            return false
        }
        val config = configs[0] ?: return false
        eglConfig = config
        val contextAttrs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        val eglContext = EGL14.eglCreateContext(
            eglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttrs, 0
        )
        if (eglContext == null || eglContext == EGL14.EGL_NO_CONTEXT) return false
        context = eglContext

        // Superficie mínima: todo el dibujo va a un framebuffer propio del tamaño
        // de salida, así el tamaño se puede cambiar sin recrear el contexto.
        val surfaceAttrs = intArrayOf(
            EGL14.EGL_WIDTH, 1,
            EGL14.EGL_HEIGHT, 1,
            EGL14.EGL_NONE
        )
        val eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, config, surfaceAttrs, 0)
        if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE) return false
        surface = eglSurface
        return EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    private fun createProgram(): Boolean {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER) ?: return false
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER) ?: return false
        program = GLES20.glCreateProgram()
        if (program == 0) return false
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        if (status[0] != GLES20.GL_TRUE) {
            Log.w(TAG, "Enlace del programa GL: " + GLES20.glGetProgramInfoLog(program))
            return false
        }
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        uScale = GLES20.glGetUniformLocation(program, "uScale")
        uOffset = GLES20.glGetUniformLocation(program, "uOffset")
        uAlpha = GLES20.glGetUniformLocation(program, "uAlpha")
        uTint = GLES20.glGetUniformLocation(program, "uTint")
        uTex = GLES20.glGetUniformLocation(program, "uTex")
        uKeyMode = GLES20.glGetUniformLocation(program, "uKeyMode")
        uKeyColor = GLES20.glGetUniformLocation(program, "uKeyColor")
        uKeySimilarity = GLES20.glGetUniformLocation(program, "uKeySimilarity")
        uKeySoftness = GLES20.glGetUniformLocation(program, "uKeySoftness")

        // Textura blanca de 1×1 para el marco de la capa: el color lo pone uTint.
        val white = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        white.put(byteArrayOf(255.toByte(), 255.toByte(), 255.toByte(), 255.toByte()))
        white.position(0)
        val whiteIds = IntArray(1)
        GLES20.glGenTextures(1, whiteIds, 0)
        whiteTexture = whiteIds[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, whiteTexture)
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST
        )
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, white
        )

        quad = ByteBuffer
            .allocateDirect(4 * 2 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
                position(0)
            }
        return true
    }

    private fun compileShader(type: Int, source: String): Int? {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) return null
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            Log.w(TAG, "Shader GL: " + GLES20.glGetShaderInfoLog(shader))
            GLES20.glDeleteShader(shader)
            return null
        }
        return shader
    }

    private fun createTarget(): Boolean {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        framebufferTexture = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, framebufferTexture)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, outWidth, outHeight, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glGenFramebuffers(1, ids, 0)
        framebuffer = ids[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, framebufferTexture, 0
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.w(TAG, "Framebuffer incompleto: $status")
            return false
        }
        readBuffer = ByteBuffer
            .allocateDirect(outWidth * outHeight * 4)
            .order(ByteOrder.nativeOrder())
        outBytes = ByteArray(outWidth * outHeight * 4)
        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
        return true
    }

    private fun release() {
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        for (index in textures.indices) {
            if (textures[index] != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(textures[index]), 0)
                textures[index] = 0
            }
            uploadedSeq[index] = -1L
        }
        if (framebuffer != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
            framebuffer = 0
        }
        if (framebufferTexture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(framebufferTexture), 0)
            framebufferTexture = 0
        }
        if (whiteTexture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(whiteTexture), 0)
            whiteTexture = 0
        }
        val eglDisplay = display
        val eglSurface = surface
        val eglContext = context
        val recorder = recorderSurface
        recorderSurface = null
        if (eglDisplay != null && recorder != null) {
            runCatching { EGL14.eglDestroySurface(eglDisplay, recorder) }
        }
        if (eglDisplay != null) {
            EGL14.eglMakeCurrent(
                eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )
            if (eglSurface != null) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != null) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
        }
        surface = null
        context = null
        display = null
        eglConfig = null
    }
}
