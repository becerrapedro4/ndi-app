package com.ndiapp.camera.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.ndiapp.camera.ndi.NdiNative
import com.ndiapp.camera.ndi.NdiSender
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Tipo de elemento de la lista de reproducción. */
enum class MediaKind { VIDEO, IMAGE, AUDIO }

data class MediaItem(val uri: Uri, val name: String, val kind: MediaKind)

/**
 * Reproduce una lista de archivos (vídeo, fotos y audio) y la publica como una
 * única fuente NDI. Mantiene la fuente viva entre elementos para que el receptor
 * no la pierda al cambiar de archivo.
 *
 * El vídeo lo lleva MediaPlayer y los frames se leen por OpenGL; si el trazado
 * OpenGL no entrega nada (algunos drivers), hay un respaldo que extrae
 * fotogramas con MediaMetadataRetriever. El audio se decodifica aparte, se manda
 * a NDI y suena por el altavoz, con el mismo reloj que el vídeo.
 */
class MediaPlaylistPlayer(private val context: Context) {

    var onStatus: ((String) -> Unit)? = null
    var onItem: ((index: Int, item: MediaItem) -> Unit)? = null
    var onProgress: ((positionMs: Int, durationMs: Int, playing: Boolean) -> Unit)? = null
    var onEnded: (() -> Unit)? = null
    var onPreview: ((Bitmap) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())

    private var sender: NdiSender? = null
    private var renderer: FrameRenderer? = null
    private var surfaceTexture: SurfaceTexture? = null

    private var items: List<MediaItem> = emptyList()
    private var index = 0
    private var kind = MediaKind.VIDEO
    private var looping = true
    private var imageSeconds = 8
    private var running = false
    private var playing = false
    private var durationMs = 0
    private var itemStartedAt = 0L
    private var pausedPosition = 0

    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var audio: MediaAudioStream? = null

    @Volatile
    private var framesSeen = 0

    @Volatile
    private var itemToken = 0

    private var still = ByteArray(0)
    private var stillWidth = 0
    private var stillHeight = 0

    private var previewBitmap: Bitmap? = null
    private var previewAt = 0L

    private var extraRotation = 0

    val isPlaying: Boolean
        get() = playing

    val isRunning: Boolean
        get() = sender != null

    val itemCount: Int
        get() = items.size

    private val pump = object : Runnable {
        override fun run() {
            if (!running || !playing) return
            val bytes = still
            if (bytes.isNotEmpty() && stillWidth > 0) {
                sender?.sendVideo(bytes, stillWidth, stillHeight, 12)
                pushPreview(bytes, stillWidth, stillHeight)
            }
            handler.postDelayed(this, 80)
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            if (playing && kind != MediaKind.VIDEO && durationMs > 0 &&
                positionMs() >= durationMs
            ) {
                advance()
            }
            onProgress?.invoke(positionMs(), durationMs, playing)
            handler.postDelayed(this, 250)
        }
    }

    /** Arranca la lista y crea la fuente NDI. */
    fun start(list: List<MediaItem>, ndiName: String, from: Int = 0): Boolean {
        close()
        if (list.isEmpty()) return false
        if (!NdiNative.init()) {
            onStatus?.invoke("El runtime de NDI no está disponible")
            return false
        }
        val created = NdiSender(ndiName)
        if (!created.isValid) {
            onStatus?.invoke("No se pudo crear la fuente NDI")
            return false
        }
        sender = created
        val render = FrameRenderer { bytes, width, height ->
            framesSeen++
            sender?.sendVideo(bytes, width, height, 30)
            pushPreview(bytes, width, height)
        }
        renderer = render
        val texture = render.start()
        if (texture == null) {
            onStatus?.invoke("No se pudo preparar la salida de vídeo")
        }
        surfaceTexture = texture
        items = list
        index = from.coerceIn(0, list.lastIndex)
        running = true
        handler.post(ticker)
        playItem()
        return true
    }

    fun play() {
        if (!running || playing) return
        playing = true
        itemStartedAt = SystemClock.elapsedRealtime()
        if (kind == MediaKind.VIDEO) {
            runCatching { player?.start() }
            audio?.play()
        } else {
            handler.post(pump)
        }
    }

