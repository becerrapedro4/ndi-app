package com.ndiapp.camera.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.display.DisplayManager
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
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

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

        /** La medición del punto tocado vuelve sola al modo continuo. */
        private const val FOCUS_REGION_TIMEOUT_MS = 4_000L

        /** Reintentos de apertura tras una desconexión. */
        private const val MAX_REOPEN_ATTEMPTS = 4
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

    /** Fps reales medidos con las marcas de tiempo del sensor. */
    @Volatile
    var measuredFps: Float = 0f
        private set

    /** El equipo tiene estabilización óptica (OIS). */
    var opticalStabilizationAvailable: Boolean = false
        private set

    /** El equipo tiene estabilización de vídeo por software. */
    var videoStabilizationAvailable: Boolean = false
        private set

    /** Tamaño real de la vista previa (también el del vídeo local). */
    var previewSize: Size? = null
        private set

    /**
     * Relación del cuadro tal como se ve en vertical (alto / ancho). El sensor
     * entrega apaisado y el sistema gira la superficie, así que el visor no puede
     * suponer 16:9 o 4:3: usa el tamaño que la lente entrega de verdad.
     */
    var previewAspect: Float = 0f
        private set

    /**
     * Grados que hay que girar el cuadro del sensor para que salga derecho con la
     * pantalla como está ahora. La actividad no se recrea al girar, así que se
     * recalcula con el aviso del DisplayManager y no sólo al abrir la cámara.
     */
    @Volatile
    var frameRotation: Int = 0
        private set

    val isRecording: Boolean
        get() = recorder != null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val callbackExecutor = Executor { command -> mainHandler.post(command) }
    private val frameThread = HandlerThread("camera2-lens").apply { start() }
    private val frameHandler = Handler(frameThread.looper)
    private val displayManager by lazy {
        context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            updateFrameRotation()
        }
    }

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewView: SurfaceView? = null
    private var previewSurface: Surface? = null

    private var logicalId: String = ""
    private var physicalId: String? = null
    private var manager: CameraManager? = null
    private var targetFps: Range<Int> = Range(30, 30)
    private var fpsRanges: List<Range<Int>> = emptyList()
    private var pendingAnalysisSize: Size? = null

    /** Periodo de cuadro real: ninguna exposición puede durar más que un cuadro. */
    private val framePeriodNs: Long
        get() = 1_000_000_000L / targetFps.upper.coerceAtLeast(1)
    private var sensorOrientation: Int = 90
    private var frontFacing = false
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
    private var reopenAttempts = 0

    // Exposición y estabilización: lo último medido por el AE se usa como punto
    // de partida al pasar a manual, así el cuadro no salta.
    private var aeManual = false
    private var lastAeIso = 0
    private var lastAeShutterNs = 0L
    private var stabilization = 0
    private var antibandingMode = CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO
    private var fpsWindowStart = 0L
    private var fpsFrames = 0

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
            val configured = session
            session = null
            runCatching { configured?.stopRepeating() }
            runCatching { configured?.close() }
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
        // Se pide el rango de fps que el sensor admite de verdad: con un rango
        // 30-60 el AE puede caer a 30 en poca luz y NDI seguiría anunciando 60.
        targetFps = chooseFpsRange(fps)
        previewView = preview
        preview.holder.addCallback(holderCallback)
        if (preview.holder.surface?.isValid == true) {
            previewSurface = preview.holder.surface
        }

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        this.manager = manager
        val characteristicsId = physicalId ?: cameraId
        var characteristics = runCatching {
            manager.getCameraCharacteristics(characteristicsId)
        }.getOrNull()
        if (characteristics == null && physicalId != null) {
            characteristics = runCatching { manager.getCameraCharacteristics(cameraId) }.getOrNull()
        }
        readRanges(characteristics)
        // Para broadcast se prefiere OIS (no recorta el cuadro); si no hay, se
        // usa la estabilización de vídeo y, si tampoco, queda apagada.
        stabilization = when {
            opticalStabilizationAvailable -> 2
            videoStabilizationAvailable -> 1
            else -> 0
        }
        updateFrameRotation()
        runCatching {
            displayManager.unregisterDisplayListener(displayListener)
            displayManager.registerDisplayListener(displayListener, mainHandler)
        }
        val map = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        pendingAnalysisSize = chooseSize(map?.getOutputSizes(ImageFormat.YUV_420_888), height, fullSensor)
        previewSize = chooseSize(map?.getOutputSizes(SurfaceHolder::class.java), height, fullSensor)
            ?: pendingAnalysisSize
        previewSize?.let { size ->
            previewAspect = if (sensorOrientation % 180 == 90) {
                size.height.toFloat() / size.width.toFloat()
            } else {
                size.width.toFloat() / size.height.toFloat()
            }
        }

        runCatching {
            manager.registerAvailabilityCallback(availabilityCallback, mainHandler)
        }.onFailure { Log.w(TAG, "Sin avisos de disponibilidad: ${it.message}") }
        openDevice()
    }

    /** Abre la cámara guardada; si falla, se reintenta con espera creciente. */
    private fun openDevice() {
        val manager = manager ?: return
        val id = logicalId
        runCatching { manager.openCamera(id, deviceCallback, mainHandler) }
            .onFailure {
                Log.e(TAG, "No se pudo abrir $id", it)
                onError?.invoke("No se pudo abrir la cámara: ${it.message}")
                scheduleReopen()
            }
    }

    private fun scheduleReopen() {
        if (closed) return
        if (reopenAttempts >= MAX_REOPEN_ATTEMPTS) {
            onError?.invoke("La cámara no vuelve a responder; ciérrala y vuelve a abrirla")
            return
        }
        reopenAttempts++
        val waitMs = 800L * (1 shl (reopenAttempts - 1))
        mainHandler.removeCallbacks(reopenRunnable)
        mainHandler.postDelayed(reopenRunnable, waitMs)
    }

    private val reopenRunnable = Runnable {
        if (closed || device != null) return@Runnable
        openDevice()
    }

    /** Otra app soltó la cámara: se vuelve a abrir en cuanto esté disponible. */
    private val availabilityCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            if (closed || cameraId != logicalId || device != null) return
            mainHandler.removeCallbacks(reopenRunnable)
            reopenAttempts = 0
            openDevice()
        }
    }

    private val deviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            if (closed) {
                camera.close()
                return
            }
            device = camera
            reopenAttempts = 0
            startSessionIfReady()
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            device = null
            onError?.invoke("La cámara se desconectó; reintentando")
            scheduleReopen()
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            device = null
            onError?.invoke("La cámara falló (error $error); reintentando")
            scheduleReopen()
        }
    }

    private fun startSessionIfReady() {
        val camera = device ?: return
        if (session != null) return
        val surface = previewSurface
        val analysisSize = pendingAnalysisSize ?: Size(1280, 720)
        if (reader == null) {
            val created = ImageReader.newInstance(
                analysisSize.width, analysisSize.height, ImageFormat.YUV_420_888, 3
            )
            reader = created
            created.setOnImageAvailableListener({ source ->
                if (closed) return@setOnImageAvailableListener
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
        val previewConfig = surface?.let { OutputConfiguration(it) }
        val analysisConfig = OutputConfiguration(analysis.surface)
        val recorderSurface = recorderSurface()
        val videoConfig = recorderSurface?.let { OutputConfiguration(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && physicalId != null) {
            runCatching {
                previewConfig?.setPhysicalCameraId(physicalId)
                analysisConfig.setPhysicalCameraId(physicalId)
                videoConfig?.setPhysicalCameraId(physicalId)
            }.onFailure { Log.w(TAG, "Salida física no admitida: ${it.message}") }
        }
        previewConfig?.let { outputs += it }
        outputs += analysisConfig
        videoConfig?.let { outputs += it }

        runCatching {
            camera.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputs,
                    callbackExecutor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(configured: CameraCaptureSession) {
                            if (closed) {
                                configured.close()
                                return
                            }
                            session = configured
                            runCatching {
                                configured.setRepeatingRequest(
                                    buildRequest(camera), captureCallback, mainHandler
                                )
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
        // TEMPLATE_RECORD es la plantilla de vídeo: el ISP afina el enfoque y la
        // exposición para movimiento continuo, no para una foto previa.
        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        reader?.surface?.let { builder.addTarget(it) }
        previewSurface?.let { builder.addTarget(it) }
        recorderSurface()?.let { builder.addTarget(it) }
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, targetFps)
        builder.set(CaptureRequest.CONTROL_AWB_MODE, awbMode)
        // Antibanding: en 50 Hz evita el parpadeo de las luces LED y las pantallas.
        runCatching {
            builder.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, antibandingMode)
        }.onFailure { Log.w(TAG, "Antibanding no admitido: ${it.message}") }
        // Estabilización solo si el equipo la ofrece: pedirla a ciegas recorta el
        // cuadro y suma latencia. Se prefiere OIS para broadcast.
        runCatching {
            if (stabilization == 2 && opticalStabilizationAvailable) {
                builder.set(
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                )
                builder.set(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                )
            } else if (stabilization == 1 && videoStabilizationAvailable) {
                builder.set(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                )
            } else {
                builder.set(
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
                )
                builder.set(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                )
            }
        }.onFailure { Log.w(TAG, "Estabilización no admitida: ${it.message}") }
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
        if (aeManual && iso != null && shutter != null) {
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
        runCatching {
            configured.setRepeatingRequest(buildRequest(camera), captureCallback, mainHandler)
        }.onFailure { Log.w(TAG, "No se pudo actualizar la petición: ${it.message}") }
    }

    /** Últimos valores reales del AE y fps efectivos, leídos de cada resultado. */
    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { lastAeIso = it }
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastAeShutterNs = it }
            val stamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
            if (fpsWindowStart == 0L) {
                fpsWindowStart = stamp
                fpsFrames = 0
                return
            }
            fpsFrames++
            val elapsed = stamp - fpsWindowStart
            if (elapsed >= 1_000_000_000L) {
                measuredFps = fpsFrames * 1_000_000_000f / elapsed
                fpsWindowStart = stamp
                fpsFrames = 0
            }
        }
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
        // El tope es el periodo de cuadro real: a 60 fps una exposición de 1/30
        // no cabe y la cámara bajaría los fps sin avisar.
        val maxShutter = if (exposureTimeRange.last > 0L) {
            minOf(exposureTimeRange.last, framePeriodNs)
        } else {
            framePeriodNs
        }
        manualIso = iso.coerceIn(isoRange.first, isoRange.last)
        manualShutterNs = shutterNs.coerceIn(1_000L, maxShutter)
        aeManual = true
        repeat()
    }

    fun setManualExposure(manual: Boolean) {
        if (!manual) {
            aeManual = false
            manualIso = null
            manualShutterNs = null
            repeat()
            return
        }
        // Sin valores elegidos se parte de lo que medía el AE: el cuadro no
        // salta al bloquear la exposición.
        aeManual = true
        if (manualIso == null) {
            manualIso = (lastAeIso.takeIf { it > 0 } ?: isoRange.first)
                .coerceIn(isoRange.first, isoRange.last)
        }
        if (manualShutterNs == null) {
            manualShutterNs = (lastAeShutterNs.takeIf { it > 0 } ?: (framePeriodNs / 2))
                .coerceIn(1_000L, framePeriodNs)
        }
        repeat()
    }

    /** Estabilización pedida: 0 = ninguna, 1 = vídeo, 2 = óptica (OIS). */
    fun setStabilization(mode: Int) {
        stabilization = when {
            mode == 2 && opticalStabilizationAvailable -> 2
            mode >= 1 && videoStabilizationAvailable -> 1
            else -> 0
        }
        repeat()
    }

    /** Antibanding: AUTO, 50 Hz o 60 Hz (CONTROL_AE_ANTIBANDING_MODE_*). */
    fun setAntibanding(mode: Int) {
        antibandingMode = mode
        repeat()
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
        val x = nx.coerceIn(0f, 1f)
        val y = ny.coerceIn(0f, 1f)
        // El visor muestra el cuadro girado: se deshace el giro antes de buscar
        // el punto en el sensor (en vertical, tocando arriba se enfoca a la
        // izquierda si no se corrige).
        val sensorX: Float
        val sensorY: Float
        when (((frameRotation % 360) + 360) % 360) {
            90 -> {
                sensorX = y
                sensorY = 1f - x
            }
            180 -> {
                sensorX = 1f - x
                sensorY = 1f - y
            }
            270 -> {
                sensorX = 1f - y
                sensorY = x
            }
            else -> {
                sensorX = x
                sensorY = y
            }
        }
        // Con zoom digital solo se ve el recorte central: el punto se lleva a
        // las coordenadas reales del sensor.
        val ratio = zoomRatio.coerceAtLeast(1f)
        val cropWidth = active.width() / ratio
        val cropHeight = active.height() / ratio
        val cropLeft = active.left + (active.width() - cropWidth) / 2f
        val cropTop = active.top + (active.height() - cropHeight) / 2f
        val cx = (cropLeft + sensorX * cropWidth).toInt()
        val cy = (cropTop + sensorY * cropHeight).toInt()
        val half = (minOf(cropWidth, cropHeight).toInt() / FOCUS_RING_DIVISOR)
            .coerceAtLeast(1)
        val left = (cx - half).coerceIn(active.left, active.right)
        val top = (cy - half).coerceIn(active.top, active.bottom)
        val right = (cx + half).coerceIn(active.left, active.right)
        val bottom = (cy + half).coerceIn(active.top, active.bottom)
        focusRegion = Rect(left, top, right, bottom)
        repeat()
        // La medición no se queda pegada hasta cerrar la cámara.
        mainHandler.removeCallbacks(clearFocusRegion)
        mainHandler.postDelayed(clearFocusRegion, FOCUS_REGION_TIMEOUT_MS)
    }

    /** Vuelve la medición al cuadro completo tras unos segundos. */
    private val clearFocusRegion = Runnable {
        if (focusRegion != null) {
            focusRegion = null
            repeat()
        }
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
    private fun videoRotation(): Int = frameRotation

    /** Vuelve a leer la orientación de la pantalla (al girar o al abrir). */
    fun refreshRotation() {
        updateFrameRotation()
    }

    private fun updateFrameRotation() {
        val degrees = displayDegrees()
        // Delantera y trasera giran al revés: la trasera resta el giro de la
        // pantalla y la delantera lo suma, como en la cámara del sistema.
        frameRotation = if (frontFacing) {
            (sensorOrientation + degrees) % 360
        } else {
            (sensorOrientation - degrees + 360) % 360
        }
    }

    /** Rotación de la pantalla en grados (0, 90, 180 o 270). */
    private fun displayDegrees(): Int {
        val displayRotation = runCatching {
            val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            manager.defaultDisplay.rotation
        }.getOrDefault(Surface.ROTATION_0)
        return when (displayRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    fun close() {
        closed = true
        runCatching { displayManager.unregisterDisplayListener(displayListener) }
        runCatching { manager?.unregisterAvailabilityCallback(availabilityCallback) }
        mainHandler.removeCallbacks(reopenRunnable)
        mainHandler.removeCallbacks(clearFocusRegion)
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        runCatching { device?.close() }
        val toClose = reader
        reader = null
        toClose?.setOnImageAvailableListener(null, null)
        toClose?.let { r -> frameHandler.post { runCatching { r.close() } } }
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
        // Sólo se apaga el hilo de los cuadros; las devoluciones de la cámara van
        // al hilo principal y sobreviven al cierre de la sesión.
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
        frontFacing = characteristics.get(CameraCharacteristics.LENS_FACING) ==
            CameraCharacteristics.LENS_FACING_FRONT
        activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val max = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        minZoom = 1f
        maxZoom = max.coerceAtLeast(1f)
        fpsRanges = characteristics
            .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.toList().orEmpty()
        val videoModes = characteristics
            .get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
            ?.toList().orEmpty()
        videoStabilizationAvailable = videoModes
            .contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
        val opticalModes = characteristics
            .get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            ?.toList().orEmpty()
        opticalStabilizationAvailable = opticalModes
            .contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
    }

    /**
     * Rango de fps que el sensor admite para el valor pedido. Se prefiere un
     * rango fijo (30-30, 60-60): con 30-60 el AE puede caer a 30 en poca luz.
     */
    private fun chooseFpsRange(fps: Int): Range<Int> {
        val wanted = fps.coerceAtLeast(1)
        if (fpsRanges.isEmpty()) return Range(30, 30)
        fpsRanges.filter { it.lower == wanted && it.upper == wanted }
            .maxByOrNull { it.upper }?.let { return it }
        fpsRanges.filter { it.upper == wanted }
            .maxByOrNull { it.lower }?.let { return it }
        fpsRanges.filter { wanted in it.lower..it.upper }
            .minByOrNull { abs(it.upper - wanted) }?.let { return it }
        return fpsRanges.minByOrNull { abs(it.upper - wanted) }
            ?: fpsRanges.maxByOrNull { it.upper }!!
    }

    /** Tamaño más cercano al alto pedido, respetando la relación 4:3 o 16:9. */
    private fun chooseSize(sizes: Array<Size>?, height: Int, fullSensor: Boolean): Size? {
        if (sizes == null || sizes.isEmpty()) return null
        // Se admite cualquier alto que la cámara ofrezca: 4K (2160), 1440, 1080
        // o 720, siempre dentro de la relación elegida.
        val wanted = height.coerceAtLeast(240)
        val ratio = if (fullSensor) 4f / 3f else 16f / 9f
        val matching = sizes.filter { size ->
            val value = size.width.toFloat() / size.height.toFloat()
            abs(value - ratio) < 0.06f
        }
        val pool = matching.ifEmpty { sizes.toList() }
        return pool.filter { it.height >= wanted / 2 }
            .minByOrNull { abs(it.height - wanted) }
            ?: pool.maxByOrNull { it.height }
    }
}
