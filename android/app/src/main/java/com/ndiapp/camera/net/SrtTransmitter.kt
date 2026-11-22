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

    /** Estado del socket y contadores del envío, una vez por segundo. */
    var onDiagnostics: ((String) -> Unit)? = null

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

    // Calidad pedida desde los ajustes: 0 = tamaño nativo y tasa automática.
    @Volatile
    private var limitHeight = 0

    @Volatile
    private var bitrate = 0
    private val muxer = TsMuxer()
    private val queue = ArrayBlockingQueue<ByteArray>(90)
    private var writer: Thread? = null

    // Cada arranque tiene su número: un hilo viejo que termina tarde no apaga
    // la emisión nueva ni pisa su estado.
    @Volatile
    private var generation = 0

    @Volatile
    private var frames = 0

    /** Cuadros que llegaron al codificador, salgan o no. */
    @Volatile
    private var submitted = 0

    /** Formato de color con el que se intenta codificar; se prueba el otro si no sale. */
    @Volatile
    private var preferSemiPlanar = true

    @Volatile
    private var videoRetried = false

    // El sensor entrega tiempos del sistema (ns) y el receptor espera un reloj
    // que empiece cerca de cero: se resta el primero y nunca se retrocede.
    // Lo lee también el hilo del micrófono para alinear el audio.
    @Volatile
    private var ptsBase = -1L
    private var lastPts = 0L

    // El audio y el vídeo tienen que medirse con el mismo reloj: el sensor
    // entrega tiempos de arranque del equipo (incluyen el sueño), así que el
    // audio se ancla al primer cuadro con el reloj monótono del sistema.
    @Volatile
    private var realtimeBaseUs = -1L

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
        submitted = 0
        preferSemiPlanar = true
        videoRetried = false
        ptsBase = -1L
        lastPts = 0L
        realtimeBaseUs = -1L
        connected = false
        releaseEncoder()
        releaseAac()
        muxer.setAudio(false)
        val gen = ++generation
        running = true
        // El modo escucha puede tardar en recibir al emisor: el aviso lo dice
        // en vez de dejar «Conectando…» sin explicación.
        onStatus?.invoke(
            if (listening(url)) "SRT escuchando en ${describe(url)} · esperando al emisor"
            else "Conectando SRT a ${describe(url)}…"
        )
        writer = Thread({ writerLoop(url.trim(), gen) }, "srt-tx").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Fija la resolución máxima y la tasa de bits del H.264. Se puede llamar con
     * la emisión en marcha: el codificador se rehace con el tamaño nuevo y el
     * socket no se corta.
     */
    fun setVideoLimit(maxHeight: Int, bitRate: Int) {
        val height = maxHeight.coerceAtLeast(0)
        val rate = bitRate.coerceAtLeast(0)
        if (height == limitHeight && rate == bitrate) return
        limitHeight = height
        bitrate = rate
        releaseEncoder()
    }

    fun send(frame: YuvFrame) {
        if (!running || !connected) return
        if (frame.width <= 0 || frame.height <= 0) return
        val (targetWidth, targetHeight) = targetSize(frame.width, frame.height)
        ensureEncoder(targetWidth, targetHeight)
        val current = encoder ?: return
        submitted++
        current.submit(frame)
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
    /** Tamaño de salida: el nativo o el que quepa en la resolución elegida. */
    private fun targetSize(sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> {
        val max = limitHeight
        if (max <= 0 || sourceHeight <= max) return sourceWidth to sourceHeight
        val targetHeight = (max - max % 2).coerceAtLeast(2)
        val scaled = ((sourceWidth.toLong() * targetHeight + sourceHeight / 2) / sourceHeight).toInt()
        return (scaled - scaled % 2).coerceAtLeast(2) to targetHeight
    }

    @Synchronized
    private fun ensureEncoder(width: Int, height: Int) {
        if (width == encoderWidth && height == encoderHeight) return
        val previous = encoder
        encoder = null
        previous?.release()
        val bitrate = if (this.bitrate > 0) {
            this.bitrate
        } else when {
            height >= 1080 -> 6_000_000
            height >= 720 -> 4_000_000
            else -> 2_500_000
        }
        val created = runCatching { H264Encoder(width, height, fps, bitrate, preferSemiPlanar) }
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

    /**
     * Primer PTS del audio en la misma base temporal que el vídeo. Se usa el
     * reloj monótono del sistema, que es el mismo con el que se ancló el primer
     * cuadro: mezclar el reloj del sensor con System.nanoTime() dejaba al audio
     * horas adelante o atrás y el receptor no arrancaba el programa.
     */
    private fun audioStartUs(leadUs: Long): Long {
        val now = SystemClock.elapsedRealtimeNanos() / 1000L
        var base = realtimeBaseUs
        if (base < 0L) {
            base = now
            realtimeBaseUs = now
        }
        return ((now - base) - leadUs).coerceAtLeast(0L)
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
        if (ptsBase < 0L) {
            ptsBase = ptsUs
            realtimeBaseUs = SystemClock.elapsedRealtimeNanos() / 1000L
        }
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
        val socket = SrtNative.connect(url, 8000, sender = true)
        if (socket == 0L) {
            val reason = SrtNative.lastError()
            // Sin socket no hay contadores: queda anotado contra quién y por qué.
            onDiagnostics?.invoke("${describe(url)} · sin socket: $reason")
            finish(gen, "SRT: $reason")
            return
        }
        if (gen != generation) {
            SrtNative.close(socket)
            return
        }
        // Lo codificado antes de conectar es imagen vieja: se descarta, y el
        // receptor recibe el PAT/PMT antes que el primer cuadro.
        queue.clear()
        connected = true
        queue.offer(muxer.psi())
        onStatus?.invoke("SRT transmitiendo")
        val connectedAt = SystemClock.elapsedRealtime()
        var noImageWarned = false
        var windowStart = connectedAt
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
                        // Estado del socket y contadores reales de libsrt: dice
                        // si el receptor lee, si hay reenvíos o si se rompió.
                        SrtNative.stats(socket).takeIf { it.isNotBlank() }?.let { stats ->
                            onDiagnostics?.invoke(stats)
                        }
                        windowFrames = counted
                        // Llegan cuadros al codificador y no sale ninguno: en
                        // varios equipos el formato de color elegido no sirve.
                        // Se reintenta una vez con el otro antes de avisar.
                        if (!videoRetried && submitted > 0 && counted == 0) {
                            videoRetried = true
                            preferSemiPlanar = !preferSemiPlanar
                            releaseEncoder()
                        }
                        if (!noImageWarned && counted == 0 && now - connectedAt >= 3000) {
                            // Conectado y sin un solo cuadro codificado: se dice,
                            // en vez de dejar la salida en 0 fps sin explicación.
                            noImageWarned = true
                            val detail = encoder?.lastError.orEmpty()
                            onWarning?.invoke(
                                "SRT conectado, pero el codificador no entrega imagen " +
                                    "($submitted cuadros recibidos, 0 codificados)" +
                                    if (detail.isBlank()) "" else " · $detail"
                            )
                        }
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

    /** Host y puerto de la URL, para que el aviso diga contra quién se emite. */
    private fun describe(url: String): String {
        val hostPort = url.trim().removePrefix("srt://").substringBefore('?').substringBefore('/')
        return hostPort.ifBlank { "?" }
    }

    /** La URL pide esperar al receptor (?mode=listener). */
    private fun listening(url: String): Boolean =
        url.substringAfter('?', "").split('&').any { it.equals("mode=listener", true) }

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