    fun pause() {
        if (!running || !playing) return
        pausedPosition = positionMs()
        playing = false
        runCatching { player?.pause() }
        audio?.pause()
        handler.removeCallbacks(pump)
    }

    fun toggle() {
        if (playing) pause() else play()
    }

    fun seekTo(positionMs: Int) {
        if (kind == MediaKind.VIDEO) {
            runCatching { player?.seekTo(positionMs.coerceAtLeast(0)) }
            audio?.seekTo(positionMs)
        } else {
            pausedPosition = positionMs.coerceAtLeast(0)
            itemStartedAt = SystemClock.elapsedRealtime()
        }
    }

    fun next() {
        if (items.isEmpty()) return
        index = (index + 1) % items.size
        playItem()
    }

    /** Salta a un elemento concreto de la lista. */
    fun select(position: Int) {
        if (!running || items.isEmpty()) return
        index = position.coerceIn(0, items.lastIndex)
        playItem()
    }

    /** Giro manual extra en grados (0, 90, 180, 270). */
    fun setExtraRotation(degrees: Int) {
        extraRotation = ((degrees % 360) + 360) % 360
        renderer?.setRotation(extraRotation)
    }

    val rotation: Int
        get() = extraRotation

    fun previous() {
        if (items.isEmpty()) return
        index = (index - 1 + items.size) % items.size
        playItem()
    }

    fun setLooping(value: Boolean) {
        looping = value
    }

    fun setImageSeconds(seconds: Int) {
        imageSeconds = seconds.coerceIn(2, 60)
    }

    fun close() {
        running = false
        releaseItem()
        handler.removeCallbacks(ticker)
        surfaceTexture?.release()
        surfaceTexture = null
        renderer?.release()
        renderer = null
        sender?.close()
        sender = null
        items = emptyList()
        index = 0
        previewBitmap?.recycle()
        previewBitmap = null
    }

    private fun playItem() {
        releaseItem()
        val item = items.getOrNull(index) ?: return
        kind = item.kind
        onItem?.invoke(index, item)
        onStatus?.invoke("Reproduciendo «${item.name}»")
        playing = true
        itemStartedAt = SystemClock.elapsedRealtime()
        pausedPosition = 0
        when (item.kind) {
            MediaKind.VIDEO -> startVideo(item)
            MediaKind.IMAGE -> startImage(item)
            MediaKind.AUDIO -> startAudio(item)
        }
    }

