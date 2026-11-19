package com.ndiapp.camera.ndi

/** Fuente NDI de salida (video + audio). */
class NdiSender(val name: String) {

    private val handle: Long = NdiNative.sendCreate(name)

    @Volatile
    private var closed = false

    val isValid: Boolean
        get() = handle != 0L

    fun sendVideo(rgba: ByteArray, width: Int, height: Int, fps: Int) {
        if (closed || handle == 0L || width <= 0 || height <= 0) return
        NdiNative.sendVideo(handle, rgba, width, height, fps, 1)
    }

    fun sendAudio(pcm: FloatArray, samples: Int, channels: Int, sampleRate: Int) {
        if (closed || handle == 0L || samples <= 0) return
        NdiNative.sendAudio(handle, pcm, samples, channels, sampleRate)
    }

    fun connections(): Int {
        if (closed || handle == 0L) return 0
        return NdiNative.sendConnections(handle)
    }

    fun close() {
        if (closed) return
        closed = true
        if (handle != 0L) NdiNative.sendDestroy(handle)
    }
}
