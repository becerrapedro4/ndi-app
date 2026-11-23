package com.ndiapp.camera.mixer

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Environment
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Grabación del programa del mezclador.
 *
 * El vídeo no se lee ni se convierte: el compositor GL dibuja el cuadro compuesto
 * directamente en la superficie de entrada del codificador H.264, así grabar no
 * cuesta una copia de píxeles por cuadro. El audio es la misma mezcla que sale
 * por NDI (micrófono + fuente), codificada como AAC-LC.
 *
 * Las dos pistas van a un único MP4. El mezclador no puede hacer su trabajo si el
 * grabador falla, así que todo error se anota y se devuelve un archivo vacío en
 * vez de romper la mezcla.
 */
class ProgramRecorder(
    file: File,
    width: Int,
    height: Int,
    fps: Int,
    bitrate: Int = DEFAULT_BITRATE,
    private val withAudio: Boolean = true
) {

    companion object {
        private const val TAG = "ProgramRecorder"

        /** 8 Mb/s: suficiente para 1080p30 con movimiento sin inflar el archivo. */
        const val DEFAULT_BITRATE = 8_000_000

        const val SAMPLE_RATE = 48_000
        const val AUDIO_CHANNELS = 2

        private const val AUDIO_BITRATE = 128_000

        /** Bloques de 20 ms en cola (~480 ms); lo que sobre se descarta. */
        private const val AUDIO_QUEUE = 24

        /** Sin pista de audio tras este plazo se empieza sólo con el vídeo. */
        private const val AUDIO_WAIT_MS = 2_500L

        /** Carpeta pública de la app: no necesita permisos de almacenamiento. */
        fun newOutputFile(context: Context): File {
            val base = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                ?: context.filesDir
            val dir = File(base, "Grabaciones")
            runCatching { dir.mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            return File(dir, "NDI-PGM-$stamp.mp4")
        }
    }

    private class Sample(
        val video: Boolean,
        val data: ByteArray,
        val ptsUs: Long,
        val flags: Int
    )

    val file: File = file

    private val muxer =
        MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

    private val codec: MediaCodec =
        MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)

    /** Superficie que el compositor usa como segunda salida. */
    val inputSurface: Surface

    private var audioCodec: MediaCodec? = null

    private val muxerLock = Any()
    private var muxerStarted = false
    private var videoTrack = -1
    private var audioTrack = -1
    private val pending = ArrayList<Sample>()

    private val audioQueue = ArrayBlockingQueue<ByteArray>(AUDIO_QUEUE)
    private var audioSamples = 0L
    private var lastVideoPtsUs = 0L
    private var frames = 0L

    private val startedAt = System.nanoTime()

    @Volatile
    private var running = true

    @Volatile
    private var endOfStream = false

    private var thread: Thread? = null

    private val bufferInfo = MediaCodec.BufferInfo()

    init {
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            width.coerceAtLeast(2) and -2,
            height.coerceAtLeast(2) and -2
        )
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps.coerceIn(1, 60))
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()

        if (withAudio) {
            audioCodec = runCatching {
                val audio = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                val audioFormat = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC,
                    SAMPLE_RATE,
                    AUDIO_CHANNELS
                )
                audioFormat.setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC
                )
                audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
                audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                audio.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                audio.start()
                audio
            }.onFailure { Log.w(TAG, "Sin codificador AAC: se graba sólo el vídeo", it) }
                .getOrNull()
        }
    }

    /** Segundos grabados, para el contador de la interfaz. */
    val elapsedSeconds: Int
        get() = ((System.nanoTime() - startedAt) / 1_000_000_000L).toInt()

    fun start() {
        if (thread != null) return
        val worker = Thread { loop() }
        worker.name = "pgm-recorder"
        worker.isDaemon = true
        thread = worker
        worker.start()
    }

    /**
     * Mismo PCM que sale por NDI: float intercalado, 48 kHz estéreo. Se pasa a
     * 16 bits y se encola; el hilo del grabador lo entrega al codificador.
     */
    fun submitAudio(pcm: FloatArray, samples: Int, channels: Int, sampleRate: Int) {
        if (!running) return
        if (audioCodec == null) return
        if (samples <= 0 || channels != AUDIO_CHANNELS || sampleRate != SAMPLE_RATE) return
        val floats = samples * channels
        if (pcm.size < floats) return
        val data = ByteArray(floats * 2)
        var index = 0
        for (sample in 0 until floats) {
            val value = (pcm[sample].coerceIn(-1f, 1f) * 32767f).toInt()
            data[index++] = (value and 0xFF).toByte()
            data[index++] = ((value shr 8) and 0xFF).toByte()
        }
        // Si el grabador va atrasado se tira el bloque más viejo: el audio no
        // puede acumular medio segundo de retraso detrás del vídeo.
        if (!audioQueue.offer(data)) {
            audioQueue.poll()
            audioQueue.offer(data)
        }
    }

    /** Cierra las pistas y devuelve el MP4, o null si no quedó ni un cuadro. */
    fun stop(): File? {
        if (!running && thread == null) return null
        running = false
        runCatching { codec.signalEndOfInputStream() }
        val worker = thread
        thread = null
        runCatching { worker?.join(3500) }
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { inputSurface.release() }
        runCatching { audioCodec?.stop() }
        runCatching { audioCodec?.release() }
        audioCodec = null

        var saved = false
        synchronized(muxerLock) {
            if (muxerStarted) {
                runCatching { muxer.stop() }
                    .onSuccess { saved = true }
                    .onFailure { Log.w(TAG, "No se pudo cerrar el MP4", it) }
            }
            runCatching { muxer.release() }
        }
        if (!saved) {
            runCatching { file.delete() }
            return null
        }
        return file
    }

    // ------------------------------------------------------------ hilo --**

    private fun loop() {
        val deadline = System.nanoTime() + 3_000_000_000L
        var audioEnded = false
        while ((running || !endOfStream) && System.nanoTime() < deadline) {
            feedAudio()
            drain(codec, video = true)
            audioCodec?.let { drain(it, video = false) }
            if (endOfStream && !audioEnded) {
                audioEnded = true
                queueAudioEnd()
                audioCodec?.let { drain(it, video = false) }
            }
            if (endOfStream) break
            runCatching { Thread.sleep(4) }
        }
    }

    private fun feedAudio() {
        val codec = audioCodec ?: return
        while (true) {
            val block = audioQueue.poll() ?: return
            val index = runCatching { codec.dequeueInputBuffer(0) }.getOrDefault(-1)
            if (index < 0) {
                // Se devuelve a la cola delante para no perder el orden sonoro.
                runCatching {
                    if (!audioQueue.offer(block)) {
                        audioQueue.poll()
                        audioQueue.offer(block)
                    }
                }
                return
            }
            val buffer = codec.getInputBuffer(index)
            if (buffer == null) {
                codec.queueInputBuffer(index, 0, 0, audioPtsUs(), 0)
                continue
            }
            buffer.clear()
            buffer.put(block)
            codec.queueInputBuffer(index, 0, block.size, audioPtsUs(), 0)
            audioSamples += block.size / (AUDIO_CHANNELS * 2)
        }
    }

    private fun audioPtsUs(): Long =
        audioSamples * 1_000_000L / SAMPLE_RATE

    private fun queueAudioEnd() {
        val codec = audioCodec ?: return
        runCatching {
            val index = codec.dequeueInputBuffer(50_000)
            if (index >= 0) {
                codec.queueInputBuffer(
                    index, 0, 0, audioPtsUs(),
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
            }
        }
    }

    private fun drain(codec: MediaCodec, video: Boolean) {
        while (true) {
            val index = codec.dequeueOutputBuffer(bufferInfo, 0)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                addTrack(codec.outputFormat, video)
                continue
            }
            if (index < 0) continue

            val size = bufferInfo.size
            val flags = bufferInfo.flags
            val pts = bufferInfo.presentationTimeUs
            if (size > 0 && flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                val buffer = codec.getOutputBuffer(index)
                if (buffer != null) {
                    buffer.position(bufferInfo.offset)
                    buffer.limit(bufferInfo.offset + size)
                    val data = ByteArray(size)
                    buffer.get(data)
                    if (video) {
                        frames++
                        lastVideoPtsUs = pts
                    }
                    write(video, data, pts, flags)
                }
            }
            codec.releaseOutputBuffer(index, false)
            if (flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                if (video) endOfStream = true
                return
            }
        }
    }

    // ----------------------------------------------------------- muxer --**

    private fun addTrack(format: MediaFormat, video: Boolean) {
        synchronized(muxerLock) {
            val track = runCatching { muxer.addTrack(format) }.getOrDefault(-1)
            if (track < 0) return
            if (video) videoTrack = track else audioTrack = track
            startIfReady()
        }
    }

    private fun startIfReady() {
        if (muxerStarted || videoTrack < 0) return
        val waitingAudio = withAudio && audioCodec != null && audioTrack < 0
        if (waitingAudio) {
            val waitedMs = (System.nanoTime() - startedAt) / 1_000_000L
            if (waitedMs < AUDIO_WAIT_MS) return
        }
        muxer.start()
        muxerStarted = true
        pending.sortBy { it.ptsUs }
        for (sample in pending) writeLocked(sample)
        pending.clear()
    }

    private fun write(video: Boolean, data: ByteArray, ptsUs: Long, flags: Int) {
        synchronized(muxerLock) {
            val sample = Sample(video, data, ptsUs, flags)
            if (!muxerStarted) {
                pending.add(sample)
                startIfReady()
                return
            }
            writeLocked(sample)
        }
    }

    private fun writeLocked(sample: Sample) {
        val track = if (sample.video) videoTrack else audioTrack
        if (track < 0) return
        val info = MediaCodec.BufferInfo()
        info.set(
            0,
            sample.data.size,
            sample.ptsUs,
            sample.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME
        )
        runCatching {
            muxer.writeSampleData(track, ByteBuffer.wrap(sample.data), info)
        }.onFailure { Log.w(TAG, "Muestra descartada por el muxer", it) }
    }
}
