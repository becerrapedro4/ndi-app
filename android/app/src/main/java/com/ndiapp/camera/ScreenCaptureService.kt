package com.ndiapp.camera

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf
import com.ndiapp.camera.media.AudioCapture
import com.ndiapp.camera.media.SystemAudioCapture
import com.ndiapp.camera.ndi.NdiNative
import com.ndiapp.camera.ndi.NdiSender

/** Publica la pantalla completa del dispositivo como fuente NDI. */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"

        /**
         * Estado real de la captura, vivo mientras viva el proceso. La interfaz lo
         * lee directamente para no inventar el estado cuando se recrea la pantalla
         * o se cambia de pestaña con la emisión en marcha.
         */
        val running = mutableStateOf(false)
        const val ACTION_START = "com.ndiapp.camera.SCREEN_START"
        const val ACTION_STOP = "com.ndiapp.camera.SCREEN_STOP"
        private const val CHANNEL_ID = "ndi_screen"
        private const val NOTIFICATION_ID = 42

        /** 0 = sin audio, 1 = micrófono, 2 = audio del sistema. */
        const val AUDIO_NONE = 0
        const val AUDIO_MIC = 1
        const val AUDIO_SYSTEM = 2

        fun startIntent(
            context: Context,
            resultCode: Int,
            data: Intent,
            height: Int,
            audioMode: Int = AUDIO_MIC
        ): Intent =
            Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START
                putExtra("resultCode", resultCode)
                putExtra("resultData", data)
                putExtra("height", height)
                putExtra("audioMode", audioMode)
            }

        fun stopIntent(context: Context): Intent =
            Intent(context, ScreenCaptureService::class.java).apply { action = ACTION_STOP }
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var sender: NdiSender? = null
    private var workerThread: HandlerThread? = null
    private var rgbaBuffer = ByteArray(0)
    private var mic: AudioCapture? = null
    private var systemAudio: SystemAudioCapture? = null
    private var dot: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                if (intent.getParcelableExtra<Intent>("resultData") == null) {
                    // Reinicio del sistema sin sesión de captura: no hay nada que emitir.
                    stopSelf()
                    return START_NOT_STICKY
                }
                createChannel()
                startForeground(NOTIFICATION_ID, buildNotification())
                val resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED)
                val data = intent.getParcelableExtra<Intent>("resultData")
                val height = intent.getIntExtra("height", 1080)
                val audioMode = intent.getIntExtra("audioMode", AUDIO_MIC)
                if (data != null) startCapture(resultCode, data, height, audioMode)
            }
        }
        return START_STICKY
    }

    private fun startCapture(
        resultCode: Int,
        data: Intent,
        targetHeight: Int,
        audioMode: Int
    ) {
        if (!NdiNative.init()) {
            Log.w(TAG, "Runtime de NDI no disponible")
            stopSelf()
            return
        }
        val created = NdiSender("NDI App Pantalla")
        if (!created.isValid) {
            stopSelf()
            return
        }
        sender = created

        val metrics = resources.displayMetrics
        val scale = if (metrics.heightPixels > targetHeight) {
            targetHeight.toFloat() / metrics.heightPixels.toFloat()
        } else {
            1f
        }
        val width = ((metrics.widthPixels * scale).toInt() / 2) * 2
        val height = ((metrics.heightPixels * scale).toInt() / 2) * 2

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mediaProjection = manager.getMediaProjection(resultCode, data) ?: run {
            stopSelf()
            return
        }
        projection = mediaProjection
        mediaProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, Handler(mainLooper))

        startAudio(mediaProjection, audioMode)

        val thread = HandlerThread("ndi-screen").also { it.start() }
        workerThread = thread
        val handler = Handler(thread.looper)

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        rgbaBuffer = ByteArray(width * height * 4)

        reader.setOnImageAvailableListener({ source ->
            val image = try {
                source.acquireLatestImage()
            } catch (t: Throwable) {
                null
            } ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val rowStride = plane.rowStride
                val frameWidth = image.width
                val frameHeight = image.height
                if (rgbaBuffer.size < frameWidth * frameHeight * 4) {
                    rgbaBuffer = ByteArray(frameWidth * frameHeight * 4)
                }
                if (rowStride == frameWidth * 4) {
                    buffer.get(rgbaBuffer, 0, frameWidth * frameHeight * 4)
                } else {
                    for (row in 0 until frameHeight) {
                        buffer.position(row * rowStride)
                        buffer.get(rgbaBuffer, row * frameWidth * 4, frameWidth * 4)
                    }
                }
                sender?.sendVideo(rgbaBuffer, frameWidth, frameHeight, 30)
            } catch (t: Throwable) {
                Log.w(TAG, "Frame de pantalla fallido", t)
            } finally {
                image.close()
            }
        }, handler)

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "ndi-screen",
            width,
            height,
            resources.displayMetrics.densityDpi,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler
        )
        running.value = true
        showBroadcastDot()
    }

    /**
     * Punto rojo flotante que solo se ve en pantalla: la ventana lleva
     * FLAG_SECURE, así que SurfaceFlinger no la incluye en la captura y no sale
     * en la fuente NDI.
     */
    private fun showBroadcastDot() {
        if (dot != null) return
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Sin permiso de superposición: no se muestra el punto rojo")
            return
        }
        val density = resources.displayMetrics.density
        val size = (14 * density).toInt()
        val view = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFFF3B30.toInt())
                setStroke((2 * density).toInt(), 0x40FFFFFF)
            }
        }
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_SECURE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (10 * density).toInt()
            y = (8 * density).toInt()
        }
        runCatching {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(view, params)
            dot = view
        }.onFailure { Log.w(TAG, "No se pudo mostrar el punto rojo", it) }
    }

    private fun hideBroadcastDot() {
        dot?.let { view ->
            runCatching {
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view)
            }
        }
        dot = null
    }

    /** Micrófono o audio reproducido por otras apps (Android 10+). */
    private fun startAudio(mediaProjection: MediaProjection, audioMode: Int) {
        when (audioMode) {
            AUDIO_MIC -> {
                mic = AudioCapture(this, onBuffer = { pcm, samples, channels, rate ->
                    sender?.sendAudio(pcm, samples, channels, rate)
                }).also { it.start() }
            }

            AUDIO_SYSTEM -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val capture = SystemAudioCapture(mediaProjection) { pcm, samples, channels, rate ->
                        sender?.sendAudio(pcm, samples, channels, rate)
                    }
                    if (capture.start()) {
                        systemAudio = capture
                    } else {
                        Log.w(TAG, "El audio del sistema no está disponible en este dispositivo")
                    }
                } else {
                    Log.w(TAG, "El audio del sistema necesita Android 10 o superior")
                }
            }
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Transmisión NDI",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun buildNotification(): Notification {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            android.app.PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
        val intent = android.app.PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            flags
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("NDI App")
            .setContentText("Transmitiendo la pantalla por NDI")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .setContentIntent(intent)
            .build()
    }

    override fun onDestroy() {
        running.value = false
        hideBroadcastDot()
        mic?.stop()
        mic = null
        systemAudio?.stop()
        systemAudio = null
        sender?.close()
        sender = null
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { projection?.stop() }
        projection = null
        workerThread?.quitSafely()
        workerThread = null
        super.onDestroy()
    }
}
