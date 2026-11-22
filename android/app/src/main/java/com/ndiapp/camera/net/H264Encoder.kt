package com.ndiapp.camera.net

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log

/**
 * Codificador H.264 en modo búferes (sin superficie): recibe el YUV que ya
 * entrega la cámara y devuelve unidades de acceso en Annex-B, listas para el
 * empaquetador MPEG-TS. Al no usar una superficie de entrada, la sesión de
 * Camera2 no se toca: transmitir no puede romper la vista previa ni la NDI.
 *
 * El caso normal (cámara en NV12) se copia por filas con `ByteBuffer.get`, que
 * es un memcpy: el hilo de la cámara sólo paga una copia de 3 MB a 1080p. El
 * camino lento, pixel a pixel, queda sólo para cámaras en I420 y codificadores
 * que no aceptan NV12.
 */
class H264Encoder(
    private val width: Int,
    private val height: Int,
    fps: Int,
    bitrate: Int,
    /** false fuerza YUV420 flexible, para codificadores sin NV12. */
    preferSemiPlanar: Boolean = true
) {
    private companion object {
        const val TAG = "H264Encoder"

        /** Variante de Qualcomm del NV12 (0x7FA30C02). */
        const val COLOR_QCOM_NV12 = 0x7FA30C02.toInt()
    }

    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val bufferInfo = MediaCodec.BufferInfo()
    private val frameSize = width * height * 3 / 2

    private val nv12 = ByteArray(frameSize)
    private val y = ByteArray(width * height)
    private val u = ByteArray(width * height / 4)
    private val v = ByteArray(width * height / 4)

    // Filas de origen para reducir el cuadro sin leer byte a byte del búfer.
    private var rowBuffer = ByteArray(0)
    private var chromaRowBuffer = ByteArray(0)

    private var spsPps: ByteArray? = null

    private val semiPlanar: Boolean = preferSemiPlanar && runCatching {
        val formats = codec.codecInfo
            .getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            .colorFormats
        formats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) ||
            formats.contains(COLOR_QCOM_NV12)
    }.getOrDefault(false)

    /** Se llama por cada cuadro codificado: datos, PTS en µs y si es clave. */
    var onAccessUnit: ((ByteArray, Long, Boolean) -> Unit)? = null

    var isRunning = false
        private set

    /** Último fallo al cargar un cuadro, para poder mostrarlo en pantalla. */
    @Volatile
    var lastError: String = ""
        private set

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            if (semiPlanar) {
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            } else {
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            }
        )
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        isRunning = true
    }

    fun submit(frame: YuvFrame) {
        if (!isRunning) return
        try {
            // Si la cámara entrega más resolución que la elegida, se reduce al
            // vuelo; el codificador siempre recibe el tamaño configurado.
            val scaled = frame.width != width || frame.height != height
            if (semiPlanar) {
                if (scaled) buildNv12Scaled(frame) else buildNv12(frame)
            } else {
                if (scaled) buildI420Scaled(frame) else buildI420(frame)
            }
            // Un poco de espera: con 0 ms el codificador descartaba cuadros
            // mientras terminaba de arrancar y la salida quedaba en 0 fps.
            val index = codec.dequeueInputBuffer(10_000)
            if (index < 0) return
            if (semiPlanar) {
                val buffer = codec.getInputBuffer(index)
                if (buffer == null) {
                    // Nunca se abandona el índice: se devuelve vacío y el
                    // codificador sigue aceptando cuadros.
                    codec.queueInputBuffer(index, 0, 0, frame.timestampNs / 1000, 0)
                    return
                }
                buffer.clear()
                buffer.put(nv12, 0, frameSize)
            } else {
                val image = codec.getInputImage(index)
                if (image == null) {
                    codec.queueInputBuffer(index, 0, 0, frame.timestampNs / 1000, 0)
                    return
                }
                fillImage(image)
            }
            codec.queueInputBuffer(index, 0, frameSize, frame.timestampNs / 1000, 0)
            drain()
        } catch (t: Throwable) {
            lastError = t.javaClass.simpleName + ": " + (t.message ?: "")
            Log.w(TAG, "No se pudo codificar el cuadro", t)
        }
    }

    /** Pide un cuadro clave para que el receptor arranque sin esperar. */
    fun requestKeyFrame() {
        if (!isRunning) return
        runCatching {
            codec.setParameters(
                android.os.Bundle().apply {
                    putInt(android.media.MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                }
            )
        }
    }

    fun release() {
        if (!isRunning) return
        isRunning = false
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    private fun drain() {
        while (true) {
            val index = codec.dequeueOutputBuffer(bufferInfo, 0)
            if (index < 0) return
            val buffer = codec.getOutputBuffer(index)
            if (buffer != null && bufferInfo.size > 0) {
                buffer.position(bufferInfo.offset)
                buffer.limit(bufferInfo.offset + bufferInfo.size)
                val data = ByteArray(bufferInfo.size)
                buffer.get(data)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    // SPS/PPS: se reenvían delante de cada cuadro clave.
                    spsPps = data
                } else {
                    val key = bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    val config = spsPps
                    val payload = if (key && config != null) combine(config, data) else data
                    onAccessUnit?.invoke(payload, bufferInfo.presentationTimeUs, key)
                }
            }
            codec.releaseOutputBuffer(index, false)
        }
    }

    private fun combine(config: ByteArray, data: ByteArray): ByteArray {
        val out = ByteArray(config.size + data.size)
        System.arraycopy(config, 0, out, 0, config.size)
        System.arraycopy(data, 0, out, config.size, data.size)
        return out
    }

    /** NV12 contiguo: Y y después UV intercalado, todo con copias por fila. */
    private fun buildNv12(frame: YuvFrame) {
        val luma = frame.y
        val savedY = luma.position()
        for (row in 0 until height) {
            luma.position(row * frame.yRowStride)
            luma.get(nv12, row * width, width)
        }
        luma.position(savedY)

        val chromaHeight = height / 2
        val uvBase = width * height
        if (frame.uPixelStride == 2 && frame.vPixelStride == 2) {
            val chroma = frame.u
            val savedU = chroma.position()
            for (row in 0 until chromaHeight) {
                chroma.position(row * frame.uRowStride)
                chroma.get(nv12, uvBase + row * width, width)
            }
            chroma.position(savedU)
        } else {
            var uPosition = 0
            var vPosition = 0
            for (row in 0 until chromaHeight) {
                uPosition = row * frame.uRowStride
                vPosition = row * frame.vRowStride
                var dest = uvBase + row * width
                for (column in 0 until width / 2) {
                    nv12[dest++] = frame.u.get(uPosition)
                    uPosition += frame.uPixelStride
                    nv12[dest++] = frame.v.get(vPosition)
                    vPosition += frame.vPixelStride
                }
            }
        }
    }

    /** I420 contiguo, para codificadores sin NV12: camino lento. */
    private fun buildI420(frame: YuvFrame) {
        var index = 0
        for (row in 0 until height) {
            var position = row * frame.yRowStride
            for (column in 0 until width) {
                y[index++] = frame.y.get(position)
                position += frame.yPixelStride
            }
        }
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        index = 0
        for (row in 0 until chromaHeight) {
            var position = row * frame.uRowStride
            for (column in 0 until chromaWidth) {
                u[index++] = frame.u.get(position)
                position += frame.uPixelStride
            }
        }
        index = 0
        for (row in 0 until chromaHeight) {
            var position = row * frame.vRowStride
            for (column in 0 until chromaWidth) {
                v[index++] = frame.v.get(position)
                position += frame.vPixelStride
            }
        }
    }

    /** NV12 reducido por muestreo de filas: se lee una fila de origen por fila. */
    private fun buildNv12Scaled(frame: YuvFrame) {
        val srcWidth = frame.width
        val srcHeight = frame.height
        if (rowBuffer.size < srcWidth) rowBuffer = ByteArray(srcWidth)
        val luma = frame.y
        val savedY = luma.position()
        for (row in 0 until height) {
            val sourceRow = row * srcHeight / height
            val base = sourceRow * frame.yRowStride
            val dest = row * width
            if (frame.yPixelStride == 1) {
                luma.position(base)
                luma.get(rowBuffer, 0, srcWidth)
                if (srcWidth == width) {
                    System.arraycopy(rowBuffer, 0, nv12, dest, width)
                } else {
                    for (column in 0 until width) {
                        nv12[dest + column] = rowBuffer[column * srcWidth / width]
                    }
                }
            } else {
                for (column in 0 until width) {
                    val sourceColumn = column * srcWidth / width
                    nv12[dest + column] = luma.get(base + sourceColumn * frame.yPixelStride)
                }
            }
        }
        luma.position(savedY)

        val srcChromaWidth = srcWidth / 2
        val srcChromaHeight = srcHeight / 2
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        val uvBase = width * height
        if (frame.uPixelStride == 2 && frame.vPixelStride == 2) {
            val needed = srcChromaWidth * 2
            if (chromaRowBuffer.size < needed) chromaRowBuffer = ByteArray(needed)
            val chroma = frame.u
            val savedU = chroma.position()
            for (row in 0 until chromaHeight) {
                val sourceRow = row * srcChromaHeight / chromaHeight
                val base = sourceRow * frame.uRowStride
                var dest = uvBase + row * width
                chroma.position(base)
                chroma.get(chromaRowBuffer, 0, needed)
                if (srcChromaWidth == chromaWidth) {
                    System.arraycopy(chromaRowBuffer, 0, nv12, dest, needed)
                } else {
                    for (column in 0 until chromaWidth) {
                        val sourceColumn = column * srcChromaWidth / chromaWidth
                        nv12[dest++] = chromaRowBuffer[sourceColumn * 2]
                        nv12[dest++] = chromaRowBuffer[sourceColumn * 2 + 1]
                    }
                }
            }
            chroma.position(savedU)
        } else {
            for (row in 0 until chromaHeight) {
                val sourceRow = row * srcChromaHeight / chromaHeight
                val uBase = sourceRow * frame.uRowStride
                val vBase = sourceRow * frame.vRowStride
                var dest = uvBase + row * width
                for (column in 0 until chromaWidth) {
                    val sourceColumn = column * srcChromaWidth / chromaWidth
                    nv12[dest++] = frame.u.get(uBase + sourceColumn * frame.uPixelStride)
                    nv12[dest++] = frame.v.get(vBase + sourceColumn * frame.vPixelStride)
                }
            }
        }
    }

    /** I420 reducido por muestreo, para codificadores sin NV12. */
    private fun buildI420Scaled(frame: YuvFrame) {
        val srcWidth = frame.width
        val srcHeight = frame.height
        if (rowBuffer.size < srcWidth) rowBuffer = ByteArray(srcWidth)
        var index = 0
        for (row in 0 until height) {
            val sourceRow = row * srcHeight / height
            val base = sourceRow * frame.yRowStride
            if (frame.yPixelStride == 1) {
                frame.y.position(base)
                frame.y.get(rowBuffer, 0, srcWidth)
                for (column in 0 until width) {
                    y[index++] = rowBuffer[column * srcWidth / width]
                }
            } else {
                for (column in 0 until width) {
                    val sourceColumn = column * srcWidth / width
                    y[index++] = frame.y.get(base + sourceColumn * frame.yPixelStride)
                }
            }
        }
        val srcChromaWidth = srcWidth / 2
        val srcChromaHeight = srcHeight / 2
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        index = 0
        for (row in 0 until chromaHeight) {
            val sourceRow = row * srcChromaHeight / chromaHeight
            val base = sourceRow * frame.uRowStride
            for (column in 0 until chromaWidth) {
                val sourceColumn = column * srcChromaWidth / chromaWidth
                u[index++] = frame.u.get(base + sourceColumn * frame.uPixelStride)
            }
        }
        index = 0
        for (row in 0 until chromaHeight) {
            val sourceRow = row * srcChromaHeight / chromaHeight
            val base = sourceRow * frame.vRowStride
            for (column in 0 until chromaWidth) {
                val sourceColumn = column * srcChromaWidth / chromaWidth
                v[index++] = frame.v.get(base + sourceColumn * frame.vPixelStride)
            }
        }
    }

    /** Copia I420 en las imágenes del codificador respetando sus zancadas. */
    private fun fillImage(image: android.media.Image) {
        val luma = image.planes[0]
        for (row in 0 until height) {
            val base = row * luma.rowStride
            val source = row * width
            for (column in 0 until width) {
                luma.buffer.put(base + column * luma.pixelStride, y[source + column])
            }
        }
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        val cb = image.planes[1]
        val cr = image.planes[2]
        val interleaved = cb.pixelStride == 2 && cr.pixelStride == 2
        for (row in 0 until chromaHeight) {
            val source = row * chromaWidth
            val baseCb = row * cb.rowStride
            val baseCr = row * cr.rowStride
            for (column in 0 until chromaWidth) {
                if (interleaved) {
                    cb.buffer.put(baseCb + column * 2, u[source + column])
                    cr.buffer.put(baseCr + column * 2, v[source + column])
                } else {
                    cb.buffer.put(baseCb + column * cb.pixelStride, u[source + column])
                    cr.buffer.put(baseCr + column * cr.pixelStride, v[source + column])
                }
            }
        }
    }
}
