package com.ndiapp.camera.net

import android.os.SystemClock
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Emisión SRT: codifica el YUV que entrega la cámara, lo empaqueta en MPEG-TS
 * y lo empuja por un socket SRT.
 *
 * La conexión y el envío corren en su propio hilo con una cola acotada: si la
 * red no da abasto se descartan paquetes en lugar de frenar la cámara. El hilo
 * de la cámara sólo codifica y encola.
 *
 * El codificador se crea con el tamaño del primer cuadro que llega y se rehace
 * solo si cambia (otra lente, otro encuadre o la resolución del dock), así la
 * emisión no se corta al tocar la cámara. Hasta que la conexión SRT no está
 * lista no se codifica nada: lo que se manda empieza por un cuadro clave.
 */
class SrtTransmitter {

    var onStatus: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onStopped: (() -> Unit)? = null

    /** Cada segundo: caudal real en Mb/s y cuadros codificados por segundo. */
    var onProgress: ((Float, Int) -> Unit)? = null

    /** Aviso que no corta el vídeo (por ejemplo, AAC no disponible). */
    var onWarning: ((String) -> Unit)? = null

    @Volatile
    var running = false
        private set

    /** El socket ya está conectado: recién ahí se codifica. */
    @Volatile
    var connected = false
        private set

    /** Cuántos paquetes no entraron en la cola (red lenta). */
    @Volatile
    var dropped = 0
        private set

    /** Bytes confirmados por el socket. */
    @Volatile
    var sentBytes = 0L
        private set

    private var encoder: H264Encoder? = null
    private var encoderWidth = 0
    private var encoderHeight = 0
    private var aac: AacEncoder? = null
    private var aacChannels = 0
    private var aacRate = 0
    private var aacFailure = ""
    private var fps = 30
    private val muxer = TsMuxer()
    private val queue = ArrayBlockingQueue<ByteArray>(90)
    private var writer: Thread? = null

    // Cada arranque tiene su número: un hilo viejo que termina tarde no apaga
    // la emisión nueva ni pisa su estado.
    @Volatile
    private var generation = 0

    @Volatile
    private var frames = 0

    // El sensor entrega tiempos del sistema (ns) y el receptor espera un reloj
    // que empiece cerca de cero: se resta el primero y nunca se retrocede.
    // Lo lee también el hilo del micrófono para alinear el audio.
    @Volatile
    private var ptsBase = -1L
    private var lastPts = 0L

    fun start(url: String, fps: Int) {
        if (running) return
        if (!SrtNative.available) {
            onError?.invoke("libsrt no está en este APK")
            return
        }
        this.fps = fps.coerceAtLeast(1)
        queue.clear()
        dropped = 0
        sentBytes = 0
        frames = 0
        ptsBase = -1L
        lastPts = 0L
        connected = false
        releaseEncoder()
        releaseAac()
        muxer.setAudio(false)
        val gen = ++generation
        running = true
        onStatus?.invoke("Conectando SRT…")
        writer = Thread({ writerLoop(url.trim(), gen) }, "srt-tx").apply {
            isDaemon = true
            start()
        }
    }

    fun send(frame: YuvFrame) {
        if (!running || !connected) return
        if (frame.width <= 0 || frame.height <= 0) return
        ensureEncoder(frame.width, frame.height)
        encoder?.submit(frame)
    }

    /**
     * PCM del micrófono (float intercalado), el mismo que va por NDI. Se
     * convierte a AAC-LC y se muxea junto al vídeo en el MPEG-TS.
     */
    fun sendAudio(pcm: FloatArray, samples: Int, channels: Int, sampleRate: Int) {
        if (!running || !connected) return
        if (samples <= 0 || channels <= 0 || sampleRate <= 0) return
        val leadUs = samples.toLong() * 1_000_000L / sampleRate
        ensureAac(channels, sampleRate, leadUs)?.submit(pcm, samples)
    }

    fun stop() {
        generation++
        running = false
        connected = false
        queue.clear()
        writer?.interrupt()
        writer = null
        releaseEncoder()
        releaseAac()
    }

    /**
     * Crea el codificador para ese tamaño, o lo rehace si cambió. Si ninguna
     * variante de color funciona se deja anotado el tamaño para no reintentar
     * en cada cuadro.
     */
    @Synchronized
    private fun ensureEncoder(width: Int, height: Int) {
        if (width == encoderWidth && height == encoderHeight) return
        val previous = encoder
        encoder = null
        previous?.release()
        val bitrate = when {
            height >= 1080 -> 6_000_000
            height >= 720 -> 4_000_000
            else -> 2_500_000
        }
        val created = runCatching { H264Encoder(width, height, fps, bitrate, true) }
            .recoverCatching { H264Encoder(width, height, fps, bitrate, false) }
            .getOrElse { error ->
                onError?.invoke("Codificador no disponible: ${error.message}")
                null
            }
        encoderWidth = width
        encoderHeight = height
        created?.onAccessUnit = { data, ptsUs, keyFrame ->
            val muxed = muxer.encode(data, presentationTime(ptsUs), keyFrame)
            frames++
            if (!queue.offer(muxed)) dropped++
        }
        created?.requestKeyFrame()
        encoder = created
    }

