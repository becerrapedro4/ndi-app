package com.ndiapp.camera.ndi

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** Descubrimiento de fuentes NDI presentes en la red local. */
class NdiFinder {

    private var handle: Long = NdiNative.findCreate()

    /** Última lista buena: un sondeo vacío no tiene por qué borrarla. */
    @Volatile
    private var last: List<NdiSource> = emptyList()
    private var emptyPolls = 0

    val isAvailable: Boolean
        get() = handle != 0L

    @Synchronized
    fun sources(timeoutMs: Int = 600): List<NdiSource> {
        if (handle == 0L) return last
        val raw = NdiNative.findSources(handle, timeoutMs) ?: return last
        val parsed = raw.map { entry ->
            val parts = entry.split('\t')
            NdiSource(
                name = parts.firstOrNull()?.substringAfterLast('(', parts.first())?.trimEnd(')')
                    ?: parts.first(),
                fullName = parts.firstOrNull().orEmpty(),
                url = parts.getOrNull(1).orEmpty()
            )
        }
        if (parsed.isEmpty()) {
            // El finder de NDI tarda en poblarse y cada espera puede vencer sin
            // novedades. Borrar la lista en el primer vacío hacía que las
            // fuentes aparecieran y desaparecieran mientras se buscaban.
            emptyPolls++
            if (emptyPolls < 3) return last
            last = emptyList()
        } else {
            emptyPolls = 0
            last = parsed
        }
        return last
    }

    /**
     * Rehace la instancia de NDI. Al cambiar la red del proceso (bind a Wi-Fi)
     * las tomas del finder quedan sobre la interfaz vieja y libndi caía dentro
     * de `find_get_current_sources`; con una instancia nueva eso no pasa.
     */
    @Synchronized
    fun restart() {
        if (handle != 0L) {
            NdiNative.findDestroy(handle)
            handle = 0L
        }
        handle = NdiNative.findCreate()
        last = emptyList()
        emptyPolls = 0
    }

    @Synchronized
    fun close() {
        if (handle != 0L) {
            NdiNative.findDestroy(handle)
            handle = 0L
        }
        last = emptyList()
        emptyPolls = 0
    }
}

/**
 * Descubrimiento continuo, compartido por el monitor y el mezclador.
 *
 * Antes cada pestaña pedía fuentes cada 2,5 s con una espera corta: entre
 * consulta y consulta había dos segundos a ciegas y, si se reiniciaba el finder
 * al cambiar la red, la lista se vaciaba y las fuentes tardaban en volver a
 * aparecer. Acá hay un solo finder que escucha sin pausa y guarda la última
 * lista buena; las pestañas sólo leen ese resultado.
 */
object NdiDiscovery {

    private val finder = NdiFinder()

    @Volatile
    private var current: List<NdiSource> = emptyList()

    @Volatile
    private var started = false

    /** El reinicio se pide y lo hace el propio hilo entre dos esperas. */
    @Volatile
    private var restartRequested = false

    private var thread: Thread? = null

    /** Última lista conocida; no bloquea ni toca la red. */
    fun sources(): List<NdiSource> = current

    @Synchronized
    fun start() {
        if (started) return
        started = true
        val worker = Thread {
            while (started) {
                if (restartRequested) {
                    restartRequested = false
                    runCatching { finder.restart() }
                }
                // Espera larga: el hilo del finder de NDI escucha entre vueltas
                // y la lista acumulada se mantiene.
                current = finder.sources(900)
                runCatching { Thread.sleep(100) }
            }
        }
        worker.name = "ndi-discovery"
        worker.isDaemon = true
        worker.priority = Thread.NORM_PRIORITY
        thread = worker
        worker.start()
    }

    /** Rehace el finder tras un cambio de red, sin cortar la lista actual. */
    fun restart() {
        if (!started) return
        restartRequested = true
    }

    fun stop() {
        started = false
        thread?.interrupt()
        thread = null
        current = emptyList()
    }
}

data class NdiSource(val name: String, val fullName: String, val url: String)

/**
 * Bytes que el sistema entregó a la app. El SDK de NDI no informa el caudal del
 * flujo recibido y el cuadro llega ya decodificado en RGBA, así que sumar su
 * tamaño daba cifras imposibles (4 Gb/s a 1080p60). Esto mide la red de verdad.
 */
