package com.ndiapp.camera.media

import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

/** Conversión de frames YUV_420_888 a RGBA compacto. */
object Yuv {

    /**
     * Escribe el frame en [buffer] con las filas compactas y aplicando la rotación.
     * Devuelve [ancho, alto] del resultado.
     */
    fun toRgba(image: ImageProxy, rotationDegrees: Int, buffer: ByteArray): IntArray {
        val y = image.planes[0]
        val u = image.planes[1]
        val v = image.planes[2]
        return toRgba(
            image.width,
            image.height,
            rotationDegrees,
            y.buffer,
            y.rowStride,
            y.pixelStride,
            u.buffer,
            u.rowStride,
            u.pixelStride,
            v.buffer,
            v.rowStride,
            v.pixelStride,
            buffer
        )
    }

    /** Igual que la versión de [ImageProxy], pero desde planos de Camera2. */
    @Suppress("LongParameterList")
    fun toRgba(
        srcW: Int,
        srcH: Int,
        rotationDegrees: Int,
        yBuf: ByteBuffer,
        yRowStride: Int,
        yPixelStride: Int,
        uBuf: ByteBuffer,
        uRowStride: Int,
        uPixelStride: Int,
        vBuf: ByteBuffer,
        vRowStride: Int,
        vPixelStride: Int,
        buffer: ByteArray
    ): IntArray {
        val swap = rotationDegrees == 90 || rotationDegrees == 270
        val dstW = if (swap) srcH else srcW
        val dstH = if (swap) srcW else srcH
        require(buffer.size >= dstW * dstH * 4) { "buffer demasiado pequeño" }

        for (sy in 0 until srcH) {
            val yBase = sy * yRowStride
            val uvRow = sy / 2
            val uBase = uvRow * uRowStride
            val vBase = uvRow * vRowStride
            for (sx in 0 until srcW) {
                val y = yBuf.get(yBase + sx * yPixelStride).toInt() and 0xFF
                val u = (uBuf.get(uBase + (sx / 2) * uPixelStride).toInt() and 0xFF) - 128
                val v = (vBuf.get(vBase + (sx / 2) * vPixelStride).toInt() and 0xFF) - 128

                val c = if (y < 16) 0 else y - 16
                val r = clamp((298 * c + 409 * v + 128) shr 8)
                val g = clamp((298 * c - 100 * u - 208 * v + 128) shr 8)
                val b = clamp((298 * c + 516 * u + 128) shr 8)

                val dx: Int
                val dy: Int
                when (rotationDegrees) {
                    90 -> {
                        dx = srcH - 1 - sy
                        dy = sx
                    }

                    180 -> {
                        dx = srcW - 1 - sx
                        dy = srcH - 1 - sy
                    }

                    270 -> {
                        dx = sy
                        dy = srcW - 1 - sx
                    }

                    else -> {
                        dx = sx
                        dy = sy
                    }
                }
                val offset = (dy * dstW + dx) * 4
                buffer[offset] = r.toByte()
                buffer[offset + 1] = g.toByte()
                buffer[offset + 2] = b.toByte()
                buffer[offset + 3] = 0xFF.toByte()
            }
        }
        return intArrayOf(dstW, dstH)
    }

    /**
     * Luma media del plano Y (0..1), muestreando de 8 en 8 píxeles. Sirve para la
     * exposición automática cuando ISO u obturación están en manual.
     */
    fun averageLuma(image: ImageProxy): Float {
        val plane = image.planes[0]
        return averageLuma(
            image.width, image.height, plane.buffer, plane.rowStride, plane.pixelStride
        )
    }

    /** Igual que la versión de [ImageProxy], pero con los planos de Camera2. */
    fun averageLuma(
        width: Int,
        height: Int,
        buffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int
    ): Float {
        var sum = 0L
        var count = 0
        var y = 0
        while (y < height) {
            val base = y * rowStride
            var x = 0
            while (x < width) {
                sum += buffer.get(base + x * pixelStride).toInt() and 0xFF
                count++
                x += 8
            }
            y += 8
        }
        return if (count == 0) 0f else (sum.toFloat() / count) / 255f
    }

    private fun clamp(value: Int): Int = when {
        value < 0 -> 0
        value > 255 -> 255
        else -> value
    }
}
