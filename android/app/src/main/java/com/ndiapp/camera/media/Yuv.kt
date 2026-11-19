package com.ndiapp.camera.media

import androidx.camera.core.ImageProxy

/** Conversión de frames YUV_420_888 de CameraX a RGBA compacto. */
object Yuv {

    /**
     * Escribe el frame en [buffer] con las filas compactas y aplicando la rotación.
     * Devuelve [ancho, alto] del resultado.
     */
    fun toRgba(image: ImageProxy, rotationDegrees: Int, buffer: ByteArray): IntArray {
        val srcW = image.width
        val srcH = image.height
        val swap = rotationDegrees == 90 || rotationDegrees == 270
        val dstW = if (swap) srcH else srcW
        val dstH = if (swap) srcW else srcH
        require(buffer.size >= dstW * dstH * 4) { "buffer demasiado pequeño" }

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride

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
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        var sum = 0L
        var count = 0
        var y = 0
        while (y < image.height) {
            val base = y * rowStride
            var x = 0
            while (x < image.width) {
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
