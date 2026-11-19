package com.ndiapp.camera.usb

/** Formato de vídeo que anuncia una cámara UVC. */
enum class UvcFormatKind { MJPEG, UNCOMPRESSED }

data class UvcFrame(
    val index: Int,
    val width: Int,
    val height: Int,
    val defaultInterval: Int,
    val maxFrameSize: Int
) {
    /** Fotogramas por segundo del intervalo por defecto (dwFrameInterval en 100 ns). */
    val fps: Int
        get() = if (defaultInterval > 0) (10_000_000 / defaultInterval).coerceAtLeast(1) else 30
}

data class UvcFormat(
    val index: Int,
    val kind: UvcFormatKind,
    val bitsPerPixel: Int,
    val frames: List<UvcFrame>
)

data class UvcTopology(
    val controlInterface: Int,
    val streamingInterface: Int,
    val terminalLink: Int,
    val formats: List<UvcFormat>
) {
    fun bestFormat(): UvcFormat? = formats.firstOrNull { it.kind == UvcFormatKind.MJPEG }
        ?: formats.firstOrNull()

    fun bestFrame(format: UvcFormat): UvcFrame? = format.frames
        .filter { it.width in 160..3840 && it.height in 120..2160 }
        .sortedWith(
            compareByDescending<UvcFrame> {
                // Preferir lo más cercano a 1080p sin pasarse
                if (it.width <= 1920 && it.height <= 1080) it.width * it.height else -it.width * it.height
            }.thenByDescending { if (it.fps in 24..31) 1 else 0 }
        )
        .firstOrNull()
        ?: format.frames.firstOrNull()
}

/**
 * Lector de descriptores UVC (clase USB 0x0E).
 *
 * Android no expone los descriptores de clase a través de UsbInterface, así que
 * se recorre el bloque crudo de `UsbDeviceConnection.rawDescriptors` y se
 * extraen la interfaz de control, la de streaming y los formatos con sus
 * resoluciones e intervalos de cuadro.
 */
object UvcDescriptors {

    private const val DESC_INTERFACE = 0x04
    private const val DESC_CS_INTERFACE = 0x24
    private const val CLASS_VIDEO = 0x0E
    private const val SUBCLASS_CONTROL = 1
    private const val SUBCLASS_STREAMING = 2

    private const val VS_INPUT_HEADER = 0x01
    private const val VS_FORMAT_UNCOMPRESSED = 0x04
    private const val VS_FRAME_UNCOMPRESSED = 0x05
    private const val VS_FORMAT_MJPEG = 0x06
    private const val VS_FRAME_MJPEG = 0x07

    fun parse(raw: ByteArray): UvcTopology? {
        var offset = 0
        var controlInterface = -1
        var streamingInterface = -1
        var terminalLink = 0

        var interfaceNumber = -1
        var interfaceClass = 0
        var interfaceSubclass = 0

        val formats = mutableListOf<UvcFormat>()
        var pendingIndex = -1
        var pendingKind = UvcFormatKind.MJPEG
        var pendingBits = 16
        val pendingFrames = mutableListOf<UvcFrame>()

        fun flushFormat() {
            if (pendingIndex >= 0) {
                formats.add(
                    UvcFormat(pendingIndex, pendingKind, pendingBits, pendingFrames.toList())
                )
            }
            pendingIndex = -1
            pendingFrames.clear()
        }

        while (offset + 2 <= raw.size) {
            val length = raw[offset].toInt() and 0xFF
            if (length < 2 || offset + length > raw.size) break
            val type = raw[offset + 1].toInt() and 0xFF

            when (type) {
                DESC_INTERFACE -> {
                    if (length >= 9) {
                        interfaceNumber = raw[offset + 2].toInt() and 0xFF
                        interfaceClass = raw[offset + 5].toInt() and 0xFF
                        interfaceSubclass = raw[offset + 6].toInt() and 0xFF
                        if (interfaceClass == CLASS_VIDEO &&
                            interfaceSubclass == SUBCLASS_CONTROL
                        ) {
                            controlInterface = interfaceNumber
                        }
                        if (interfaceClass == CLASS_VIDEO &&
                            interfaceSubclass == SUBCLASS_STREAMING
                        ) {
                            if (streamingInterface != interfaceNumber) {
                                flushFormat()
                            }
                            streamingInterface = interfaceNumber
                        }
                    }
                }

                DESC_CS_INTERFACE -> {
                    if (interfaceClass != CLASS_VIDEO ||
                        interfaceSubclass != SUBCLASS_STREAMING || length < 3
                    ) {
                        offset += length
                        continue
                    }
                    when (val subtype = raw[offset + 2].toInt() and 0xFF) {
                        VS_INPUT_HEADER -> if (length >= 5) {
                            terminalLink = raw[offset + 4].toInt() and 0xFF
                        }

                        VS_FORMAT_MJPEG -> if (length >= 4) {
                            flushFormat()
                            pendingIndex = raw[offset + 3].toInt() and 0xFF
                            pendingKind = UvcFormatKind.MJPEG
                            pendingBits = 0
                        }

                        VS_FORMAT_UNCOMPRESSED -> if (length >= 4) {
                            flushFormat()
                            pendingIndex = raw[offset + 3].toInt() and 0xFF
                            pendingKind = UvcFormatKind.UNCOMPRESSED
                            pendingBits = if (length > 0x15) raw[offset + 0x15].toInt() and 0xFF else 16
                        }

                        VS_FRAME_MJPEG -> if (length >= 0x16 && pendingIndex >= 0) {
                            val width = le16(raw, offset + 5)
                            val height = le16(raw, offset + 7)
                            val interval = le32(raw, offset + 0x11)
                            if (width > 0 && height > 0) {
                                pendingFrames.add(
                                    UvcFrame(
                                        index = raw[offset + 3].toInt() and 0xFF,
                                        width = width,
                                        height = height,
                                        defaultInterval = interval,
                                        maxFrameSize = width * height * 3 / 4
                                    )
                                )
                            }
                        }

                        VS_FRAME_UNCOMPRESSED -> if (length >= 0x1A && pendingIndex >= 0) {
                            val width = le16(raw, offset + 5)
                            val height = le16(raw, offset + 7)
                            val maxSize = le32(raw, offset + 0x11)
                            val interval = le32(raw, offset + 0x15)
                            if (width > 0 && height > 0) {
                                pendingFrames.add(
                                    UvcFrame(
                                        index = raw[offset + 3].toInt() and 0xFF,
                                        width = width,
                                        height = height,
                                        defaultInterval = interval,
                                        maxFrameSize = maxSize
                                    )
                                )
                            }
                        }

                        else -> Unit
                    }
                }
            }
            offset += length
        }
        flushFormat()

        if (streamingInterface < 0 || formats.isEmpty()) return null
        return UvcTopology(
            controlInterface = controlInterface,
            streamingInterface = streamingInterface,
            terminalLink = terminalLink,
            formats = formats
        )
    }

    private fun le16(data: ByteArray, offset: Int): Int {
        if (offset + 1 >= data.size) return 0
        return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun le32(data: ByteArray, offset: Int): Int {
        if (offset + 3 >= data.size) return 0
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
    }
}
