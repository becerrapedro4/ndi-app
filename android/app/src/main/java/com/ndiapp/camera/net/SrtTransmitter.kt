package com.ndiapp.camera.net

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Emisión SRT: codifica el YUV que entrega la cámara, lo empaqueta en MPEG-TS
 * y lo empuja por un socket SRT.
 *
 * La conexión y el envío corren en su propio hilo con una cola acotada: si la
 * red no da abasto se descartan paquetes en lugar de frenar la cámara. El hilo
 * de la cámara sólo codifica y encola.
 */
class SrtTransmitter {

    var onStatus: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onStopped: (() -> Unit)? = null

    @Volatile
    var running = false
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
    private val muxer = TsMuxer()
    private val queue = ArrayBlockingQueue<ByteArray>(90)
    private var writer: Thread? = null

    fun start(url: String, width: Int, height: Int, fps: Int) {
        if (running) return
        if (!SrtNative.available) {
            onError?.invoke("libsrt no está en este APK")
            return
        }
        if (width <= 0 || height <= 0) {
            onError?.invoke("Tamaño de vídeo inválido")
            return
        }
        val bitrate = when {
            height >= 1080 -> 6_000_000
            height >= 720 -> 4_000_000
            else -> 2_500_000
        }
        queue.clear()
        dropped = 0
        sentBytes = 0
        running = true
        onStatus?.invoke("Conectando SRT…")
        writer = Thread({ writerLoop(url) }, "srt-tx").apply {
            isDaemon = true
            start()
        }
        encoder = runCatching { H264Encoder(width, height, fps, bitrate) }.getOrElse { error ->
            running = false
            onError?.invoke("Codificador no disponible: ${error.message}")
            null
        }
        encoder?.onAccessUnit = { data, ptsUs, keyFrame ->
            val packets = muxer.encode(data, ptsUs * 90 / 1000, keyFrame)
            if (!queue.offer(packets)) dropped++
        }
    }

    fun send(frame: YuvFrame) {
        if (!running) return
        encoder?.submit(frame)
    }

    fun stop() {
        running = false
        encoder?.release()
        encoder = null
        queue.clear()
        writer?.interrupt()
        writer = null
    }

    private fun writerLoop(url: String) {
        val socket = SrtNative.connect(url, 8000)
        if (socket == 0L) {
            running = false
            encoder?.release()
            encoder = null
            onError?.invoke("SRT: ${SrtNative.lastError()}")
            onStopped?.invoke()
            return
        }
        onStatus?.invoke("SRT transmitiendo")
        try {
            while (running) {
                val chunk = queue.poll(300, TimeUnit.MILLISECONDS) ?: continue
                val written = SrtNative.write(socket, chunk, 0, chunk.size)
                if (written < 0) {
                    onError?.invoke("SRT: ${SrtNative.lastError()}")
                    break
                }
                sentBytes += written
            }
        } catch (_: InterruptedException) {
            // Cierre solicitado desde la interfaz.
        } finally {
            running = false
            SrtNative.close(socket)
            onStopped?.invoke()
        }
    }
}