private fun receivedBytes(): Long {
    val total = TrafficStats.getUidRxBytes(Process.myUid())
    return if (total < 0) 0L else total
}

/** Bitmap del tamaño pedido, reutilizando el anterior cuando ya sirve. */
private fun reusableBitmap(
    current: Bitmap?,
    width: Int,
    height: Int,
    store: (Bitmap) -> Unit
): Bitmap = if (current != null && current.width == width && current.height == height) {
    current
} else {
    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also(store)
}

/** Medidas del flujo recibido, actualizadas dos veces por segundo. */
data class StreamStats(
    val fps: Float = 0f,
    val mbps: Float = 0f,
    val levelLeft: Float = 0f,
    val levelRight: Float = 0f,
    val audioRate: Int = 0,
    val audioChannels: Int = 0,
    val audioPackets: Int = 0,
    val audioProblem: String = "",
    val width: Int = 0,
    val height: Int = 0
)

/** Recibe video y audio de una fuente NDI y los entrega a la UI. */
class NdiReceiverController {

    @Volatile
    private var handle: Long = 0L
    private var job: Job? = null

    /** Serializa el arranque y el cierre: libndi no admite usarse y destruirse a la vez. */
    private val lock = Any()
    private var track: AudioTrack? = null
    private var trackRate = 0
    private var trackChannels = 0

    // El audio va en su propio hilo: un write bloqueante no puede frenar el vídeo.
    private val audioQueue = ArrayBlockingQueue<AudioBlock>(16)

    @Volatile
    private var audioWriter: Thread? = null

    @Volatile
    private var audioProblem = ""

    @Volatile
    private var levelLeft = 0f

    @Volatile
    private var levelRight = 0f

    private class AudioBlock(
        val pcm: FloatArray,
        val samples: Int,
        val channels: Int,
        val rate: Int
    )

    val isRunning: Boolean
        get() = handle != 0L

    /**
     * Cuadro RGBA tal como llega de la red, sin pasar por Bitmap. Lo usa el
     * mezclador para subirlo a una textura GL mientras el búfer sigue válido.
     */
    var onRgbaFrame: ((rgba: ByteArray, width: Int, height: Int) -> Unit)? = null

