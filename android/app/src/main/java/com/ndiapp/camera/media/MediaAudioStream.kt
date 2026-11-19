package com.ndiapp.camera.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodifica la pista de audio de un archivo y entrega PCM float intercalado.
 *
 * El ritmo lo marca AudioTrack: cada bloque se escribe de forma bloqueante, así
 * que la entrega va en tiempo real. Al mismo tiempo se envía a NDI, y como el
 * vídeo lo reproduce MediaPlayer con el mismo reloj del dispositivo, imagen y
 * sonido quedan sincronizados.
 */
class MediaAudioStream(
    private val context: Context,
    private val onPcm: (pcm: FloatArray, samples: Int, channels: Int, sampleRate: Int) -> Unit
) {

    companion object {
        private const val TAG = "MediaAudioStream"
    }

    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var playing = false

    @Volatile
    private var pendingSeekMs = -1

    @Volatile
    private var looping = false

    @Volatile
    private var endOfStream = false

    private var channels = 2
    private var sampleRate = 48_000
    private var floatOutput = false

    /** Abre la pista de audio de [uri]. Devuelve false si el archivo no tiene audio. */
    fun open(uri: Uri): Boolean {
        close()
        val extractor = MediaExtractor()
        var trackIndex = -1
        var format: MediaFormat? = null
        return try {
            extractor.setDataSource(context, uri, null)
            for (index in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(index)
                val mime = candidate.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = index
                    format = candidate
                    break
                }
            }
            if (trackIndex < 0 || format == null) {
                extractor.release()
                return false
            }
            extractor.selectTrack(trackIndex)
            val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val count = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            if (count > 2) {
                // Sin mezcla multicanal: mejor sin audio que con canales falsos.
                extractor.release()
                return false
            }
            sampleRate = rate
            channels = count
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return false
            val decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()
            this.extractor = extractor
            codec = decoder
            ensureTrack()
            running = true
            thread = Thread { loop() }.also {
                it.name = "media-audio"
                it.priority = Thread.MAX_PRIORITY
                it.start()
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "No se pudo abrir el audio", t)
            runCatching { extractor.release() }
            false
        }
    }

    fun play() {
        playing = true
        track?.let { runCatching { it.play() } }
    }

    fun pause() {
        playing = false
        track?.let { runCatching { it.pause() } }
    }

    fun seekTo(positionMs: Int) {
        pendingSeekMs = positionMs.coerceAtLeast(0)
        endOfStream = false
    }

    fun setLooping(value: Boolean) {
        looping = value
    }

    fun close() {
        running = false
        thread?.let { runCatching { it.join(400) } }
        thread = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { extractor?.release() }
        extractor = null
        releaseTrack()
        playing = false
        endOfStream = false
        pendingSeekMs = -1
    }

    private fun loop() {
        val extractor = extractor ?: return
        val decoder = codec ?: return
        val info = MediaCodec.BufferInfo()
        while (running) {
            try {
                val seek = pendingSeekMs
                if (seek >= 0) {
                    pendingSeekMs = -1
                    extractor.seekTo(seek * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    decoder.flush()
                    flushTrack()
                    endOfStream = false
                }
                if (!playing) {
                    Thread.sleep(12)
                    continue
                }
                if (endOfStream) {
                    if (looping) {
                        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                        decoder.flush()
                        flushTrack()
                        endOfStream = false
                    } else {
                        Thread.sleep(20)
                        continue
                    }
                }

                val input = decoder.dequeueInputBuffer(10_000)
                if (input >= 0) {
                    val buffer = decoder.getInputBuffer(input)
                    val size = if (buffer != null) extractor.readSampleData(buffer, 0) else -1
                    if (size < 0) {
                        decoder.queueInputBuffer(
                            input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                    } else {
                        decoder.queueInputBuffer(input, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }

                val output = decoder.dequeueOutputBuffer(info, 10_000)
                when {
                    output >= 0 -> {
                        val buffer = decoder.getOutputBuffer(output)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val chunk = ByteArray(info.size)
                            buffer.get(chunk)
                            decoder.releaseOutputBuffer(output, false)
                            emit(chunk)
                        } else {
                            decoder.releaseOutputBuffer(output, false)
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            endOfStream = true
                        }
                    }

                    output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> applyOutputFormat(
                        decoder.outputFormat
                    )
                }
            } catch (t: Throwable) {
                if (running) Log.w(TAG, "Fallo al decodificar audio", t)
                return
            }
        }
    }

    private fun emit(bytes: ByteArray) {
        val floats: FloatArray
        val count: Int
        if (floatOutput) {
            count = bytes.size / 4
            if (count <= 0) return
            floats = FloatArray(count)
            ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asFloatBuffer().get(floats)
        } else {
            count = bytes.size / 2
            if (count <= 0) return
            floats = FloatArray(count)
            val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            for (index in 0 until count) floats[index] = shorts.get(index) / 32_768f
        }
        val frames = count / channels
        if (frames <= 0) return
        val usable = frames * channels
        val pcm = if (usable == floats.size) floats else floats.copyOf(usable)
        onPcm(pcm, frames, channels, sampleRate)
        track?.let { sink -> runCatching { sink.write(pcm, 0, usable, AudioTrack.WRITE_BLOCKING) } }
    }

    private fun applyOutputFormat(format: MediaFormat) {
        runCatching {
            if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            }
            if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 2)
            }
            if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                floatOutput = format.getInteger(MediaFormat.KEY_PCM_ENCODING) ==
                    AudioFormat.ENCODING_PCM_FLOAT
            }
        }
        releaseTrack()
        ensureTrack()
    }

    private fun ensureTrack() {
        if (track != null) return
        val channelMask = if (channels >= 2) {
            AudioFormat.CHANNEL_OUT_STEREO
        } else {
            AudioFormat.CHANNEL_OUT_MONO
        }
        val minBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        if (minBytes <= 0) return
        val created = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minBytes, sampleRate * channels * 4 / 2))
                .build()
        }.getOrNull() ?: return
        if (playing) runCatching { created.play() }
        track = created
    }

    private fun flushTrack() {
        track?.let { sink ->
            runCatching {
                sink.pause()
                sink.flush()
                if (playing) sink.play()
            }
        }
    }

    private fun releaseTrack() {
        track?.let { sink ->
            runCatching { sink.pause() }
            runCatching { sink.flush() }
            runCatching { sink.release() }
        }
        track = null
    }
}
