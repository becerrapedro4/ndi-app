package com.ndiapp.camera.mixer

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.util.Log
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
 */
class GlCompositor {

    companion object {
        const val INPUT_A = 0
        const val INPUT_B = 1

        private const val TAG = "GlCompositor"

        private const val VERTEX_SHADER = """
            attribute vec2 aPos;
            uniform vec2 uScale;
            varying vec2 vUV;
            void main() {
                gl_Position = vec4(aPos.x * uScale.x, aPos.y * uScale.y, 0.0, 1.0);
                vUV = vec2(aPos.x * 0.5 + 0.5, aPos.y * 0.5 + 0.5);
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTex;
            uniform float uAlpha;
            varying vec2 vUV;
            void main() {
                vec4 color = texture2D(uTex, vUV);
                gl_FragColor = vec4(color.rgb, color.a * uAlpha);
            }
        """
    }

    /** Cuadro compuesto listo para enviar. El búfer se reutiliza entre cuadros. */
    var onFrame: ((rgba: ByteArray, width: Int, height: Int) -> Unit)? = null

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
    private var program = 0
    private var aPos = 0
    private var uScale = 0
    private var uAlpha = 0
    private var uTex = 0
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

    fun stop() {
        if (!running && thread == null) return
        running = false
        thread?.let { worker ->
            worker.interrupt()
            runCatching { worker.join(1500) }
        }
        thread = null
        mix = 0f
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

        // A opaco y B encima con alfa = mezcla: el crossfade es una sola pasada.
        drawSlot(INPUT_A, 1f)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        drawSlot(INPUT_B, mix)
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

    private fun drawSlot(slot: Int, alpha: Float) {
        if (textures[slot] == 0 || textureWidth[slot] <= 0) return
        val inputAspect = textureWidth[slot].toFloat() / textureHeight[slot].toFloat()
        val outputAspect = outWidth.toFloat() / outHeight.toFloat()
        var scaleX = 1f
        var scaleY = 1f
        if (inputAspect > outputAspect) {
            scaleY = outputAspect / inputAspect
        } else {
            scaleX = inputAspect / outputAspect
        }
        GLES20.glUniform2f(uScale, scaleX, scaleY)
        GLES20.glUniform1f(uAlpha, alpha.coerceIn(0f, 1f))
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[slot])
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
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
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
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
        uAlpha = GLES20.glGetUniformLocation(program, "uAlpha")
        uTex = GLES20.glGetUniformLocation(program, "uTex")

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
        val eglDisplay = display
        val eglSurface = surface
        val eglContext = context
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
    }
}
