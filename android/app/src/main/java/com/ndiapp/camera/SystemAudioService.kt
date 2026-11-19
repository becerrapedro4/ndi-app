package com.ndiapp.camera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import androidx.compose.runtime.mutableStateOf
import com.ndiapp.camera.media.AudioNdiSource

/**
 * Publica el audio que reproducen otras apps como fuente NDI.
 *
 * Usa captura de reproducción (AudioPlaybackCapture) sobre una MediaProjection
 * sin VirtualDisplay: no se comparte la pantalla. Desde Android 14 hace falta un
 * servicio en primer plano de tipo mediaProjection antes de crear la proyección.
 */
class SystemAudioService : Service() {

    companion object {
        val running = mutableStateOf(false)
        val status = mutableStateOf("")

        private const val CHANNEL_ID = "ndi_system_audio"
        private const val NOTIFICATION_ID = 43
        const val ACTION_START = "com.ndiapp.camera.AUDIO_START"
        const val ACTION_STOP = "com.ndiapp.camera.AUDIO_STOP"

        fun startIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, SystemAudioService::class.java).apply {
                action = ACTION_START
                putExtra("resultCode", resultCode)
                putExtra("resultData", data)
            }

        fun stopIntent(context: Context): Intent =
            Intent(context, SystemAudioService::class.java).apply { action = ACTION_STOP }
    }

    private var projection: MediaProjection? = null
    private var source: AudioNdiSource? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>("resultData")
                val resultCode = intent.getIntExtra("resultCode", 0)
                if (data == null) {
                    status.value = "No se recibió el permiso de captura"
                    stopSelf()
                    return START_NOT_STICKY
                }
                val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                    as? MediaProjectionManager
                if (manager == null) {
                    status.value = "Este dispositivo no permite capturar audio del sistema"
                    stopSelf()
                    return START_NOT_STICKY
                }
                val created = runCatching { manager.getMediaProjection(resultCode, data) }
                    .getOrNull()
                if (created == null) {
                    status.value = "No se pudo crear la captura de audio"
                    stopSelf()
                    return START_NOT_STICKY
                }
                projection = created
                created.registerCallback(
                    object : MediaProjection.Callback() {
                        override fun onStop() {
                            stopSelf()
                        }
                    },
                    Handler(mainLooper)
                )

                val audio = AudioNdiSource(this)
                audio.onStatus = { message -> status.value = message }
                source = audio
                val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    audio.startSystemAudio(created, "NDI App Audio sistema (${Build.MODEL})")
                } else {
                    status.value = "El audio del sistema requiere Android 10 o superior"
                    false
                }
                if (!started) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                running.value = true
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running.value = false
        source?.stop()
        source = null
        runCatching { projection?.stop() }
        projection = null
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Audio del sistema", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Compartiendo audio del sistema por NDI")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}
