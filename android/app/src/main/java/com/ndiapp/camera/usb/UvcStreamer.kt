package com.ndiapp.camera.usb

import android.content.Context
import android.graphics.BitmapFactory
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** Conversión YUY2 (UVC sin comprimir de 16 bpp) a RGBA. */
object UvcYuv {

    fun yuy2ToRgba(source: ByteArray, width: Int, height: Int): ByteArray {
        val output = ByteArray(width * height * 4)
        val pairs = (width * height) / 2
        var read = 0
        var write = 0
        for (index in 0 until pairs) {
            if (read + 3 >= source.size || write + 7 >= output.size) break
            val y0 = source[read].toInt() and 0xFF
            val u = (source[read + 1].toInt() and 0xFF) - 128
            val y1 = source[read + 2].toInt() and 0xFF
            val v = (source[read + 3].toInt() and 0xFF) - 128
            writePixel(output, write, y0, u, v)
            writePixel(output, write + 4, y1, u, v)
            read += 4
            write += 8
        }
        return output
    }

    private fun writePixel(output: ByteArray, offset: Int, y: Int, u: Int, v: Int) {
        val c = (y - 16).coerceAtLeast(0)
        output[offset] = (((298 * c + 409 * v + 128) shr 8).coerceIn(0, 255)).toByte()
        output[offset + 1] = (((298 * c - 100 * u - 208 * v + 128) shr 8).coerceIn(0, 255)).toByte()
        output[offset + 2] = (((298 * c + 516 * u + 128) shr 8).coerceIn(0, 255)).toByte()
        output[offset + 3] = 0xFF.toByte()
    }
}

/**
 * Captura real de una cámara UVC: reclama la interfaz de streaming, negocia
 * formato con Probe/Commit, lee del endpoint (bulk si existe, si no isocrono),
 * rearma los frames con la cabecera de payload UVC y los entrega en RGBA.
 */
