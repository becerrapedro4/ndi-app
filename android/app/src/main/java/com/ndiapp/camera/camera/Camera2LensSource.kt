package com.ndiapp.camera.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Pipeline Camera2 propio, el mismo enfoque que usa PhotonCamera: se abre la cámara
 * directamente con `CameraManager`, se mantiene una sesión con salidas propias (vista
 * previa, análisis YUV y grabación) y todos los ajustes se piden como opciones de la
 * petición de captura en lugar de pasar por CameraX.
 *
 * Permite además las lentes físicas que el sistema no expone por su cuenta: se abre la
 * cámara lógica y se piden las salidas enlazadas a la cámara física con
 * `OutputConfiguration.setPhysicalCameraId`.
 */
class Camera2LensSource(private val context: Context) {

    companion object {
        private const val TAG = "Camera2LensSource"
        private const val FOCUS_RING_DIVISOR = 5
    }

    var onFrame: ((Image) -> Unit)? = null
    var onStatus: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    /** Fin de la grabación: archivo grabado y si terminó bien. */
    var onRecordingFinished: ((File?, Boolean) -> Unit)? = null

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

    /** Tamaño real de la vista previa (también el del vídeo local). */
    var previewSize: Size? = null
        private set

    val isRecording: Boolean
        get() = recorder != null

    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val frameThread = HandlerThread("camera2-lens").apply { start() }
    private val frameHandler = Handler(frameThread.looper)

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewView: SurfaceView? = null
    private var previewSurface: Surface? = null

    private var logicalId: String = ""
    private var physicalId: String? = null
    private var targetFps: Range<Int> = Range(30, 30)
    private var pendingAnalysisSize: Size? = null
    private var sensorOrientation: Int = 90
    private var activeArray: Rect? = null

