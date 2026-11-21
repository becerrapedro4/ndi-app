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
            if (semiPlanar) buildNv12(frame) else buildI420(frame)
            val index = codec.dequeueInputBuffer(0)
            if (index < 0) return
            if (semiPlanar) {
                val buffer = codec.getInputBuffer(index) ?: return
                buffer.clear()
                buffer.put(nv12, 0, frameSize)
            } else {
                val image = codec.getInputImage(index) ?: return
                fillImage(image)
            }
            codec.queueInputBuffer(index, 0, frameSize, frame.timestampNs / 1000, 0)
            drain()
        } catch (t: Throwable) {
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