    fun start(
        scope: CoroutineScope,
        source: NdiSource,
        audioEnabled: () -> Boolean,
        lowBandwidth: Boolean = false,
        /** Alto máximo del cuadro entregado a la UI; 0 deja el de la fuente. */
        maxHeight: () -> Int = { 0 },
        /** null cuando sólo interesa el cuadro RGBA crudo (mezclador). */
        onFrame: ((Bitmap) -> Unit)? = null,
        /**
         * PCM recibido, para el mezclador de audio. Se llama desde el hilo de
         * recepción y el búfer se reutiliza en el bloque siguiente: copiarlo si
         * se va a guardar.
         */
        onAudio: ((pcm: FloatArray, samples: Int, channels: Int, sampleRate: Int) -> Unit)? = null,
        onStats: (StreamStats) -> Unit = {}
    ) {
        // El anterior se destruye recién cuando su hilo salió de libndi.
        releasePrevious()
        val socket = NdiNative.recvCreate(source.fullName, source.url, lowBandwidth)
        if (socket == 0L) return
        synchronized(lock) { handle = socket }

        // Se arranca en 1080p y se agranda solo cuando la fuente entrega 4K: el
        // puente avisa con el tipo 7 que el cuadro no entraba.
        var rgbaBuffer = ByteArray(1920 * 1080 * 4)
        val pcmBuffer = FloatArray(48_000 * 2)
        startAudioWriter()

        job = scope.launch(Dispatchers.Default) {
            var sourceFrame: Bitmap? = null
            var scaledA: Bitmap? = null
            var scaledB: Bitmap? = null
            var useScaledB = false
            val scalePaint = Paint().apply { isFilterBitmap = true }
            val sourceRect = Rect()
            val targetRect = Rect()
            var shownWidth = 0
            var shownHeight = 0
            var frames = 0
            var lastRx = receivedBytes()
            var audioRate = 0
            var audioChannels = 0
            var audioPackets = 0
            var windowStart = SystemClock.elapsedRealtime()
            while (isActive) {
                val poll = NdiNative.recvPoll(socket, rgbaBuffer, pcmBuffer, 60) ?: break
                when (poll[0]) {
                    // 7 = el cuadro no entraba en el búfer: se agranda y se sigue.
                    1, 7 -> {
                        val width = poll[1]
                        val height = poll[2]
                        val needed = width * height * 4
                        if (width > 0 && height > 0 && needed > rgbaBuffer.size) {
                            rgbaBuffer = ByteArray(needed)
                        }
                        if (width > 0 && height > 0 && poll[0] == 1) {
                            frames++
                            onRgbaFrame?.invoke(rgbaBuffer, width, height)
                            val sink = onFrame
                            if (sink != null) {
                                // La copia del RGBA y la reducción se hacen en este hilo:
                                // al principal solo llega el cuadro ya listo para pintar.
                                val limit = maxHeight()
                                val outHeight = if (limit in 1 until height) limit else height
                                val outWidth = if (outHeight == height) {
                                    width
                                } else {
                                    ((width.toLong() * outHeight / height).toInt() and -2)
                                        .coerceAtLeast(2)
                                }
                                val full = reusableBitmap(sourceFrame, width, height) {
                                    sourceFrame = it
                                }
                                full.copyPixelsFromBuffer(
                                    ByteBuffer.wrap(rgbaBuffer, 0, width * height * 4)
                                )
                                val output = if (outWidth == width && outHeight == height) {
                                    full
                                } else {
                                    // Dos bitmaps alternos: el visor pinta uno mientras se
                                    // escribe el otro, sin cortes ni asignaciones por cuadro.
                                    val slotB = useScaledB
                                    useScaledB = !useScaledB
                                    val spare = if (slotB) scaledB else scaledA
                                    val bitmap = reusableBitmap(spare, outWidth, outHeight) {
                                        if (slotB) scaledB = it else scaledA = it
                                    }
                                    sourceRect.set(0, 0, width, height)
                                    targetRect.set(0, 0, outWidth, outHeight)
                                    Canvas(bitmap).drawBitmap(
                                        full, sourceRect, targetRect, scalePaint
                                    )
                                    bitmap
                                }
                                shownWidth = outWidth
                                shownHeight = outHeight
                                withContext(Dispatchers.Main) { sink(output) }
                            }
                        }
                    }

                    2 -> {
                        audioPackets++
                        val samples = poll[3]
                        val channels = poll[4]
                        audioRate = poll[5]
                        audioChannels = channels
                        updateLevels(pcmBuffer, samples, channels)
                        if (samples > 0 && channels > 0) {
                            onAudio?.invoke(pcmBuffer, samples, channels, poll[5])
                        }
                        if (audioEnabled() && samples > 0 && channels > 0) {
                            // Copia mínima y a la cola: el hilo del vídeo no espera
                            // a que el AudioTrack acepte el bloque.
                            val block = AudioBlock(
                                pcmBuffer.copyOf(samples * channels),
                                samples,
                                channels,
                                poll[5]
                            )
                            if (!audioQueue.offer(block)) {
                                audioProblem = "la salida de audio no da abasto"
                            }
                        }
                    }
                }

                val now = SystemClock.elapsedRealtime()
                val elapsed = now - windowStart
                if (elapsed >= 500) {
                    val seconds = elapsed / 1000f
                    // Caudal real: diferencia de bytes recibidos en la ventana.
                    val rx = receivedBytes()
                    val delta = if (rx >= lastRx) rx - lastRx else 0L
                    lastRx = rx
                    val stats = StreamStats(
                        fps = frames / seconds,
                        mbps = delta * 8f / 1_000_000f / seconds,
                        levelLeft = levelLeft,
                        levelRight = levelRight,
                        audioRate = audioRate,
                        audioChannels = audioChannels,
                        audioPackets = audioPackets,
                        audioProblem = audioProblem,
                        width = shownWidth,
                        height = shownHeight
                    )
                    audioPackets = 0
                    levelLeft *= 0.6f
                    levelRight *= 0.6f
                    frames = 0
                    windowStart = now
                    withContext(Dispatchers.Main) { onStats(stats) }
                }
            }
        }
    }

