package com.ndiapp.camera.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.annotation.RequiresApi
import android.media.Image
import androidx.camera.core.ImageProxy
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Fuente de cámara propia sobre Camera2 para las lentes físicas que el sistema no
 * deja abrir por su cuenta (el ultra gran angular de muchos Xiaomi, por ejemplo).
 *
 * Se abre la cámara lógica y se piden las salidas enlazadas a la cámara física con
 * `OutputConfiguration.setPhysicalCameraId`, igual que hace PhotonCamera. Los frames
 * llegan por un [ImageReader] YUV_420_888 y la vista previa por el [SurfaceView].
 */
class Camera2LensSource(private val context: Context) {

    companion object {
        private const val TAG = "Camera2LensSource"
    }

    var onFrame: ((Image) -> Unit)? = null
    var onStatus: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    var minZoom: Float = 1f
        private set
    var maxZoom: Float = 1f
        private set

    var isoRange: IntRange = 100..1600
        private set
    var exposureTimeRange: LongRange = 0L..0L
        private set
    var exposureRange: IntRange = 0..0
        private set
    var exposureStep: Float = 1f / 3f
        private set
    var minFocusDistance: Float = 0f
        private set
    var hasFlash: Boolean = false
        private set

    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewView: SurfaceView? = null
    private var previewSurface: Surface? = null

    private var logicalId: String = ""
    private var physicalId: String? = null
    private var targetFps: Range<Int> = Range(30, 30)
    private var pendingPreviewSize: Size? = null
    private var pendingAnalysisSize: Size? = null

    private var zoomRatio = 1f
    private var evIndex = 0
    private var manualIso: Int? = null
    private var manualShutterNs: Long? = null
    private var manualFocus: Float? = null
    private var awbMode: Int = CaptureRequest.CONTROL_AWB_MODE_AUTO
    private var torch = false
    private var closed = false