    private var zoomRatio = 1f
    private var evIndex = 0
    private var manualIso: Int? = null
    private var manualShutterNs: Long? = null
    private var manualFocus: Float? = null
    private var awbMode: Int = CaptureRequest.CONTROL_AWB_MODE_AUTO
    private var torch = false
    private var focusRegion: Rect? = null
    private var closed = false

    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var pendingRecorderStart = false

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
     * Abre [cameraId] (la cámara lógica) con las salidas enlazadas a [physicalId]
     * cuando la lente es física y oculta. La vista previa se dibuja en [preview].
     */
    fun open(
        cameraId: String,
        physicalId: String?,
        preview: SurfaceView,
        height: Int,
        fps: Int,
        fullSensor: Boolean
    ) {
        this.logicalId = cameraId
        this.physicalId = physicalId
        targetFps = if (fps >= 60) Range(30, 60) else Range(30, 30)
        previewView = preview
        preview.holder.addCallback(holderCallback)
        if (preview.holder.surface?.isValid == true) {
            previewSurface = preview.holder.surface
        }

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val characteristicsId = physicalId ?: cameraId
        var characteristics = runCatching {
            manager.getCameraCharacteristics(characteristicsId)
        }.getOrNull()
        if (characteristics == null && physicalId != null) {
            characteristics = runCatching { manager.getCameraCharacteristics(cameraId) }.getOrNull()
        }
        readRanges(characteristics)
        val map = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        pendingAnalysisSize = chooseSize(map?.getOutputSizes(ImageFormat.YUV_420_888), height, fullSensor)
        previewSize = chooseSize(map?.getOutputSizes(SurfaceHolder::class.java), height, fullSensor)
            ?: pendingAnalysisSize

        runCatching { manager.openCamera(logicalId, ioExecutor, deviceCallback) }
            .onFailure {
                Log.e(TAG, "No se pudo abrir $logicalId", it)
                onError?.invoke("No se pudo abrir la cámara: ${it.message}")
            }
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
        if (reader == null) {
            val created = ImageReader.newInstance(
                analysisSize.width, analysisSize.height, ImageFormat.YUV_420_888, 3
            )
            reader = created
            created.setOnImageAvailableListener({ source ->
                val image = runCatching { source.acquireLatestImage() }.getOrNull()
                    ?: return@setOnImageAvailableListener
                try {
                    onFrame?.invoke(image)
                } catch (t: Throwable) {
                    Log.w(TAG, "Frame descartado", t)
                } finally {
                    image.close()
                }
            }, frameHandler)
        }
        val analysis = reader ?: return

        val outputs = mutableListOf<OutputConfiguration>()
        val previewConfig = OutputConfiguration(surface)
        val analysisConfig = OutputConfiguration(analysis.surface)
        val recorderSurface = recorderSurface()
        val videoConfig = recorderSurface?.let { OutputConfiguration(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && physicalId != null) {
            runCatching {
                previewConfig.setPhysicalCameraId(physicalId)
                analysisConfig.setPhysicalCameraId(physicalId)
                videoConfig?.setPhysicalCameraId(physicalId)
            }.onFailure { Log.w(TAG, "Salida física no admitida: ${it.message}") }
        }
        outputs += previewConfig
        outputs += analysisConfig
        videoConfig?.let { outputs += it }

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
                            runCatching {
                                configured.setRepeatingRequest(buildRequest(camera), null, mainHandler)
                            }.onFailure {
                                Log.e(TAG, "No se pudo iniciar la repetición", it)
                                onError?.invoke("La lente no entrega imagen: ${it.message}")
                            }
                            if (pendingRecorderStart) {
                                pendingRecorderStart = false
                                runCatching { recorder?.start() }.onFailure { failure ->
                                    Log.e(TAG, "No se pudo grabar", failure)
                                    releaseRecorder(deleteFile = true, ok = false)
                                }
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

    private fun recorderSurface(): Surface? = recorder?.surface

    private fun restartSession() {
        val configured = session
        session = null
        runCatching { configured?.stopRepeating() }
        runCatching { configured?.close() }
        startSessionIfReady()
    }

    private fun buildRequest(camera: CameraDevice): CaptureRequest {
        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        reader?.surface?.let { builder.addTarget(it) }
        previewSurface?.let { builder.addTarget(it) }
        recorderSurface()?.let { builder.addTarget(it) }
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
            runCatching {
                builder.set(
                    CaptureRequest.CONTROL_ZOOM_RATIO,
                    zoomRatio.coerceIn(minZoom, maxZoom)
                )
            }
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
        // Medición en el punto tocado, como PhotonCamera.
        val region = focusRegion
        if (region != null) {
            val metering = arrayOf(MeteringRectangle(region, 1000))
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, metering)
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, metering)
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

    /** Enfoca y mide la luz en el punto tocado del visor (coordenadas 0..1). */
    fun focusAt(nx: Float, ny: Float) {
        val active = activeArray ?: return
        val width = active.width()
        val height = active.height()
        val cx = active.left + (nx.coerceIn(0f, 1f) * width).toInt()
        val cy = active.top + (ny.coerceIn(0f, 1f) * height).toInt()
        val half = (minOf(width, height) / FOCUS_RING_DIVISOR).coerceAtLeast(1)
        val left = (cx - half).coerceIn(active.left, active.right)
        val top = (cy - half).coerceIn(active.top, active.bottom)
        val right = (cx + half).coerceIn(active.left, active.right)
        val bottom = (cy + half).coerceIn(active.top, active.bottom)
        focusRegion = Rect(left, top, right, bottom)
        repeat()
    }

    // ------------------------------------------------------------ grabación --

    /**
     * Graba la sesión en [file]. La salida del MediaRecorder se añade como una salida
     * más de la sesión Camera2, así que la vista previa y el flujo NDI no se cortan.
     */
    fun startRecording(file: File, withAudio: Boolean, fps: Int): Boolean {
        if (recorder != null) return false
        val size = previewSize ?: pendingAnalysisSize ?: Size(1280, 720)
        val created = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        val configured = runCatching {
            created.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            if (withAudio) created.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            created.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            created.setOutputFile(file.absolutePath)
            created.setVideoEncodingBitRate(
                (size.width * size.height * 5).coerceIn(4_000_000, 24_000_000)
            )
            created.setVideoFrameRate(fps.coerceAtLeast(24))
            created.setVideoSize(size.width, size.height)
            created.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            created.setOrientationHint(videoRotation())
            if (withAudio) {
                created.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                created.setAudioEncodingBitRate(128_000)
                created.setAudioChannels(1)
                created.setAudioSamplingRate(48_000)
            }
            created.prepare()
        }
        if (configured.isFailure) {
            Log.w(TAG, "MediaRecorder no aceptó la configuración", configured.exceptionOrNull())
            runCatching { created.release() }
            file.delete()
            onError?.invoke("Este dispositivo no admite grabación local")
            return false
        }
        recorder = created
        recordingFile = file
        pendingRecorderStart = true
        // La sesión necesita la salida del grabador: se rehace con las tres salidas.
        restartSession()
        return true
    }

    fun stopRecording() {
        if (recorder == null) return
        releaseRecorder(deleteFile = false, ok = true)
        restartSession()
    }

    private fun releaseRecorder(deleteFile: Boolean, ok: Boolean) {
        val active = recorder ?: return
        val file = recordingFile
        recorder = null
        recordingFile = null
        pendingRecorderStart = false
        var finished = ok
        if (ok) {
            finished = runCatching { active.stop() }.onFailure {
                Log.w(TAG, "No se pudo cerrar la grabación", it)
            }.isSuccess
        }
        runCatching { active.reset() }
        runCatching { active.release() }
        if (!finished && (deleteFile || file != null)) file?.delete()
        onRecordingFinished?.invoke(file, finished)
    }

    /** Grados que hay que girar el cuadro del sensor para que salga derecho. */
    private fun videoRotation(): Int {
        val displayRotation = runCatching {
            val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            manager.defaultDisplay.rotation
        }.getOrDefault(Surface.ROTATION_0)
        val degrees = when (displayRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return (sensorOrientation - degrees + 360) % 360
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
        focusRegion = null
        recorder?.let { active ->
            runCatching { active.reset() }
            runCatching { active.release() }
        }
        recorder = null
        ioExecutor.shutdown()
        runCatching { frameThread.quitSafely() }
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
        sensorOrientation =
            characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val max = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        minZoom = 1f
        maxZoom = max.coerceAtLeast(1f)
    }

    /** Tamaño más cercano al alto pedido, respetando la relación 4:3 o 16:9. */
    private fun chooseSize(sizes: Array<Size>?, height: Int, fullSensor: Boolean): Size? {
        if (sizes == null || sizes.isEmpty()) return null
        val wanted = if (height >= 1080) 1080 else 720
        val ratio = if (fullSensor) 4f / 3f else 16f / 9f
        val matching = sizes.filter { size ->
            val value = size.width.toFloat() / size.height.toFloat()
            kotlin.math.abs(value - ratio) < 0.06f
        }
        val pool = matching.ifEmpty { sizes.toList() }
        val smaller = pool.filter { it.height <= wanted }
        return (smaller.ifEmpty { pool }).minByOrNull {
            kotlin.math.abs(it.height - wanted)
        }
    }
}
