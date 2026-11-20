package com.ndiapp.camera.net

import java.nio.ByteBuffer

/**
 * Un cuadro YUV 4:2:0 tal como lo entrega Camera2. Los búferes son los del
 * ImageReader y sólo valen mientras la cámara procesa el cuadro, así que quien
 * lo consuma tiene que copiar lo que necesite en el acto.
 */
class YuvFrame(
    val width: Int,
    val height: Int,
    val y: ByteBuffer,
    val yRowStride: Int,
    val yPixelStride: Int,
    val u: ByteBuffer,
    val uRowStride: Int,
    val uPixelStride: Int,
    val v: ByteBuffer,
    val vRowStride: Int,
    val vPixelStride: Int,
    val timestampNs: Long
)