    private fun startVideo(item: MediaItem) {
        val texture = surfaceTexture
        if (texture == null) {
            onStatus?.invoke("Sin salida de vídeo disponible")
            return
        }
        framesSeen = 0
        val token = itemToken
        val mp = MediaPlayer()
        player = mp
        val surf = Surface(texture)
        surface = surf
        val audioStream = MediaAudioStream(context) { pcm, samples, channels, rate ->
            sender?.sendAudio(pcm, samples, channels, rate)
        }
        audio = audioStream
        val hasAudio = runCatching { audioStream.open(item.uri) }.getOrDefault(false)
        // MediaPlayer ya aplica la rotación del archivo: aquí solo va el giro manual.
        runCatching { renderer?.setRotation(extraRotation) }
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            mp.setSurface(surf)
            mp.setVolume(0f, 0f)
            mp.setDataSource(context, item.uri)
            mp.setOnVideoSizeChangedListener { _, width, height ->
                renderer?.setVideoSize(width, height)
            }
            mp.setOnPreparedListener { prepared ->
                durationMs = prepared.duration.coerceAtLeast(0)
                if (hasAudio) {
                    audioStream.setLooping(false)
                    audioStream.play()
                }
                if (playing) runCatching { prepared.start() }
            }
            mp.setOnCompletionListener { advance() }
            mp.setOnErrorListener { _, what, extra ->
                onStatus?.invoke("No se pudo reproducir el vídeo ($what/$extra)")
                advance()
                true
            }
            mp.prepareAsync()
        } catch (t: Throwable) {
            onStatus?.invoke("No se pudo abrir el vídeo: ${t.message}")
            advance()
            return
        }
        // Si OpenGL no entrega frames, se extrae el vídeo de otra forma.
        handler.postDelayed({
            if (running && token == itemToken && framesSeen == 0) startFallback(item)
        }, 2200)
    }

    private fun startImage(item: MediaItem) {
        val bitmap = decodeImage(item.uri)
        if (bitmap == null) {
            onStatus?.invoke("No se pudo abrir la imagen")
            advance()
            return
        }
        stillWidth = bitmap.width
        stillHeight = bitmap.height
        still = ByteArray(stillWidth * stillHeight * 4)
        bitmap.copyPixelsToBuffer(ByteBuffer.wrap(still))
        bitmap.recycle()
        durationMs = imageSeconds * 1000
        handler.removeCallbacks(pump)
        handler.post(pump)
    }

    private fun startAudio(item: MediaItem) {
        durationMs = readDuration(item.uri)
        stillWidth = 1280
        stillHeight = 720
        still = ByteArray(stillWidth * stillHeight * 4)
        var offset = 3
        while (offset < still.size) {
            still[offset] = 0xFF.toByte()
            offset += 4
        }
        val stream = MediaAudioStream(context) { pcm, samples, channels, rate ->
            sender?.sendAudio(pcm, samples, channels, rate)
        }
        audio = stream
        if (!runCatching { stream.open(item.uri) }.getOrDefault(false)) {
            onStatus?.invoke("Este archivo no tiene pista de audio")
            advance()
            return
        }
        stream.setLooping(false)
        stream.play()
        handler.removeCallbacks(pump)
        handler.post(pump)
    }

    private fun startFallback(item: MediaItem) {
        onStatus?.invoke("Reproduciendo en modo compatible (sin OpenGL)")
        val token = itemToken
        val thread = Thread {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, item.uri)
                while (running && token == itemToken) {
                    val position = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)
                    val frame = runCatching {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                            retriever.getScaledFrameAtTime(
                                position * 1000L,
                                MediaMetadataRetriever.OPTION_CLOSEST,
                                1280,
                                720
                            )
                        } else {
                            retriever.getFrameAtTime(
                                position * 1000L,
                                MediaMetadataRetriever.OPTION_CLOSEST
                            )
                        }
                    }.getOrNull()
                    if (frame != null) {
                        val bytes = ByteArray(frame.width * frame.height * 4)
                        frame.copyPixelsToBuffer(ByteBuffer.wrap(bytes))
                        sender?.sendVideo(bytes, frame.width, frame.height, 15)
                        pushPreview(bytes, frame.width, frame.height)
                        frame.recycle()
                    }
                    Thread.sleep(70)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Respaldo de vídeo fallido", t)
            } finally {
                runCatching { retriever.release() }
            }
        }
        thread.name = "media-fallback"
        thread.start()
    }

    /** Muestra en la interfaz lo mismo que se está enviando por NDI. */
    private fun pushPreview(bytes: ByteArray, width: Int, height: Int) {
        if (width <= 0 || height <= 0 || bytes.size < width * height * 4) return
        val now = SystemClock.elapsedRealtime()
        if (now - previewAt < 100) return
        previewAt = now
        val copy = bytes.copyOf(width * height * 4)
        handler.post {
            if (!running && items.isEmpty()) return@post
            var bitmap = previewBitmap
            if (bitmap == null || bitmap.width != width || bitmap.height != height) {
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                previewBitmap = bitmap
            }
            runCatching {
                bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(copy))
                onPreview?.invoke(bitmap)
            }
        }
    }

    private fun advance() {
        if (!running) return
        val next = index + 1
        if (next > items.lastIndex) {
            if (!looping) {
                playing = false
                runCatching { player?.pause() }
                audio?.pause()
                handler.removeCallbacks(pump)
                onEnded?.invoke()
                return
            }
            index = 0
        } else {
            index = next
        }
        playItem()
    }

    private fun releaseItem() {
        itemToken++
        handler.removeCallbacks(pump)
        playing = false
        runCatching { player?.release() }
        player = null
        surface?.release()
        surface = null
        audio?.close()
        audio = null
        still = ByteArray(0)
        stillWidth = 0
        stillHeight = 0
        durationMs = 0
        pausedPosition = 0
    }

    private fun positionMs(): Int = when (kind) {
        MediaKind.VIDEO -> runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)
        else -> {
            val extra = if (playing) (SystemClock.elapsedRealtime() - itemStartedAt).toInt() else 0
            (pausedPosition + extra).coerceAtLeast(0)
        }
    }

    private fun decodeImage(uri: Uri): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > 1920 || bounds.outHeight / sample > 1080) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }.getOrNull()

    private fun readDuration(uri: Uri): Int = runCatching {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, uri)
        val value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        retriever.release()
        value?.toIntOrNull() ?: 0
    }.getOrDefault(0)

    private fun readRotation(uri: Uri): Int = runCatching {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, uri)
        val value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
        retriever.release()
        value?.toIntOrNull() ?: 0
    }.getOrDefault(0)

    companion object {
        private const val TAG = "MediaPlaylistPlayer"
    }
}

