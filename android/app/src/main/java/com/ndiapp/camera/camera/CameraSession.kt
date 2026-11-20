package com.ndiapp.camera.camera

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.ndiapp.camera.media.AudioCapture
import com.ndiapp.camera.media.Yuv
import com.ndiapp.camera.ndi.NdiNative
import com.ndiapp.camera.ndi.NdiSender
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

data class CameraRequest(
    val lensFacing: Int,
    val cameraId: String?,
    val height: Int,
    val fps: Int,
    val withAudio: Boolean,
    val ndiName: String
)

/** Lente trasera disponible en el dispositivo (ultra gran angular, principal, tele). */
data class CameraLens(
    val id: String,
    val label: String,
    val focalMm: Float,
    val equivMm: Float
)

/** Cámara como fuente NDI: vista previa, análisis de frames y audio del micrófono. */
class CameraSession(private val context: Context) {

    companion object {
        private const val TAG = "CameraSession"

        /** Velocidades de obturación fotográficas, de la más rápida a la más lenta. */
        private val shutterCandidates = listOf(
            8000, 6400, 5000, 4000, 3200, 2500, 2000, 1600, 1250, 1000,
            800, 640, 500, 400, 320, 250, 200, 160, 125, 100,
            80, 60, 50, 40, 30, 25, 20, 15, 12, 10, 8, 6, 5, 4, 3,
            2, 1
        )
    }

    var onStatus: ((String) -> Unit)? = null

    /** La grabación local empezó o terminó (fin con el nombre del archivo o el error). */
    var onRecordingStarted: (() -> Unit)? = null
    var onRecordingFinished: ((String) -> Unit)? = null

    /** La cámara ya está abierta y sus rangos reales (ISO, EV, obturación) leídos. */
    var onCameraReady: (() -> Unit)? = null

    var minZoom: Float = 1f
        private set

    var maxZoom: Float = 1f
        private set

    /** Cámara que está abierta ahora mismo (su id de Camera2). */
    var cameraId: String? = null
        private set

    var exposureRange: IntRange = 0..0
        private set

    /** EV por paso de compensación (normalmente 1/3). */
    var exposureStep: Float = 1f / 3f
        private set

    /** Rango de ISO del sensor, acotado a un máximo manejable en la interfaz. */
    var isoRange: IntRange = 100..1600
        private set

    /** Distancia mínima de foco en dioptrías (0 = foco fijo). */
    var minFocusDistance: Float = 0f
        private set

    /** Tiempo de exposición real del sensor, en nanosegundos. */
    var exposureTimeRange: LongRange = 0L..0L
        private set

    /** Lentes traseras del dispositivo, ordenadas de la más angular a la más larga. */
    var rearLenses: List<CameraLens> = emptyList()
        private set

    /** La cámara abierta tiene flash (linterna). */
    var hasFlash: Boolean = false
        private set

    /** Luma media del último frame (0..1): base de la exposición automática propia. */
    @Volatile
    var averageLuma: Float = 0f
        private set

    val canRecord: Boolean
        get() = videoCapture != null

    val isRecording: Boolean
        get() = recording != null

    val isStreaming: Boolean
        get() = sender != null

    private var request: CameraRequest? = null
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var analysis: ImageAnalysis? = null
    private var sender: NdiSender? = null
    private var audio: AudioCapture? = null
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var rgbaBuffer = ByteArray(0)
    private var previewView: PreviewView? = null
    private var camera2Control: Camera2CameraControl? = null

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var audioSuspendedForRecording = false

    @Volatile
    private var muted = false

    val isRunning: Boolean
        get() = sender != null

    /** Abre la cámara y mantiene la vista previa, todavía sin emitir por NDI. */
    fun start(previewView: PreviewView, owner: LifecycleOwner, req: CameraRequest) {
        request = req
        this.previewView = previewView
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                rearLenses = readRearLenses(cameraProvider)
                val selector = if (req.cameraId != null) {
                    CameraSelector.Builder()
                        .addCameraFilter { infos ->
                            infos.filter { Camera2CameraInfo.from(it).cameraId == req.cameraId }
                        }
                        .build()
                } else {
                    CameraSelector.Builder().requireLensFacing(req.lensFacing).build()
                }
                val fallbackSelector =
                    CameraSelector.Builder().requireLensFacing(req.lensFacing).build()
                // 4:3 es el modo que usa el sensor completo; con 16:9 el ISP recorta
                // el cuadro por el lado corto y el visor sale más cerrado que la
                // cámara del sistema, que también trabaja en 4:3.
                val target = if (req.height >= 1080) Size(1440, 1080) else Size(960, 720)
                val resolutionSelector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            target,
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    )
                    .build()

                val preview = Preview.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .build()
                preview.setSurfaceProvider(previewView.surfaceProvider)

