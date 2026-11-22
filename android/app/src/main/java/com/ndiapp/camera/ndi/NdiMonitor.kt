package com.ndiapp.camera.ndi

import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

/** Descubrimiento de fuentes NDI presentes en la red local. */
class NdiFinder {

    private var handle: Long = NdiNative.findCreate()

    val isAvailable: Boolean
        get() = handle != 0L

    @Synchronized
    fun sources(timeoutMs: Int = 400): List<NdiSource> {
        if (handle == 0L) return emptyList()
        val raw = NdiNative.findSources(handle, timeoutMs) ?: return emptyList()
        return raw.map { entry ->
            val parts = entry.split('\t')
            NdiSource(
                name = parts.firstOrNull()?.substringAfterLast('(', parts.first())?.trimEnd(')')
                    ?: parts.first(),
                fullName = parts.firstOrNull().orEmpty(),
                url = parts.getOrNull(1).orEmpty()
            )
        }
    }

    /**
     * Rehace la instancia de NDI. Al cambiar la red del proceso (bind a Wi-Fi)
     * las tomas del finder quedan sobre la interfaz vieja y libndi caía dentro
     * de `find_get_current_sources`; con una instancia nueva eso no pasa.
     */
    @Synchronized
    fun restart() {
        if (handle != 0L) {
            NdiNative.findDestroy(handle)
            handle = 0L
        }
        handle = NdiNative.findCreate()
    }

    @Synchronized
    fun close() {
        if (handle != 0L) {
            NdiNative.findDestroy(handle)
            handle = 0L
        }
    }
}

data class NdiSource(val name: String, val fullName: String, val url: String)

/**
 * Bytes que el sistema entregó a la app. El SDK de NDI no informa el caudal del
 * flujo recibido y el cuadro llega ya decodificado en RGBA, así que sumar su
 * tamaño daba cifras imposibles (4 Gb/s a 1080p60). Esto mide la red de verdad.
 */
private fun receivedBytes(): Long {
    val total = TrafficStats.getUidRxBytes(Process.myUid())
    return if (total < 0) 0L else total
}

/** Medidas del flujo recibido, actualizadas dos veces por segundo. */
data class StreamStats(
    val fps: Float = 0f,
    val mbps: Float = 0f,
    val levelLeft: Float = 0f,
    val levelRight: Float = 0f,
    val audioRate: Int = 0,
    val audioChannels: Int = 0,
    val audioPackets: Int = 0,
    val audioProblem: String = ""
)

/** Recibe video y audio de una fuente NDI y los entrega a la UI. */
class NdiReceiverController {

    @Volatile
    private var handle: Long = 0L
    private var job: Job? = null

    /** Serializa el arranque y el cierre: libndi no admite usarse y destruirse a la vez. */
    private val lock = Any()
    private var track: AudioTrack? = null
    private var trackRate = 0
    private var trackChannels = 0

    @Volatile
    private var levelLeft = 0f

    @Volatile
    private var levelRight = 0f

    val isRunning: Boolean
        get() = handle != 0L

    fun start(
        scope: CoroutineScope,
        source: NdiSource,
        audioEnabled: () -> Boolean,
        lowBandwidth: Boolean = false,
        onFrame: (Bitmap) -> Unit,
        onStats: (StreamStats) -> Unit = {}
    ) {
        // El anterior se destruye recién cuando su hilo salió de libndi.
        releasePrevious()
        val socket = NdiNative.recvCreate(source.fullName, source.url, lowBandwidth)
        if (socket == 0L) return
        synchronized(lock) { handle = socket }

        val rgbaBuffer = ByteArray(1920 * 1080 * 4)
        val pcmBuffer = FloatArray(48_000 * 2)

        job = scope.launch(Dispatchers.Default) {
            var reusable: Bitmap? = null
            var frames = 0
            var lastRx = receivedBytes()
            var audioRate = 0
            var audioChannels = 0
            var audioPackets = 0
            var audioProblem = ""
            var windowStart = SystemClock.elapsedRealtime()
            while (isActive) {
                val poll = NdiNative.recvPoll(socket, rgbaBuffer, pcmBuffer, 60) ?: break
                when (poll[0]) {
                    1 -> {
                        val width = poll[1]
                        val height = poll[2]
                        if (width > 0 && height > 0) {
                            frames++
                            withContext(Dispatchers.Main) {
                                val existing = reusable
                                val bitmap = if (existing == null || existing.width != width ||
                                    existing.height != height
                                ) {
                                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                        .also { reusable = it }
                                } else {
                                    existing
                                }
                                bitmap.copyPixelsFromBuffer(
                                    ByteBuffer.wrap(rgbaBuffer, 0, width * height * 4)
                                )
                                onFrame(bitmap)
                            }
                        }
                    }

                    2 -> {
                        audioPackets++
                        audioRate = poll[5]
                        audioChannels = poll[4]
                        updateLevels(pcmBuffer, poll[3], poll[4])
                        if (audioEnabled()) {
                            audioProblem = writeAudio(pcmBuffer, poll[3], poll[4], poll[5])
                        }
                    }
                }

                val now = SystemClock.elapsedRealtime()
                val elapsed = now - windowStart
                if (elapsed >= 500) {
                    val seconds = elapsed / 1000f
                    // Caudal real: diferencia de bytes recibidos en la ventana.
                    val rx = receivedBytes()
                    val delta = if (rx >= lastRx) rx - lastRx else 0L
                    lastRx = rx
                    val stats = StreamStats(
                        fps = frames / seconds,
                        mbps = delta * 8f / 1_000_000f / seconds,
                        levelLeft = levelLeft,
                        levelRight = levelRight,
                        audioRate = audioRate,
                        audioChannels = audioChannels,
                        audioPackets = audioPackets,
                        audioProblem = audioProblem
                    )
                    audioPackets = 0
                    levelLeft *= 0.6f
                    levelRight *= 0.6f
                    frames = 0
                    windowStart = now
                    withContext(Dispatchers.Main) { onStats(stats) }
                }
            }
        }
    }