class UvcStreamer(
    private val context: Context,
    private val camera: UvcCamera,
    private val onFrame: (rgba: ByteArray, width: Int, height: Int, fps: Int) -> Unit,
    private val onStatus: (String) -> Unit
) {

    companion object {
        private const val TAG = "UvcStreamer"
        private const val PROBE_CONTROL = 0x01
        private const val COMMIT_CONTROL = 0x02
        private const val GET_CUR = 0x81
        private const val SET_CUR = 0x01
        private const val ISOC_REQUESTS = 12
    }

    private var connection: UsbDeviceConnection? = null
    private var thread: Thread? = null
    private var interfaceUsed: UsbInterface? = null

    @Volatile
    private var running = false

    private var formatKind = UvcFormatKind.MJPEG
    private var streamFps = 30
    private var frameWidth = 0
    private var frameHeight = 0
    private var isBulk = false
    private var bufferSize = 0
    private var lastEmit = 0L

    private val frameBuffer = ByteArrayOutputStream(512 * 1024)
    private var lastFid = -1

    val isRunning: Boolean
        get() = running

    fun start(): Boolean {
        if (running) return true
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return fail("Este dispositivo no tiene USB host")
        val device = manager.deviceList.values
            .firstOrNull { it.deviceName == camera.deviceName }
            ?: return fail("La cámara no está conectada")
        if (!manager.hasPermission(device)) return fail("Falta el permiso de acceso USB")

        val conn = manager.openDevice(device) ?: return fail("No se pudo abrir la cámara")
        connection = conn

        val raw = runCatching { conn.rawDescriptors }.getOrNull()
        if (raw == null || raw.isEmpty()) {
            conn.close()
            connection = null
            return fail("No se pudieron leer los descriptores USB")
        }
        val topology = UvcDescriptors.parse(raw)
        if (topology == null) {
            conn.close()
            connection = null
            return fail("La cámara no anuncia formatos UVC compatibles")
        }

        val format = topology.bestFormat()
        val frame = format?.let { topology.bestFrame(it) }
        if (format == null || frame == null) {
            conn.close()
            connection = null
            return fail("La cámara no ofrece resoluciones utilizables")
        }
        formatKind = format.kind
        frameWidth = frame.width
        frameHeight = frame.height

        var chosenInterface: UsbInterface? = null
        var chosenEndpoint: UsbEndpoint? = null
        var chosenBulk = false
        for (index in 0 until device.interfaceCount) {
            val candidate = device.getInterface(index)
            if (candidate.id != topology.streamingInterface || candidate.alternateSetting == 0) {
                continue
            }
            var bestEndpoint: UsbEndpoint? = null
            for (endpointIndex in 0 until candidate.endpointCount) {
                val endpoint = candidate.getEndpoint(endpointIndex)
                if (endpoint.direction != UsbConstants.USB_DIR_IN) continue
                val type = endpoint.type
                if (type != UsbConstants.USB_ENDPOINT_XFER_ISOC &&
                    type != UsbConstants.USB_ENDPOINT_XFER_BULK
                ) {
                    continue
                }
                if (bestEndpoint == null || endpoint.maxPacketSize > bestEndpoint.maxPacketSize) {
                    bestEndpoint = endpoint
                }
            }
            val endpoint = bestEndpoint ?: continue
            val bulk = endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK
            val current = chosenEndpoint
            val better = when {
                current == null -> true
                bulk && !chosenBulk -> true
                bulk == chosenBulk && endpoint.maxPacketSize > current.maxPacketSize -> true
                else -> false
            }
            if (better) {
                chosenInterface = candidate
                chosenEndpoint = endpoint
                chosenBulk = bulk
            }
        }

        val streamInterface = chosenInterface
        val endpoint = chosenEndpoint
        if (streamInterface == null || endpoint == null) {
            conn.close()
            connection = null
            return fail("La cámara no expone un endpoint de vídeo utilizable")
        }
        isBulk = chosenBulk

        if (!conn.claimInterface(streamInterface, true)) {
            conn.close()
            connection = null
            return fail("No se pudo reclamar la interfaz de vídeo")
        }
        interfaceUsed = streamInterface

        val maxPayload = try {
            negotiate(conn, topology, format, frame)
        } catch (t: Throwable) {
            Log.w(TAG, "Negociación UVC fallida", t)
            conn.releaseInterface(streamInterface)
            conn.close()
            connection = null
            return fail(t.message ?: "La cámara rechazó el formato elegido")
        }

        if (!conn.setInterface(streamInterface)) {
            Log.w(TAG, "setInterface devolvió false; se intenta emitir igualmente")
        }

        if (isBulk) {
            bufferSize = (if (maxPayload > 0) maxPayload else 64 * 1024).coerceIn(1024, 1 shl 20)
        } else {
            val packet = endpoint.maxPacketSize and 0x7FF
            bufferSize = if (packet > 0) packet else (endpoint.maxPacketSize and 0xFFFF).coerceAtLeast(1024)
        }

        running = true
        val worker = Thread {
            try {
                if (isBulk) runBulk(conn, endpoint) else runIsochronous(conn, endpoint)
            } catch (t: Throwable) {
                if (running) {
                    Log.w(TAG, "Lectura UVC interrumpida", t)
                    onStatus("Se perdió la cámara: ${t.message}")
                }
            }
        }
        worker.name = "uvc-capture"
        worker.priority = Thread.MAX_PRIORITY
        thread = worker
        worker.start()

        onStatus(
            "${if (formatKind == UvcFormatKind.MJPEG) "MJPEG" else "YUY2"} " +
                "$frameWidth×$frameHeight @ ${streamFps} fps · " +
                "${if (isBulk) "bulk" else "isocrono"}"
        )
        return true
    }

    fun stop() {
        running = false
        runCatching { connection?.close() }
        thread?.let { runCatching { it.join(900) } }
        thread = null
        interfaceUsed = null
        connection = null
    }

    private fun runBulk(conn: UsbDeviceConnection, endpoint: UsbEndpoint) {
        val buffer = ByteArray(bufferSize)
        while (running) {
            val read = runCatching { conn.bulkTransfer(endpoint, buffer, buffer.size, 500) }
                .getOrDefault(-1)
            if (read <= 0) {
                if (!running) break
                continue
            }
            handlePacket(buffer, read, isIsochronous = false)
        }
    }

    private fun runIsochronous(conn: UsbDeviceConnection, endpoint: UsbEndpoint) {
        val size = bufferSize
        val requests = ArrayList<UsbRequest>(ISOC_REQUESTS)
        val buffers = ArrayList<ByteBuffer>(ISOC_REQUESTS)
        for (index in 0 until ISOC_REQUESTS) {
            val request = UsbRequest()
            if (!request.initialize(conn, endpoint)) break
            val buffer = ByteBuffer.allocateDirect(size)
            if (!request.queue(buffer, size)) break
            requests.add(request)
            buffers.add(buffer)
        }
        if (requests.isEmpty()) {
            onStatus("No se pudo encolar la lectura isocrona")
            return
        }
        val data = ByteArray(size)
        while (running) {
            val done = runCatching { conn.requestWait() }.getOrNull() ?: break
            val index = requests.indexOf(done)
            if (index < 0) continue
            val buffer = buffers[index]
            buffer.position(0)
            buffer.get(data)
            handlePacket(data, size, isIsochronous = true)
            java.util.Arrays.fill(data, 0)
            buffer.clear()
            buffer.put(data)
            buffer.position(0)
            if (!running) break
            if (!done.queue(buffer, size)) break
        }
        for (request in requests) {
            runCatching { request.cancel() }
            runCatching { request.close() }
        }
    }

    /**
     * Cabecera de payload UVC: bHeaderLength, bmHeaderInfo (FID/EOF/error) y
     * opcionalmente PTS. Un frame termina al cambiar el FID o al llegar EOF.
     */
    private fun handlePacket(data: ByteArray, length: Int, isIsochronous: Boolean) {
        var offset = 0
        while (offset + 2 <= length) {
            val first = data[offset].toInt() and 0xFF
            val info = data[offset + 1].toInt() and 0xFF
            val headerLength = if (first == 0xFF) {
                if (offset + 3 > length) return
                data[offset + 2].toInt() and 0xFF
            } else {
                first
            }
            if (headerLength < 2 || offset + headerLength > length) return

            val fid = info and 0x01
            val endOfFrame = info and 0x02 != 0
            val error = info and 0x40 != 0

            if (fid != lastFid && frameBuffer.size() > 0) closeFrame()
            lastFid = fid
            if (!error) {
                frameBuffer.write(data, offset + headerLength, length - offset - headerLength)
            }
            if (endOfFrame) closeFrame()
            if (!isIsochronous) return
            offset = length
        }
    }

    private fun closeFrame() {
        if (frameBuffer.size() < 8) {
            frameBuffer.reset()
            return
        }
        val bytes = frameBuffer.toByteArray()
        frameBuffer.reset()
        emit(bytes)
    }

    private fun emit(data: ByteArray) {
        val minimumGap = 1000L / streamFps.coerceIn(1, 60)
        val now = System.currentTimeMillis()
        if (now - lastEmit < minimumGap) return
        if (formatKind == UvcFormatKind.MJPEG) {
            val end = jpegEnd(data)
            if (end <= 4) return
            if ((data[0].toInt() and 0xFF) != 0xFF || (data[1].toInt() and 0xFF) != 0xD8) return
            val jpeg = if (end == data.size) data else data.copyOf(end)
            val bitmap = runCatching { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) }
                .getOrNull() ?: return
            val width = bitmap.width
            val height = bitmap.height
            if (width <= 0 || height <= 0) {
                bitmap.recycle()
                return
            }
            val rgba = ByteArray(width * height * 4)
            bitmap.copyPixelsToBuffer(ByteBuffer.wrap(rgba))
            bitmap.recycle()
            lastEmit = now
            onFrame(rgba, width, height, streamFps)
        } else {
            val width = frameWidth
            val height = frameHeight
            val needed = width * height * 2
            if (data.size < needed) return
            val rgba = UvcYuv.yuy2ToRgba(data, width, height)
            lastEmit = now
            onFrame(rgba, width, height, streamFps)
        }
    }

    /** Los buffers isocronos pueden venir con cola obsoleta: se corta en el EOI. */
    private fun jpegEnd(data: ByteArray): Int {
        var index = data.size - 2
        while (index > 0) {
            if ((data[index].toInt() and 0xFF) == 0xFF &&
                (data[index + 1].toInt() and 0xFF) == 0xD9
            ) {
                return index + 2
            }
            index--
        }
        return -1
    }

    private fun negotiate(
        conn: UsbDeviceConnection,
        topology: UvcTopology,
        format: UvcFormat,
        frame: UvcFrame
    ): Int {
        val index = topology.streamingInterface
        var size = 34
        var probe = ByteArray(size)
        if (conn.controlTransfer(0xA1, GET_CUR, PROBE_CONTROL shl 8, index, probe, size, 600) < 0) {
            size = 26
            probe = ByteArray(size)
            if (conn.controlTransfer(0xA1, GET_CUR, PROBE_CONTROL shl 8, index, probe, size, 600) < 0) {
                throw IllegalStateException("La cámara no respondió a VS_PROBE_CONTROL")
            }
        }

        put16(probe, 0, 0x0001)
        probe[2] = format.index.toByte()
        probe[3] = frame.index.toByte()
        put32(probe, 4, frame.defaultInterval)
        put16(probe, 8, 1)
        put16(probe, 10, 0)
        put16(probe, 12, 0)
        put16(probe, 14, 0)
        put16(probe, 16, 0)

        if (conn.controlTransfer(0x21, SET_CUR, PROBE_CONTROL shl 8, index, probe, size, 600) < 0) {
            throw IllegalStateException("La cámara rechazó el formato pedido")
        }

        val accepted = ByteArray(size)
        if (conn.controlTransfer(0xA1, GET_CUR, PROBE_CONTROL shl 8, index, accepted, size, 600) < 0) {
            throw IllegalStateException("La cámara no confirmó el formato")
        }
        val acceptedFormat = accepted[2].toInt() and 0xFF
        val acceptedFrame = accepted[3].toInt() and 0xFF
        if (acceptedFormat != format.index || acceptedFrame != frame.index) {
            throw IllegalStateException(
                "La cámara eligió otro formato (${acceptedFormat}/${acceptedFrame})"
            )
        }
        val interval = le32(accepted, 4)
        if (interval > 0) streamFps = (10_000_000 / interval).coerceIn(1, 60)

        val maxPayload = if (size >= 26) le32(accepted, 22) else 0

        if (conn.controlTransfer(0x21, SET_CUR, COMMIT_CONTROL shl 8, index, accepted, size, 600) < 0) {
            throw IllegalStateException("La cámara rechazó el commit del formato")
        }
        return maxPayload
    }

    private fun fail(message: String): Boolean {
        Log.w(TAG, message)
        onStatus(message)
        return false
    }

    private fun put16(data: ByteArray, offset: Int, value: Int) {
        if (offset + 1 >= data.size) return
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun put32(data: ByteArray, offset: Int, value: Int) {
        if (offset + 3 >= data.size) return
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value shr 8) and 0xFF).toByte()
        data[offset + 2] = ((value shr 16) and 0xFF).toByte()
        data[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun le32(data: ByteArray, offset: Int): Int {
        if (offset + 3 >= data.size) return 0
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
    }
}
