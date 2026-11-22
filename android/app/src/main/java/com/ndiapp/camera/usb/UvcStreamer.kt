package com.ndiapp.camera.usb

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import com.serenegiant.usb.IFrameCallback
import com.serenegiant.usb.Size
import com.serenegiant.usb.USBException
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.UVCCamera
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Captura de una cámara UVC a través de libuvc (UVCAndroid).
 *
 * libuvc habla UVC en C, con libusb: maneja tanto endpoints isocronos (lo habitual
 * en las capturadoras y webcams) como los que sólo tienen bulk, y convierte MJPEG,
 * YUY2 y compañía a RGBA en la parte nativa. La implementación anterior encolaba
 * lecturas isocronas con `UsbRequest` desde Java y el kernel las rechazaba en
 * estas cámaras, así que nunca llegaba imagen.
 *
 * Abrir y cerrar están serializados en un solo hilo, y el hilo principal nunca
 * espera al dispositivo.
 */
class UvcStreamer(
    private val context: Context,
    private val camera: UvcCamera,
    private val preferredHeight: Int,
    private val onFrame: (rgba: ByteArray, width: Int, height: Int, fps: Int) -> Unit,
    private val onStatus: (String) -> Unit
) {

    companion object {
        private const val TAG = "UvcStreamer"
        private const val OPEN_TIMEOUT_MS = 15_000L
        private const val CLOSE_TIMEOUT_MS = 3_000L

        /**
         * Un único hilo para abrir y cerrar todas las cámaras: al cambiar de
         * resolución, la nueva espera a que la anterior suelte el dispositivo.
         */
        private val DEVICE_THREAD = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "uvc-device").apply { isDaemon = true }
        }
    }

    private var monitor: USBMonitor? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var opened: UVCCamera? = null
    private var previewSink: ImageReader? = null
    private var controlBlock: USBMonitor.UsbControlBlock? = null
    private val deviceLatch = CountDownLatch(1)
    private val openError = AtomicReference<Exception?>(null)

    @Volatile
    private var running = false

    @Volatile
    private var capturing = false

    private var reusable = ByteArray(0)

    val isRunning: Boolean
        get() = running

    private val listener = object : USBMonitor.OnDeviceConnectListener {
        override fun onAttach(device: UsbDevice) = Unit

        override fun onDetach(device: UsbDevice) = Unit

        override fun onDeviceOpen(
            device: UsbDevice,
            ctrlBlock: USBMonitor.UsbControlBlock,
            createNew: Boolean
        ) {
            controlBlock = ctrlBlock
            deviceLatch.countDown()
        }

        override fun onDeviceClose(
            device: UsbDevice,
            ctrlBlock: USBMonitor.UsbControlBlock
        ) = Unit

        override fun onCancel(device: UsbDevice) {
            openError.compareAndSet(null, IllegalStateException("No se concedió el permiso USB"))
            deviceLatch.countDown()
        }

        override fun onError(device: UsbDevice, e: USBException) {
            openError.compareAndSet(null, e)
            deviceLatch.countDown()
        }
    }

    /**
     * Empieza a abrir la cámara en segundo plano. Devuelve false sólo si la cámara
     * no está conectada; los fallos de apertura se informan por `onStatus`.
     */
    fun start(): Boolean {
        if (running) return true
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return fail("Este dispositivo no tiene USB host")
        val device = manager.deviceList.values
            .firstOrNull { it.deviceName == camera.deviceName }
            ?: return fail("La cámara no está conectada")
        running = true
        monitor = USBMonitor(context, listener, Handler(Looper.getMainLooper())).also {
            it.register()
        }
        DEVICE_THREAD.execute { openDevice(device) }
        return true
    }

    fun stop() {
        running = false
        capturing = false
        DEVICE_THREAD.execute { release() }
    }

    private fun openDevice(device: UsbDevice) {
        if (!running) return
        val current = monitor ?: return
        // Con el permiso ya concedido, la librería entrega el bloque de control al
        // instante; si no, muestra su diálogo y responde por el mismo callback.
        current.requestPermission(device)

        if (!deviceLatch.await(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            openError.compareAndSet(null, IllegalStateException("La cámara no abrió a tiempo"))
        }
        val failure = openError.get()
        if (failure != null) {
            Log.w(TAG, "No se pudo abrir la cámara", failure)
            onStatus("No se pudo abrir la cámara: ${failure.message}")
            release()
            return
        }
        val block = controlBlock
        if (block == null) {
            onStatus("La cámara abrió sin bloque de control USB")
            release()
            return
        }

        val thread = HandlerThread("UVC-Camera").apply { start() }
        cameraThread = thread
        val handler = Handler(thread.looper)
        cameraHandler = handler
        val cameraLatch = CountDownLatch(1)
        handler.post {
            try {
                openCamera(block)
            } catch (t: Throwable) {
                openError.compareAndSet(null, IllegalStateException(t.message ?: t.toString()))
            } finally {
                cameraLatch.countDown()
            }
        }
        if (!cameraLatch.await(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            openError.compareAndSet(null, IllegalStateException("El flujo no arrancó a tiempo"))
        }
        val startFailure = openError.get()
        if (startFailure != null) {
            Log.w(TAG, "No se pudo iniciar el flujo UVC", startFailure)
            onStatus("No se pudo iniciar el flujo UVC: ${startFailure.message}")
            release()
        }
    }

    /** Corre en el hilo de la cámara, que necesita un Looper. */
    private fun openCamera(block: USBMonitor.UsbControlBlock) {
        if (!running) return
        val uvc = UVCCamera(null)
        opened = uvc
        val result = uvc.open(block)
        if (result != 0) throw IllegalStateException("libuvc devolvió $result al abrir")

        val mode = chooseMode(uvc.supportedSizeList)
            ?: throw IllegalStateException("La cámara no informa ningún modo de vídeo")
        uvc.setPreviewSize(mode)

        // libuvc sólo arranca su hilo de captura cuando hay una superficie de
        // preview, aunque los cuadros lleguen por callback: se le da un ImageReader
        // y se van descartando las imágenes para que nunca se bloquee.
        val sink = ImageReader.newInstance(mode.width, mode.height, PixelFormat.RGBA_8888, 2)
        sink.setOnImageAvailableListener(
            { reader -> runCatching { reader?.acquireLatestImage()?.close() } },
            cameraHandler
        )
        previewSink = sink
        uvc.setPreviewDisplay(sink.surface)

        val width = mode.width
        val height = mode.height
        val fps = if (mode.fps > 0) mode.fps else 30
        uvc.setFrameCallback(object : IFrameCallback {
            override fun onFrame(frame: ByteBuffer?) {
                if (!capturing || frame == null) return
                val available = frame.remaining()
                val needed = width * height * 4
                if (available < needed) return
                val target = buffer(needed)
                val source = frame.duplicate()
                source.position(0)
                source.limit(needed)
                source.get(target, 0, needed)
                onFrame(target, width, height, fps)
            }
        }, UVCCamera.PIXEL_FORMAT_RGBX)

        capturing = true
        uvc.startPreview()
        val format = if (mode.type == UVCCamera.UVC_VS_FRAME_MJPEG) "MJPEG" else "YUY2"
        onStatus("$format ${width}×$height @ $fps fps · libuvc")
    }

    /** Elige el modo de la altura pedida, prefiriendo MJPEG como la referencia. */
    private fun chooseMode(sizes: List<Size>?): Size? {
        if (sizes.isNullOrEmpty()) return null
        return closestHeight(sizes, true) ?: closestHeight(sizes, false) ?: sizes.first()
    }

    private fun closestHeight(sizes: List<Size>, mjpegOnly: Boolean): Size? {
        val wanted = if (preferredHeight > 0) preferredHeight else 720
        var best: Size? = null
        var bestDistance = Int.MAX_VALUE
        var bestAspect = Int.MAX_VALUE
        for (size in sizes) {
            if (mjpegOnly && size.type != UVCCamera.UVC_VS_FRAME_MJPEG) continue
            val distance = kotlin.math.abs(size.height - wanted)
            val aspect = kotlin.math.abs(size.width * 9 - size.height * 16) /
                kotlin.math.max(1, size.height)
            val current = best
            val better = current == null || distance < bestDistance ||
                (distance == bestDistance && aspect < bestAspect) ||
                (distance == bestDistance && aspect == bestAspect && size.width > current.width)
            if (better) {
                best = size
                bestDistance = distance
                bestAspect = aspect
            }
        }
        return best
    }

    /** Un solo buffer: el consumidor copia el cuadro dentro de la llamada. */
    private fun buffer(size: Int): ByteArray {
        val current = reusable
        if (current.size != size) {
            val created = ByteArray(size)
            reusable = created
            return created
        }
        return current
    }

    private fun release() {
        capturing = false
        running = false
        val current = opened
        opened = null
        val handler = cameraHandler
        if (handler != null && current != null) {
            val closed = CountDownLatch(1)
            handler.post {
                runCatching { current.stopPreview() }
                runCatching { current.close() }
                runCatching { current.destroy() }
                closed.countDown()
            }
            runCatching { closed.await(CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        }
        previewSink?.let { runCatching { it.close() } }
        previewSink = null
        cameraThread?.let { runCatching { it.quitSafely() } }
        cameraThread = null
        cameraHandler = null
        monitor?.let { runCatching { it.destroy() } }
        monitor = null
        controlBlock = null
    }

    private fun fail(message: String): Boolean {
        Log.w(TAG, message)
        onStatus(message)
        return false
    }
}