                val analysisBuilder = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                Camera2Interop.Extender(analysisBuilder).setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    if (req.fps >= 60) Range(30, 60) else Range(30, 30)
                )
                val imageAnalysis = analysisBuilder.build()
                imageAnalysis.setAnalyzer(analysisExecutor) { proxy ->
                    try {
                        val rotation = proxy.imageInfo.rotationDegrees
                        val needed = proxy.width * proxy.height * 4
                        if (rgbaBuffer.size < needed) rgbaBuffer = ByteArray(needed)
                        val dims = Yuv.toRgba(proxy, rotation, rgbaBuffer)
                        averageLuma = Yuv.averageLuma(proxy)
                        sender?.sendVideo(rgbaBuffer, dims[0], dims[1], req.fps)
                    } catch (t: Throwable) {
                        Log.w(TAG, "No se pudo procesar el frame", t)
                    } finally {
                        proxy.close()
                    }
                }
                analysis = imageAnalysis

                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(
                            Quality.FHD,
                            FallbackStrategy.higherQualityOrLowerThan(Quality.HD)
                        )
                    )
                    .build()
                val video = VideoCapture.withOutput(recorder)

                // El vídeo local es opcional: si el dispositivo no admite los tres
                // casos de uso a la vez, la app sigue emitiendo sin grabación.
                val bound = runCatching {
                    cameraProvider.bindToLifecycle(
                        owner, selector, preview, imageAnalysis, video
                    ) to video
                }.recoverCatching {
                    Log.w(TAG, "Sin grabación local: ${it.message}")
                    cameraProvider.bindToLifecycle(owner, selector, preview, imageAnalysis) to null
                }.recoverCatching {
                    Log.w(TAG, "Lente no disponible, se usa la principal: ${it.message}")
                    cameraProvider.bindToLifecycle(
                        owner, fallbackSelector, preview, imageAnalysis
                    ) to null
                }.getOrThrow()
                camera = bound.first
                videoCapture = bound.second

                camera2Control = Camera2CameraControl.from(bound.first.cameraControl)
                runCatching {
                    val manager =
                        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    val characteristics = manager.getCameraCharacteristics(
                        Camera2CameraInfo.from(bound.first.cameraInfo).cameraId
                    )
                    characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let {
                        isoRange = it.lower..minOf(it.upper, 6400)
                    }
                    hasFlash = characteristics
                        .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    minFocusDistance = characteristics
                        .get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                    characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let {
                        exposureTimeRange = it.lower..it.upper
                    }
                }
                cameraId = runCatching {
                    Camera2CameraInfo.from(bound.first.cameraInfo).cameraId
                }.getOrNull()
                val zoomState = bound.first.cameraInfo.zoomState.value
                minZoom = zoomState?.minZoomRatio?.coerceAtLeast(0.1f) ?: 1f
                maxZoom = zoomState?.maxZoomRatio?.coerceAtLeast(minZoom) ?: 1f
                val state = bound.first.cameraInfo.exposureState
                exposureRange = state.exposureCompensationRange.lower..
                    state.exposureCompensationRange.upper
                exposureStep = state.exposureCompensationStep.toFloat()
                onCameraReady?.invoke()
                onStatus?.invoke(
                    if (sender != null) "Enviando «${req.ndiName}»" else "Vista previa"
                )
            } catch (t: Throwable) {
                Log.e(TAG, "No se pudo iniciar la cámara", t)
                onStatus?.invoke("Error de cámara: ${t.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Velocidades de obturación que el sensor puede usar realmente a estos fps.
     * El extremo lento es el periodo de cuadro (una exposición más larga no cabe
     * en un frame de vídeo) y el rápido, el mínimo del sensor.
     */
    fun shutterValues(fps: Int): List<Int> {
        val period = 1_000_000_000L / fps.coerceAtLeast(1)
        val maxNs = if (exposureTimeRange.last > 0L) {
            minOf(exposureTimeRange.last, period)
        } else {
            period
        }
        val minNs = if (exposureTimeRange.first > 0L) exposureTimeRange.first else 0L
        val speeds = shutterCandidates.filter { denominator ->
            val ns = 1_000_000_000L / denominator
            ns >= minNs && ns <= maxNs
        }
        if (speeds.isNotEmpty()) return speeds
        // Fps raro sin coincidencia exacta: se ofrece al menos el periodo de cuadro.
        return listOf(fps.coerceAtLeast(1))
    }

    /** Lentes traseras reales del dispositivo, etiquetadas respecto de la principal. */
    private fun readRearLenses(cameraProvider: ProcessCameraProvider): List<CameraLens> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val raw = cameraProvider.availableCameraInfos.mapNotNull { info ->
            if (info.lensFacing != CameraSelector.LENS_FACING_BACK) return@mapNotNull null
            runCatching {
                val id = Camera2CameraInfo.from(info).cameraId
                val characteristics = manager.getCameraCharacteristics(id)
                val focal = characteristics
                    .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.minOrNull() ?: return@runCatching null
                val physical = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val diagonal = if (physical != null) {
                    hypot(physical.width.toDouble(), physical.height.toDouble())
                } else {
                    0.0
                }
                val crop = if (diagonal > 0.1) 43.2666 / diagonal else 1.0
                CameraLens(id, "", focal, (focal * crop).toFloat())
            }.getOrNull()
        }.distinctBy { it.id }.sortedBy { it.equivMm }
        if (raw.isEmpty()) return emptyList()
        // La lente más cercana a 26 mm equivalentes se toma como «1×».
        val main = raw.minByOrNull { abs(it.equivMm - 26f) } ?: raw.first()
        return raw.map { lens ->
            val ratio = ((lens.equivMm / main.equivMm.coerceAtLeast(1f)) * 10f).roundToInt() / 10f
            val safe = ratio.coerceAtLeast(0.1f)
            val label = if (abs(safe - safe.roundToInt()) < 0.05f) {
                "${safe.roundToInt()}×"
            } else {
                String.format(Locale.US, "%.1f×", safe)
            }
            lens.copy(label = label)
        // Un mismo objetivo puede aparecer dos veces (cámara lógica y física):
        // se deja una entrada por campo de visión.
        }.distinctBy { it.label }
    }

    /** Crea la fuente NDI y empieza a enviar los frames de la vista previa. */
    fun startStreaming(): Boolean {
        val req = request ?: return false
        if (sender != null) return true
        if (!NdiNative.init()) {
            onStatus?.invoke("El runtime de NDI no está disponible")
            return false
        }
        val newSender = NdiSender(req.ndiName)
        if (!newSender.isValid) {
            onStatus?.invoke("No se pudo crear la fuente NDI")
            return false
        }
        sender = newSender
        if (req.withAudio && !audioSuspendedForRecording) startAudio(req)
        onStatus?.invoke("Enviando «${req.ndiName}»")
        return true
    }

    private fun startAudio(req: CameraRequest) {
        if (audio != null) return
        audio = AudioCapture { pcm, samples, channels, rate ->
            if (!muted) sender?.sendAudio(pcm, samples, channels, rate)
        }.also { it.start() }
    }

    /** Corta la emisión NDI y deja la vista previa en marcha. */
    fun stopStreaming() {
        audio?.stop()
        audio = null
        sender?.close()
        sender = null
        if (request != null) onStatus?.invoke("Vista previa")
    }

    fun stop() {
        stopRecordingInternal(notify = false)
        request = null
        analysis?.clearAnalyzer()
        analysis = null
        runCatching { provider?.unbindAll() }
        provider = null
        camera = null
        cameraId = null
        minZoom = 1f
        maxZoom = 1f
        camera2Control = null
        videoCapture = null
        hasFlash = false
        averageLuma = 0f
        audio?.stop()
        audio = null
        sender?.close()
        sender = null
    }

    /** Nº de conexiones NDI activas: si hay alguna, otro equipo está usando la fuente. */
    fun connections(): Int = sender?.connections() ?: 0

    fun setZoom(ratio: Float) {
        camera?.cameraControl?.setZoomRatio(ratio.coerceIn(minZoom, maxZoom))
    }

    fun setExposure(index: Int) {
        camera?.cameraControl?.setExposureCompensationIndex(
            index.coerceIn(exposureRange.first, exposureRange.last)
        )
    }

    fun setTorch(on: Boolean) {
        if (on && !hasFlash) return
        camera?.cameraControl?.enableTorch(on)
    }

    /** Bloquea la exposición automática o vuelve a ella. */
    fun setManualExposure(manual: Boolean) {
        camera2Control?.addCaptureRequestOptions(
            CaptureRequestOptions.Builder()
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_MODE,
                    if (manual) CaptureRequest.CONTROL_AE_MODE_OFF
                    else CaptureRequest.CONTROL_AE_MODE_ON
                )
                .build()
        )
    }

    /** Exposición manual: ISO y velocidad de obturación en nanosegundos. */
    fun setManualExposureValues(iso: Int, shutterNs: Long) {
        val period = 1_000_000_000L / (request?.fps ?: 30).coerceAtLeast(1)
        val sensorMax = if (exposureTimeRange.last > 0L) exposureTimeRange.last else period
        val sensorMin = if (exposureTimeRange.first > 0L) exposureTimeRange.first else 1_000L
        val maxShutter = minOf(period, sensorMax)
        // Nunca se pide un tiempo que el sensor no admite: el driver lo ignoraría.
        val shutter = shutterNs.coerceIn(minOf(sensorMin, maxShutter), maxShutter)
        camera2Control?.addCaptureRequestOptions(
            CaptureRequestOptions.Builder()
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_OFF
                )
                .setCaptureRequestOption(
                    CaptureRequest.SENSOR_SENSITIVITY,
                    iso.coerceIn(isoRange.first, isoRange.last)
                )
                .setCaptureRequestOption(
                    CaptureRequest.SENSOR_EXPOSURE_TIME,
                    shutter
                )
                .build()
        )
    }

    /** Foco manual por dioptrías (0 = infinito) o vuelta al enfoque continuo. */
    fun setManualFocus(enabled: Boolean, diopters: Float) {
        val control = camera2Control ?: return
        val builder = CaptureRequestOptions.Builder()
        if (enabled && minFocusDistance > 0f) {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_OFF
            )
            builder.setCaptureRequestOption(
                CaptureRequest.LENS_FOCUS_DISTANCE,
                diopters.coerceIn(0f, minFocusDistance)
            )
        } else {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
        }
        control.addCaptureRequestOptions(builder.build())
    }

    /** Balance de blancos por modo (los presets de la app). */
    fun setWhiteBalance(mode: Int) {
        camera2Control?.addCaptureRequestOptions(
            CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, mode)
                .build()
        )
    }

    /** Enfoca el punto tocado en el visor y deja el enfoque en automático. */
    fun focusAt(x: Float, y: Float) {
        setManualFocus(false, 0f)
        val view = previewView ?: return
        val point = view.meteringPointFactory.createPoint(x, y)
        camera?.cameraControl?.startFocusAndMetering(
            FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                .setAutoCancelDuration(3, TimeUnit.SECONDS)
                .build()
        )
    }

    fun setMuted(value: Boolean) {
        muted = value
    }

    // ------------------------------------------------------------ grabación --

    /** Graba la vista previa en un MP4 dentro de Películas/NDI App. */
    fun startRecording(): Boolean {
        if (recording != null) return true
        val capture = videoCapture ?: run {
            onStatus?.invoke("Este dispositivo no admite grabación local")
            return false
        }
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        val directory = File(root, "Grabaciones").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(directory, "NDI-App-$stamp.mp4")
        var pending = capture.output.prepareRecording(
            context,
            FileOutputOptions.Builder(file).build()
        )
        // El micrófono sólo puede usarlo un cliente a la vez: mientras se graba con
        // audio, la emisión NDI sigue en video y el micrófono vuelve al terminar.
        if (request?.withAudio == true) {
            audio?.stop()
            audio = null
            audioSuspendedForRecording = true
            pending = pending.withAudioEnabled()
        }
        onStatus?.invoke("Grabando")
        recording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> onRecordingStarted?.invoke()
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    resumeAudioAfterRecording()
                    if (event.hasError()) {
                        Log.w(TAG, "Grabación fallida: ${event.error}")
                        file.delete()
                        onStatus?.invoke("No se pudo grabar")
                        onRecordingFinished?.invoke("No se pudo grabar el vídeo")
                    } else {
                        publishRecording(file)
                    }
                }
            }
        }
        return true
    }

    fun stopRecording() {
        stopRecordingInternal(notify = true)
    }

    private fun stopRecordingInternal(notify: Boolean) {
        val active = recording ?: return
        if (!notify) {
            runCatching { active.stop() }
            recording = null
            resumeAudioAfterRecording()
        } else {
            runCatching { active.stop() }
        }
    }

    private fun resumeAudioAfterRecording() {
        if (!audioSuspendedForRecording) return
        audioSuspendedForRecording = false
        val req = request ?: return
        if (req.withAudio && sender != null) startAudio(req)
    }

    /** Copia la grabación a la galería para que quede visible fuera de la app. */
    private fun publishRecording(file: File) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            onRecordingFinished?.invoke(file.name)
            return
        }
        ioExecutor.execute {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/NDI App")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = runCatching {
                resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            }.getOrNull()
            val saved = uri != null && runCatching {
                resolver.openOutputStream(uri)?.use { output ->
                    file.inputStream().use { it.copyTo(output) }
                } ?: error("sin salida")
                val done = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                resolver.update(uri, done, null, null)
                file.delete()
                true
            }.getOrDefault(false)
            val message = if (saved) {
                file.name
            } else {
                uri?.let { runCatching { resolver.delete(it, null, null) } }
                "${file.name} (en la carpeta de la app)"
            }
            mainHandler.post {
                onStatus?.invoke(if (saved) "Guardado en Películas/NDI App" else "Grabación guardada")
                onRecordingFinished?.invoke(message)
            }
        }
    }
}