    /**
     * Crea el codificador AAC y anuncia la pista en el PMT. Si el equipo no
     * tiene AAC se avisa una sola vez y el vídeo sigue saliendo solo.
     */
    @Synchronized
    private fun ensureAac(channels: Int, sampleRate: Int, leadUs: Long): AacEncoder? {
        if (aac != null && channels == aacChannels && sampleRate == aacRate) return aac
        val signature = "$channels@$sampleRate"
        if (aac == null && aacFailure == signature) return null
        aac?.release()
        aac = null
        val created = runCatching { AacEncoder(sampleRate, channels, audioStartUs(leadUs)) }
            .getOrElse { error ->
                aacFailure = signature
                onWarning?.invoke("Audio AAC no disponible: ${error.message}")
                null
            }
        aacChannels = channels
        aacRate = sampleRate
        created?.onFrame = { frame, ptsUs ->
            val muxed = muxer.encodeAudio(frame, ptsUs * 90L / 1000L)
            if (!queue.offer(muxed)) dropped++
        }
        if (created != null) {
            // El PMT tiene que anunciar el audio antes del primer PES.
            muxer.setAudio(true)
            queue.offer(muxer.psi())
        }
        aac = created
        return created
    }

    /** Primer PTS del audio en la misma base que el vídeo, sin el búfer leído. */
    private fun audioStartUs(leadUs: Long): Long {
        val base = if (ptsBase >= 0L) ptsBase else System.nanoTime() / 1000L
        return (System.nanoTime() / 1000L - base) - leadUs
    }

    @Synchronized
    private fun releaseAac() {
        aac?.release()
        aac = null
        aacChannels = 0
        aacRate = 0
        aacFailure = ""
    }

    /** PTS en unidades de 90 kHz, relativo al primer cuadro y sin retrocesos. */
    private fun presentationTime(ptsUs: Long): Long {
        if (ptsBase < 0L) ptsBase = ptsUs
        var pts = ptsUs - ptsBase
        if (pts < lastPts) pts = lastPts + 33_000L
        lastPts = pts
        return pts * 90L / 1000L
    }

    @Synchronized
    private fun releaseEncoder() {
        encoder?.release()
        encoder = null
        encoderWidth = 0
        encoderHeight = 0
    }

    private fun writerLoop(url: String, gen: Int) {
        if (url.isBlank()) {
            finish(gen, "SRT: falta la URL de salida")
            return
        }
        val socket = SrtNative.connect(url, 8000)
        if (socket == 0L) {
            finish(gen, "SRT: ${SrtNative.lastError()}")
            return
        }
        if (gen != generation) {
            SrtNative.close(socket)
            return
        }
        connected = true
        queue.clear()
        onStatus?.invoke("SRT transmitiendo")
        var windowStart = SystemClock.elapsedRealtime()
        var windowBytes = 0L
        var windowFrames = frames
        try {
            while (running && gen == generation) {
                val chunk = queue.poll(250, TimeUnit.MILLISECONDS)
                if (chunk != null) {
                    val written = SrtNative.write(socket, chunk, 0, chunk.size)
                    if (written < 0) {
                        onError?.invoke("SRT: ${SrtNative.lastError()}")
                        break
                    }
                    sentBytes += written
                    windowBytes += written
                }
                val now = SystemClock.elapsedRealtime()
                val elapsed = now - windowStart
                if (elapsed >= 1000) {
                    if (gen == generation) {
                        val mbps = (windowBytes * 8f / 1_000_000f) / (elapsed / 1000f)
                        val counted = frames
                        val rate = ((counted - windowFrames) * 1000f / elapsed).toInt()
                        onProgress?.invoke(mbps, rate)
                        windowFrames = counted
                    }
                    windowBytes = 0L
                    windowStart = now
                }
            }
        } catch (_: InterruptedException) {
            // Cierre solicitado desde la interfaz.
        } finally {
            SrtNative.close(socket)
            if (gen == generation) {
                running = false
                connected = false
                queue.clear()
                releaseEncoder()
                releaseAac()
                onStopped?.invoke()
            }
        }
    }

    /** Fallo antes de conectar: se informa y se cierra la emisión. */
    private fun finish(gen: Int, message: String) {
        if (gen != generation) return
        running = false
        connected = false
        releaseEncoder()
        releaseAac()
        onError?.invoke(message)
        onStopped?.invoke()
    }
}