/**
 * Hilo de OpenGL que convierte la SurfaceTexture de MediaPlayer en frames RGBA
 * con la orientación y la rotación correctas para NDI.
 */
private class FrameRenderer(private val onFrame: (ByteArray, Int, Int) -> Unit) {

    companion object {
        private const val TAG = "MediaFrameRenderer"
        private val QUAD = floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f
        )
        private const val VERTEX_SHADER = """
            attribute vec4 aPos;
            attribute vec4 aTex;
            uniform mat4 uTexMatrix;
            uniform mat2 uRot;
            varying vec2 vTex;
            void main() {
                vec2 p = vec2(aPos.x, -aPos.y);
                gl_Position = vec4(uRot * p, 0.0, 1.0);
                vTex = (uTexMatrix * aTex).xy;
            }
        """
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES sTex;
            void main() {
                gl_FragColor = vec4(texture2D(sTex, vTex).rgb, 1.0);
            }
        """
    }

    private val thread = HandlerThread("media-gl").apply { start() }
    private val handler = Handler(thread.looper)
    private val texMatrix = FloatArray(16)
    private val quad: FloatBuffer = ByteBuffer
        .allocateDirect(QUAD.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(QUAD)
            position(0)
        }

    private var display: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var pbuffer: EGLSurface? = null
    private var program = 0
    private var positionHandle = -1
    private var texHandle = -1
    private var matrixHandle = -1
    private var rotationHandle = -1
    private var fbo = 0
    private var colorTexture = 0
    private var videoWidth = 0
    private var videoHeight = 0
    private var outWidth = 0
    private var outHeight = 0
    private var frameBytes = ByteArray(0)
    private var pixels: ByteBuffer? = null
    private var rotation = 0

    @Volatile
    private var surfaceTexture: SurfaceTexture? = null

    fun start(): SurfaceTexture? {
        val latch = CountDownLatch(1)
        var created: SurfaceTexture? = null
        handler.post {
            try {
                initEgl()
                program = buildProgram()
                if (program == 0) throw IllegalStateException("programa GL inválido")
                val textures = IntArray(1)
                GLES20.glGenTextures(1, textures, 0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])
                GLES20.glTexParameterf(
                    GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MIN_FILTER,
                    GLES20.GL_LINEAR.toFloat()
                )
                GLES20.glTexParameterf(
                    GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MAG_FILTER,
                    GLES20.GL_LINEAR.toFloat()
                )
                GLES20.glTexParameteri(
                    GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_S,
                    GLES20.GL_CLAMP_TO_EDGE
                )
                GLES20.glTexParameteri(
                    GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_T,
                    GLES20.GL_CLAMP_TO_EDGE
                )
                val texture = SurfaceTexture(textures[0])
                texture.setDefaultBufferSize(1280, 720)
                texture.setOnFrameAvailableListener({ draw() }, handler)
                surfaceTexture = texture
                created = texture
            } catch (t: Throwable) {
                Log.e(TAG, "No se pudo iniciar OpenGL", t)
            } finally {
                latch.countDown()
            }
        }
        latch.await(4, TimeUnit.SECONDS)
        return created
    }

    fun setVideoSize(width: Int, height: Int) {
        handler.post {
            videoWidth = width
            videoHeight = height
            applySize()
        }
    }

    fun setRotation(degrees: Int) {
        handler.post {
            rotation = ((degrees % 360) + 360) % 360
            applySize()
        }
    }

    fun release() {
        handler.post {
            surfaceTexture?.setOnFrameAvailableListener(null)
            surfaceTexture?.release()
            surfaceTexture = null
            if (program != 0) GLES20.glDeleteProgram(program)
            if (fbo != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            if (colorTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(colorTexture), 0)
            fbo = 0
            colorTexture = 0
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )
            display?.let { current ->
                if (pbuffer != null) EGL14.eglDestroySurface(current, pbuffer)
                eglContext?.let { EGL14.eglDestroyContext(current, it) }
                EGL14.eglTerminate(current)
            }
            display = null
            eglContext = null
            pbuffer = null
            thread.quitSafely()
        }
    }

    private fun applySize() {
        if (videoWidth <= 0 || videoHeight <= 0) return
        val swapped = rotation == 90 || rotation == 270
        val width = if (swapped) videoHeight else videoWidth
        val height = if (swapped) videoWidth else videoHeight
        if (width == outWidth && height == outHeight && fbo != 0) return
        outWidth = width
        outHeight = height
        surfaceTexture?.setDefaultBufferSize(videoWidth, videoHeight)
        frameBytes = ByteArray(width * height * 4)
        pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        setupFramebuffer(width, height)
    }

    private fun initEgl() {
        val eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)
        val configAttrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, configAttrs, 0, configs, 0, 1, count, 0)
        val contextAttrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        val context = EGL14.eglCreateContext(
            eglDisplay,
            configs[0],
            EGL14.EGL_NO_CONTEXT,
            contextAttrs,
            0
        )
        val surfaceAttrs = intArrayOf(
            EGL14.EGL_WIDTH, 1,
            EGL14.EGL_HEIGHT, 1,
            EGL14.EGL_NONE
        )
        val surface = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0], surfaceAttrs, 0)
        EGL14.eglMakeCurrent(eglDisplay, surface, surface, context)
        display = eglDisplay
        eglContext = context
        pbuffer = surface
    }

    private fun buildProgram(): Int {
        val vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        if (vertex == 0 || fragment == 0) return 0
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "Enlace GL fallido: ${GLES20.glGetProgramInfoLog(program)}")
            GLES20.glDeleteProgram(program)
            return 0
        }
        positionHandle = GLES20.glGetAttribLocation(program, "aPos")
        texHandle = GLES20.glGetAttribLocation(program, "aTex")
        matrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix")
        rotationHandle = GLES20.glGetUniformLocation(program, "uRot")
        return program
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "Compilación GL fallida: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private fun setupFramebuffer(width: Int, height: Int) {
        if (fbo != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        if (colorTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(colorTexture), 0)
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        colorTexture = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, colorTexture)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
        )
        val buffers = IntArray(1)
        GLES20.glGenFramebuffers(1, buffers, 0)
        fbo = buffers[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, colorTexture, 0
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.w(TAG, "Framebuffer incompleto: $status")
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun draw() {
        val texture = surfaceTexture ?: return
        try {
            texture.updateTexImage()
            val width = outWidth
            val height = outHeight
            val target = pixels
            if (fbo == 0 || width <= 0 || height <= 0 || target == null) return
            texture.getTransformMatrix(texMatrix)

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)

            val radians = Math.toRadians(rotation.toDouble())
            val cos = Math.cos(radians).toFloat()
            val sin = Math.sin(radians).toFloat()
            GLES20.glUniformMatrix2fv(
                rotationHandle, 1, false, floatArrayOf(cos, sin, -sin, cos), 0
            )
            GLES20.glUniformMatrix4fv(matrixHandle, 1, false, texMatrix, 0)

            quad.position(0)
            GLES20.glEnableVertexAttribArray(positionHandle)
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 16, quad)
            quad.position(2)
            GLES20.glEnableVertexAttribArray(texHandle)
            GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 16, quad)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(positionHandle)
            GLES20.glDisableVertexAttribArray(texHandle)

            // Se lee mientras el FBO sigue enlazado: si se desenlaza antes, la
            // lectura sale del framebuffer por defecto (un pbuffer de 1×1).
            target.position(0)
            GLES20.glReadPixels(
                0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, target
            )
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            target.position(0)
            target.get(frameBytes)
            onFrame(frameBytes, width, height)
        } catch (t: Throwable) {
            Log.w(TAG, "Frame de vídeo fallido", t)
        }
    }
}