    private fun updateLevels(data: FloatArray, samples: Int, channels: Int) {
        if (samples <= 0 || channels <= 0) return
        var left = 0f
        var right = 0f
        for (index in 0 until samples) {
            val l = data[index * channels]
            left += l * l
            if (channels > 1) {
                val r = data[index * channels + 1]
                right += r * r
            } else {
                right += l * l
            }
        }
        val rmsLeft = kotlin.math.sqrt(left / samples)
        val rmsRight = kotlin.math.sqrt(right / samples)
        levelLeft = maxOf(rmsLeft, levelLeft)
        levelRight = maxOf(rmsRight, levelRight)
    }

    /** Devuelve un mensaje si la salida de audio no pudo escribirse. */
    private fun writeAudio(data: FloatArray, samples: Int, channels: Int, rate: Int): String {
        if (samples <= 0 || channels <= 0 || rate <= 0) return ""
        if (track == null || trackRate != rate || trackChannels != channels) {
            track?.release()
            val channelMask =
                if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            val minimum = AudioTrack.getMinBufferSize(
                rate,
                channelMask,
                AudioFormat.ENCODING_PCM_FLOAT
            )
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(rate)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minimum, rate * channels * 4 / 5))
                .build()
            trackRate = rate
            trackChannels = channels
            track?.play()
        }
        // Bloqueante a propósito: mantiene el audio en tiempo real y evita
        // descartar muestras cuando el buffer se llena.
        val result = runCatching {
            track?.write(data, 0, samples * channels, AudioTrack.WRITE_BLOCKING)
        }
        return when {
            result.isFailure -> "error de salida: ${result.exceptionOrNull()?.message}"
            (result.getOrNull() ?: 0) < 0 -> "la salida de audio rechazó el bloque"
            else -> ""
        }
    }

    fun stop() {
        val running = synchronized(lock) {
            val current = job
            job = null
            current
        }
        val socket = synchronized(lock) {
            val current = handle
            handle = 0L
            current
        }
        running?.cancel()
        releaseAudio()
        destroyWhenIdle(running, socket)
    }

    /** Suelta la fuente anterior antes de abrir otra. */
    private fun releasePrevious() {
        val running = synchronized(lock) {
            val current = job
            job = null
            current
        }
        val socket = synchronized(lock) {
            val current = handle
            handle = 0L
            current
        }
        running?.cancel()
        releaseAudio()
        destroyWhenIdle(running, socket)
    }

    /**
     * Destruye la instancia sólo cuando el hilo de lectura ya salió de libndi.
     * Destruirla mientras `recvPoll` está dentro de la librería la hacía caer en
     * su propio hilo de captura (el crash que se veía al abrir una fuente).
     */
    private fun destroyWhenIdle(running: Job?, socket: Long) {
        if (socket == 0L) return
        Thread {
            runCatching {
                val pending = running ?: return@Thread
                kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.withTimeoutOrNull(1_500L) { pending.join() }
                }
            }
            NdiNative.recvDestroy(socket)
        }.apply {
            isDaemon = true
            name = "ndi-recv-close"
        }.start()
    }

    private fun releaseAudio() {
        track?.let {
            try {
                it.pause()
                it.flush()
            } catch (_: Exception) {
            }
            it.release()
        }
        track = null
        trackRate = 0
        trackChannels = 0
        levelLeft = 0f
        levelRight = 0f
    }
}
