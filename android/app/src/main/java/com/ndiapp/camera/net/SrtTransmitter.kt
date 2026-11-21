package com.ndiapp.camera.net

import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Emisor SRT: recibe frames RGBA (el mismo buffer que se manda por NDI),
 * los convierte a I420, los codifica en H.264 por hardware, los empaqueta en
 * MPEG-TS y los envía por un socket SRT en modo caller.
 *
 * Todo el trabajo pesado ocurre en un hilo propio; [pushFrame] no bloquea.
 */
class SrtTransmitter {

    data class Config(
        val host: String,
        val port: Int,
        val width: Int,
        val height: Int,
        val fps: Int = 30,
        val bitrate: Int = 4_000_000,
        val latencyMs: Int = 120,
        val passphrase: String = "",
    )

    private val running = AtomicBoolean(false)
    private var socket = SrtNative.INVALID_HANDLE
    private var encoder: H264Encoder? = null
    private var queue: ArrayBlockingQueue<ByteArray>? = null
    private var thread: Thread? = null
    private var config: Config? = null
    private var frameIndex = 0L
    private val muxer = TsMuxer()

    @Volatile
    var droppedFrames: Long = 0
        private set

    @Volatile
    var sentFrames: Long = 0
        private set

    val isRunning: Boolean get() = running.get()

    /** Abre el socket SRT, arranca el encoder y el hilo de envío. */
    fun start(config: Config): Boolean {
        if (running.get()) return true
        if (!SrtNative.isAvailable) {
            Log.e(TAG, "libsrt no está disponible en este build")
            return false
        }
        if (config.width <= 0 || config.height <= 0) return false

        socket = SrtNative.createCaller(config.latencyMs, config.passphrase.ifEmpty { null })
        if (socket == SrtNative.INVALID_HANDLE) {
            Log.e(TAG, "No se pudo crear el socket SRT: ${SrtNative.lastError()}")
            return false
        }
        if (!SrtNative.connect(socket, config.host, config.port)) {
            Log.e(TAG, "No se pudo conectar a ${config.host}:${config.port}: ${SrtNative.lastError()}")
            SrtNative.close(socket)
            socket = SrtNative.INVALID_HANDLE
            return false
        }

        val newEncoder = H264Encoder(config.width, config.height, config.fps, config.bitrate)
        if (!newEncoder.start()) {
            Log.e(TAG, "El encoder H.264 no arrancó")
            SrtNative.close(socket)
            socket = SrtNative.INVALID_HANDLE
            return false
        }

        this.config = config
        encoder = newEncoder
        queue = ArrayBlockingQueue(FRAME_QUEUE_SIZE)
        frameIndex = 0
        droppedFrames = 0
        sentFrames = 0
        running.set(true)
        thread = Thread({ loop() }, "srt-transmitter").apply { start() }
        Log.i(TAG, "SRT emitiendo a ${config.host}:${config.port} (libsrt ${SrtNative.version()})")
        return true
    }

    /**
     * Encola un frame RGBA (width*height*4 bytes) para emisión.
     * Si la cola está llena se descarta el frame más viejo: es preferible perder
     * uno a acumular latencia.
     */
    fun pushFrame(rgba: ByteArray) {
        val cfg = config ?: return
        val expected = cfg.width * cfg.height * 4
        if (rgba.size < expected) return

        val q = queue ?: return
        if (!q.offer(rgba)) {
            q.poll()
            droppedFrames++
            q.offer(rgba)
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        thread?.join(1500)
        thread = null
        encoder?.stop()
        encoder = null
        queue = null
        if (socket != SrtNative.INVALID_HANDLE) {
            SrtNative.close(socket)
            socket = SrtNative.INVALID_HANDLE
        }
        Log.i(TAG, "SRT detenido (enviados=$sentFrames, descartados=$droppedFrames)")
    }

    private fun loop() {
        val cfg = config ?: return
        val activeEncoder = encoder ?: return
        val q = queue ?: return

        while (running.get()) {
            val frame = q.poll(200, TimeUnit.MILLISECONDS) ?: continue
            val i420 = rgbaToI420(frame, cfg.width, cfg.height)
            val ptsUs = frameIndex * 1_000_000L / cfg.fps
            frameIndex++

            for (accessUnit in activeEncoder.encode(i420, ptsUs)) {
                val ts = muxer.writeVideoFrame(accessUnit.data, accessUnit.ptsUs, accessUnit.keyFrame)
                if (ts.isEmpty()) continue
                val sent = SrtNative.send(socket, ts, 0, ts.size, SEND_TIMEOUT_MS)
                if (sent < 0) {
                    Log.w(TAG, "Envío SRT falló: ${SrtNative.lastError()}")
                } else {
                    sentFrames++
                }
            }
        }
    }

    companion object {
        private const val TAG = "SrtTransmitter"
        private const val FRAME_QUEUE_SIZE = 3
        private const val SEND_TIMEOUT_MS = 2000

        /** RGBA empaquetado (R,G,B,A) a I420 BT.601 de rango limitado. */
        fun rgbaToI420(rgba: ByteArray, width: Int, height: Int): ByteArray {
            val ySize = width * height
            val out = ByteArray(ySize + ySize / 2)
            val uOffset = ySize
            val vOffset = ySize + ySize / 4

            // Plano Y y submuestreo 2x2 de U/V en el mismo recorrido.
            var uIndex = uOffset
            var vIndex = vOffset
            var y = 0
            while (y < height - 1) {
                var x = 0
                while (x < width - 1) {
                    val i00 = (y * width + x) * 4
                    val i01 = i00 + 4
                    val i10 = i00 + width * 4
                    val i11 = i10 + 4

                    val r0 = rgba[i00].toInt() and 0xFF
                    val g0 = rgba[i00 + 1].toInt() and 0xFF
                    val b0 = rgba[i00 + 2].toInt() and 0xFF
                    val r1 = rgba[i01].toInt() and 0xFF
                    val g1 = rgba[i01 + 1].toInt() and 0xFF
                    val b1 = rgba[i01 + 2].toInt() and 0xFF
                    val r2 = rgba[i10].toInt() and 0xFF
                    val g2 = rgba[i10 + 1].toInt() and 0xFF
                    val b2 = rgba[i10 + 2].toInt() and 0xFF
                    val r3 = rgba[i11].toInt() and 0xFF
                    val g3 = rgba[i11 + 1].toInt() and 0xFF
                    val b3 = rgba[i11 + 2].toInt() and 0xFF

                    out[y * width + x] = luma(r0, g0, b0)
                    out[y * width + x + 1] = luma(r1, g1, b1)
                    out[(y + 1) * width + x] = luma(r2, g2, b2)
                    out[(y + 1) * width + x + 1] = luma(r3, g3, b3)

                    val r = (r0 + r1 + r2 + r3) / 4
                    val g = (g0 + g1 + g2 + g3) / 4
                    val b = (b0 + b1 + b2 + b3) / 4
                    out[uIndex++] = ((-38 * r - 74 * g + 112 * b + 128) shr 8).toByte()
                    out[vIndex++] = ((112 * r - 94 * g - 18 * b + 128) shr 8).toByte()

                    x += 2
                }
                y += 2
            }
            return out
        }

        private fun luma(r: Int, g: Int, b: Int): Byte =
                (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte()
    }
}