    private fun updateLevels(data: FloatArray, samples: Int, channels: Int) {
        if (samples <= 0 || channels <= 0) return
        var left = 0f
        var right = 0f
        for (index in 0 until samples) {
            val l = data[index * channels]
            left += l * l
            if (channels > 1) {
                val r = data[index * channels + 1]
                right += r * r
            } else {
                right += l * l
            }
        }
        val rmsLeft = kotlin.math.sqrt(left / samples)
        val rmsRight = kotlin.math.sqrt(right / samples)
        levelLeft = maxOf(rmsLeft, levelLeft)
        levelRight = maxOf(rmsRight, levelRight)
    }

    /** Hilo propio del audio: escribe en el AudioTrack sin frenar la recepción. */
    private fun startAudioWriter() {
        if (audioWriter != null) return
        val thread = Thread {
            while (!Thread.currentThread().isInterrupted) {
                val block = runCatching { audioQueue.poll(200, TimeUnit.MILLISECONDS) }
                    .getOrNull() ?: continue
                audioProblem = writeAudio(block.pcm, block.samples, block.channels, block.rate)
            }
        }
        thread.name = "ndi-recv-audio"
        thread.isDaemon = true
        audioWriter = thread
        thread.start()
    }

    private fun stopAudioWriter() {
        audioWriter?.interrupt()
        audioWriter = null
        audioQueue.clear()
    }

    /** Devuelve un mensaje si la salida de audio no pudo escribirse. */
    private fun writeAudio(data: FloatArray, samples: Int, channels: Int, rate: Int): String {
        if (samples <= 0 || channels <= 0 || rate <= 0) return ""
        if (track == null || trackRate != rate || trackChannels != channels) {
            track?.release()
            val channelMask =
                if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            val minimum = AudioTrack.getMinBufferSize(
                rate,
                channelMask,
                AudioFormat.ENCODING_PCM_FLOAT
            )
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(rate)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minimum, rate * channels * 4 / 5))
                .build()
            trackRate = rate
            trackChannels = channels
            track?.play()
        }
        // Bloqueante a propósito: mantiene el audio en tiempo real y evita
        // descartar muestras cuando el buffer se llena.
        val result = runCatching {
            track?.write(data, 0, samples * channels, AudioTrack.WRITE_BLOCKING)
        }
        return when {
            result.isFailure -> "error de salida: ${result.exceptionOrNull()?.message}"
            (result.getOrNull() ?: 0) < 0 -> "la salida de audio rechazó el bloque"
            else -> ""
        }
    }

    fun stop() {
        val running = synchronized(lock) {
            val current = job
            job = null
            current
        }
        val socket = synchronized(lock) {
            val current = handle
            handle = 0L
            current
        }
        running?.cancel()
        releaseAudio()
        destroyWhenIdle(running, socket)
    }

    /** Suelta la fuente anterior antes de abrir otra. */
    private fun releasePrevious() {
        val running = synchronized(lock) {
            val current = job
            job = null
            current
        }
        val socket = synchronized(lock) {
            val current = handle
            handle = 0L
            current
        }
        running?.cancel()
        releaseAudio()
        destroyWhenIdle(running, socket)
    }

    /**
     * Destruye la instancia sólo cuando el hilo de lectura ya salió de libndi.
     * Destruirla mientras `recvPoll` está dentro de la librería la hacía caer en
     * su propio hilo de captura (el crash que se veía al abrir una fuente).
     */
    private fun destroyWhenIdle(running: Job?, socket: Long) {
        if (socket == 0L) return
        Thread {
            runCatching {
                val pending = running ?: return@Thread
                kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.withTimeoutOrNull(1_500L) { pending.join() }
                }
            }
            NdiNative.recvDestroy(socket)
        }.apply {
            isDaemon = true
            name = "ndi-recv-close"
        }.start()
    }

    private fun releaseAudio() {
        stopAudioWriter()
        track?.let {
            try {
                it.pause()
                it.flush()
            } catch (_: Exception) {
            }
            it.release()
        }
        track = null
        trackRate = 0
        trackChannels = 0
        levelLeft = 0f
        levelRight = 0f
    }
}
