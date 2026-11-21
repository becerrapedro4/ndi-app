package com.ndiapp.camera.net

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.Log

/**
 * Encoder H.264 por hardware a partir de frames I420 (YUV420 planar).
 *
 * La salida se entrega en formato Annex-B (start codes 00 00 00 01), que es lo
 * que necesita el muxer MPEG-TS. MediaCodec entrega los buffers en formato AVCC
 * (prefijo de longitud de 4 bytes), así que se convierten aquí.
 */
class H264Encoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int,
) {

    /** Un access unit H.264 listo para el muxer. */
    class Frame(val data: ByteArray, val ptsUs: Long, val keyFrame: Boolean)

    private var codec: MediaCodec? = null
    private val bufferInfo = MediaCodec.BufferInfo()
    private var csd: ByteArray = ByteArray(0)
    private var requestKeyFrame = true
    private var startedAtUs = 0L

    val isRunning: Boolean get() = codec != null

    fun start(): Boolean {
        stop()
        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
        }
        return try {
            configureAndStart(format)
        } catch (e: Exception) {
            // Algunos encoders rechazan el perfil explícito: reintentar sin él.
            Log.w(TAG, "configure con perfil falló, reintentando sin perfil", e)
            runCatching {
                codec?.release()
                codec = null
                configureAndStart(MediaFormat.createVideoFormat(MIME, width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT,
                            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                })
            }.getOrElse {
                Log.e(TAG, "No se pudo iniciar el encoder H.264", it)
                false
            }
        }
    }

    private fun configureAndStart(format: MediaFormat): Boolean {
        val instance = MediaCodec.createEncoderByType(MIME)
        instance.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        instance.start()
        codec = instance
        requestKeyFrame = true
        startedAtUs = System.nanoTime() / 1000
        return true
    }

    /**
     * Codifica un frame I420.
     * @param i420 plano Y (width*height) + U (w/2*h/2) + V (w/2*h/2)
     * @param ptsUs timestamp de presentación en microsegundos
     * @return access units disponibles en este instante (puede estar vacío)
     */
    fun encode(i420: ByteArray, ptsUs: Long): List<Frame> {
        val instance = codec ?: return emptyList()
        val out = ArrayList<Frame>(2)

        if (requestKeyFrame) {
            runCatching {
                instance.setParameters(Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                })
            }
            requestKeyFrame = false
        }

        val inputIndex = instance.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (inputIndex >= 0) {
            val image = instance.getInputImage(inputIndex)
            if (image != null) {
                copyI420ToImage(image, i420)
                instance.queueInputBuffer(inputIndex, 0, i420.size, ptsUs, 0)
            } else {
                instance.queueInputBuffer(inputIndex, 0, 0, ptsUs, 0)
            }
        }

        drain(instance, out)
        return out
    }

    fun requestKeyFrame() {
        requestKeyFrame = true
    }

    fun stop() {
        val instance = codec ?: return
        codec = null
        runCatching { instance.stop() }
        runCatching { instance.release() }
        csd = ByteArray(0)
    }

    private fun drain(instance: MediaCodec, out: MutableList<Frame>) {
        while (true) {
            val index = instance.dequeueOutputBuffer(bufferInfo, OUTPUT_TIMEOUT_US)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    captureCodecSpecificData(instance.outputFormat)
                    continue
                }
                index < 0 -> continue
                else -> {
                    val buffer = instance.getOutputBuffer(index)
                    val size = bufferInfo.size
                    val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buffer != null && size > 0 && !isConfig) {
                        buffer.position(bufferInfo.offset)
                        buffer.limit(bufferInfo.offset + size)
                        val raw = ByteArray(size)
                        buffer.get(raw)
                        val annexB = toAnnexB(raw)
                        if (annexB.isNotEmpty()) {
                            val keyFrame = bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                            out.add(Frame(annexB, bufferInfo.presentationTimeUs, keyFrame))
                        }
                    }
                    instance.releaseOutputBuffer(index, false)
                }
            }
        }
    }

    /** Guarda SPS/PPS para poder prependerlos a cada IDR. */
    private fun captureCodecSpecificData(format: MediaFormat) {
        val sps = format.getByteBuffer("csd-0")
        val pps = format.getByteBuffer("csd-1")
        if (sps == null) return
        val spsBytes = ByteArray(sps.remaining()).also { sps.get(it) }
        val ppsBytes = if (pps != null) ByteArray(pps.remaining()).also { pps.get(it) } else ByteArray(0)
        csd = withStartCode(spsBytes) + withStartCode(ppsBytes)
    }

    private fun withStartCode(nal: ByteArray): ByteArray {
        if (nal.size >= 4 && nal[0] == 0.toByte() && nal[1] == 0.toByte() &&
                nal[2] == 0.toByte() && nal[3] == 1.toByte()) {
            return nal
        }
        return START_CODE + nal
    }

    /** Convierte un buffer AVCC (longitudes de 4 bytes) a Annex-B si hace falta. */
    private fun toAnnexB(raw: ByteArray): ByteArray {
        if (raw.size >= 4 && raw[0] == 0.toByte() && raw[1] == 0.toByte() &&
                (raw[2] == 1.toByte() || (raw[2] == 0.toByte() && raw[3] == 1.toByte()))) {
            // Ya viene en Annex-B.
            return raw
        }
        val out = java.io.ByteArrayOutputStream(raw.size + 16)
        var offset = 0
        var converted = false
        while (offset + 4 <= raw.size) {
            val length = ((raw[offset].toInt() and 0xFF) shl 24) or
                    ((raw[offset + 1].toInt() and 0xFF) shl 16) or
                    ((raw[offset + 2].toInt() and 0xFF) shl 8) or
                    (raw[offset + 3].toInt() and 0xFF)
            if (length <= 0 || offset + 4 + length > raw.size) break
            out.write(START_CODE)
            out.write(raw, offset + 4, length)
            offset += 4 + length
            converted = true
        }
        if (!converted || offset != raw.size) {
            Log.w(TAG, "Buffer H.264 con formato inesperado (${raw.size} bytes)")
            return ByteArray(0)
        }
        val annexB = out.toByteArray()
        val isKeyFrame = (annexB.size > 4 && (annexB[4].toInt() and 0x1F) == 5)
        return if (isKeyFrame && csd.isNotEmpty()) csd + annexB else annexB
    }

    /** Copia I420 a los planos de la Image de entrada respetando strides. */
    private fun copyI420ToImage(image: Image, i420: ByteArray) {
        val ySize = width * height
        val uvSize = ySize / 4
        copyPlane(image.planes[0], i420, 0, width, height)
        copyPlane(image.planes[1], i420, ySize, width / 2, height / 2)
        copyPlane(image.planes[2], i420, ySize + uvSize, width / 2, height / 2)
    }

    private fun copyPlane(plane: Image.Plane, source: ByteArray, sourceOffset: Int, w: Int, h: Int) {
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val row = ByteArray(rowStride)
        buffer.position(0)
        for (y in 0 until h) {
            val toRead = minOf(rowStride, buffer.remaining())
            if (toRead <= 0) break
            buffer.get(row, 0, toRead)
            val srcRow = sourceOffset + y * w
            var column = 0
            var x = 0
            while (x < w) {
                source[srcRow + x] = row[column]
                column += pixelStride
                x++
            }
        }
    }

    companion object {
        private const val TAG = "H264Encoder"
        private const val MIME = "video/avc"
        private const val INPUT_TIMEOUT_US = 0L
        private const val OUTPUT_TIMEOUT_US = 0L
        private val START_CODE = byteArrayOf(0, 0, 0, 1)
    }
}
