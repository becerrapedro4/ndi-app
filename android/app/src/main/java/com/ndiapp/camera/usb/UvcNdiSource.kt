package com.ndiapp.camera.usb

import android.content.Context
import com.ndiapp.camera.ndi.NdiNative
import com.ndiapp.camera.ndi.NdiSender

/** Cámara UVC capturada por USB y publicada como fuente NDI. */
class UvcNdiSource(private val context: Context) {

    var onStatus: ((String) -> Unit)? = null

    private var sender: NdiSender? = null
    private var streamer: UvcStreamer? = null

    val isRunning: Boolean
        get() = streamer != null

    fun start(camera: UvcCamera, name: String): Boolean {
        stop()
        if (!NdiNative.init()) {
            onStatus?.invoke("El runtime de NDI no está disponible")
            return false
        }
        val created = NdiSender(name)
        if (!created.isValid) {
            onStatus?.invoke("No se pudo crear la fuente NDI")
            return false
        }
        val capture = UvcStreamer(
            context = context,
            camera = camera,
            onFrame = { rgba, width, height, fps -> created.sendVideo(rgba, width, height, fps) },
            onStatus = { message -> onStatus?.invoke(message) }
        )
        if (!capture.start()) {
            created.close()
            return false
        }
        sender = created
        streamer = capture
        return true
    }

    fun stop() {
        streamer?.stop()
        streamer = null
        sender?.close()
        sender = null
    }
}