    private val holderCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            val surface = holder.surface
            if (!surface.isValid) return
            previewSurface = surface
            startSessionIfReady()
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            previewSurface = null
        }
    }

    /**
     * Abre [logicalId] con las salidas enlazadas a [physicalId] y arranca la vista
     * previa en [preview].
     */
    fun open(
        logicalId: String,
        physicalId: String,
        preview: SurfaceView,
        height: Int,
        fps: Int
    ) {
        this.logicalId = logicalId
        this.physicalId = physicalId
        targetFps = if (fps >= 60) Range(30, 60) else Range(30, 30)
        previewView = preview
        preview.holder.addCallback(holderCallback)
        if (preview.holder.surface?.isValid == true) {
            previewSurface = preview.holder.surface
        }

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        runCatching {
            val physical = manager.getCameraCharacteristics(physicalId)
            readRanges(physical)
            val map = physical.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            pendingPreviewSize = chooseSize(
                map?.getOutputSizes(SurfaceHolder::class.java), height
            )
            pendingAnalysisSize = chooseSize(
                map?.getOutputSizes(ImageFormat.YUV_420_888), height
            )
        }.onFailure { Log.w(TAG, "Sin datos de la lente física: ${it.message}") }

        if (pendingAnalysisSize == null) {
            val logical = runCatching { manager.getCameraCharacteristics(logicalId) }.getOrNull()
            readRanges(logical)
            val map = logical?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            pendingAnalysisSize = chooseSize(
                map?.getOutputSizes(ImageFormat.YUV_420_888), height
            )
            pendingPreviewSize = chooseSize(
                map?.getOutputSizes(SurfaceHolder::class.java), height
            )
        }

        manager.openCamera(logicalId, deviceCallback, ioExecutor)
    }

    private val deviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            if (closed) {
                camera.close()
                return
            }
            device = camera
            startSessionIfReady()
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            device = null
            onError?.invoke("La cámara se desconectó")
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            device = null
            onError?.invoke("No se pudo abrir la lente (error $error)")
        }
    }

    private fun startSessionIfReady() {
        val camera = device ?: return
        val surface = previewSurface ?: return
        if (session != null) return
        val analysisSize = pendingAnalysisSize ?: Size(1280, 720)
        val analysis = ImageReader.newInstance(
            analysisSize.width, analysisSize.height, ImageFormat.YUV_420_888, 3
        )
        reader = analysis
        analysis.setOnImageAvailableListener({ source ->
            val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                onFrame?.invoke(image)
            } catch (t: Throwable) {
                Log.w(TAG, "Frame descartado", t)
            } finally {
                image.close()
            }
        }, ioExecutor)

        val outputs = mutableListOf<OutputConfiguration>()
        val previewConfig = OutputConfiguration(surface)
        val analysisConfig = OutputConfiguration(analysis.surface)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && physicalId != null) {
            runCatching {
                previewConfig.physicalCameraId = physicalId
                analysisConfig.physicalCameraId = physicalId
            }.onFailure { Log.w(TAG, "Salida física no admitida: ${it.message}") }
        }
        outputs += previewConfig
        outputs += analysisConfig

        runCatching {
            camera.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputs,
                    ioExecutor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(configured: CameraCaptureSession) {
                            if (closed) {
                                configured.close()
                                return
                            }
                            session = configured
                            runCatching { configured.setRepeatingRequest(buildRequest(camera), null, mainHandler) }
                                .onFailure {
                                    Log.e(TAG, "No se pudo iniciar la repetición", it)
                                    onError?.invoke("La lente no entrega imagen: ${it.message}")
                                }
                        }

                        override fun onConfigureFailed(failed: CameraCaptureSession) {
                            Log.e(TAG, "Sesión rechazada")
                            onError?.invoke("Esta lente no admite vista previa en este equipo")
                        }
                    }
                )
            )
        }.onFailure {
            Log.e(TAG, "createCaptureSession falló", it)
            onError?.invoke("No se pudo abrir la lente: ${it.message}")
        }
    }

    private fun buildRequest(camera: CameraDevice): CaptureRequest {
        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        reader?.surface?.let { builder.addTarget(it) }
        previewSurface?.let { builder.addTarget(it) }
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, targetFps)
        builder.set(CaptureRequest.CONTROL_AWB_MODE, awbMode)
        builder.set(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
        )
        if (hasFlash) {
            builder.set(
                CaptureRequest.FLASH_MODE,
                if (torch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio.coerceIn(minZoom, maxZoom)) }
        }
        val iso = manualIso
        val shutter = manualShutterNs
        if (iso != null && shutter != null) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutter)
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evIndex)
        }
        val focus = manualFocus
        if (focus != null && minFocusDistance > 0f) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focus.coerceIn(0f, minFocusDistance))
        } else {
            builder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            )
        }
        return builder.build()
    }

    private fun repeat() {
        val camera = device ?: return
        val configured = session ?: return
        runCatching { configured.setRepeatingRequest(buildRequest(camera), null, mainHandler) }
            .onFailure { Log.w(TAG, "No se pudo actualizar la petición: ${it.message}") }
    }

    // ------------------------------------------------------------- controles --

    fun setZoom(ratio: Float) {
        zoomRatio = ratio.coerceIn(minZoom, maxZoom)
        repeat()
    }

    fun setExposureCompensation(index: Int) {
        evIndex = index
        repeat()
    }

    fun setManualExposureValues(iso: Int, shutterNs: Long) {
        val maxShutter = if (exposureTimeRange.last > 0L) {
            minOf(exposureTimeRange.last, 1_000_000_000L / 30)
        } else {
            1_000_000_000L / 30
        }
        manualIso = iso.coerceIn(isoRange.first, isoRange.last)
        manualShutterNs = shutterNs.coerceIn(1_000L, maxShutter)
        repeat()
    }

    fun setManualExposure(manual: Boolean) {
        if (!manual) {
            manualIso = null
            manualShutterNs = null
            repeat()
        }
    }

    fun setManualFocus(diopters: Float?) {
        manualFocus = diopters
        repeat()
    }

    fun setWhiteBalance(mode: Int) {
        awbMode = mode
        repeat()
    }

    fun setTorch(on: Boolean) {
        if (!hasFlash) return
        torch = on
        repeat()
    }

    fun close() {
        closed = true
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { reader?.close() }
        previewView?.holder?.removeCallback(holderCallback)
        session = null
        device = null
        reader = null
        previewView = null
        previewSurface = null
        ioExecutor.shutdown()
    }

    // ----------------------------------------------------------------- utils --

    private fun readRanges(characteristics: CameraCharacteristics?) {
        if (characteristics == null) return
        characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let {
            isoRange = it.lower..minOf(it.upper, 6400)
        }
        characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let {
            exposureTimeRange = it.lower..it.upper
        }
        characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.let {
            exposureRange = it.lower..it.upper
        }
        characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.let {
            exposureStep = it.toFloat()
        }
        minFocusDistance =
            characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        val max = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        minZoom = 1f
        maxZoom = max.coerceAtLeast(1f)
    }

    /** Tamaño más cercano a 1080p entre los que ofrece la lente. */
    private fun chooseSize(sizes: Array<Size>?, height: Int): Size? {
        val wanted = if (height >= 1080) 1080 else 720
        val candidates = sizes?.filter { it.height <= if (wanted >= 1080) 1080 else 720 } ?: return null
        return candidates.minByOrNull { kotlin.math.abs(it.height - wanted) }
            ?: sizes?.minByOrNull { kotlin.math.abs(it.height - wanted) }
    }
}
