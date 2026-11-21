package com.ndiapp.camera.net

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log

/**
 * Codificador AAC-LC para la salida SRT.
 *
 * Recibe el mismo PCM float intercalado que ya usa la fuente NDI (48 kHz
 * estéreo), lo convierte a 16 bits y lo entrega al codificador del sistema.
 * Cada trama sale con su cabecera ADTS, que es lo que espera el MPEG-TS para
 * el tipo de flujo 0x0F (AAC), y con el PTS en microsegundos que fija el
 * llamante para que el audio quede alineado con el vídeo.
 */
class AacEncoder(
    private val sampleRate: Int,
    private val channels: Int,
    private val startUs: Long
) {

    private companion object {
        const val TAG = "AacEncoder"
        const val BITRATE = 128_000
        const val SAMPLES_PER_FRAME = 1024
    }

    /** Trama ADTS y su PTS en microsegundos. */
    var onFrame: ((ByteArray, Long) -> Unit)? = null

    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val info = MediaCodec.BufferInfo()
    private val pcm16 = ByteArray(8192 * channels * 2)

    @Volatile
    private var running = true

    private var submittedSamples = 0L

    init {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            sampleRate,
            channels
        )
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        format.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    /** Encola un bloque de PCM float; [samples] es por canal. */
    @Synchronized
    fun submit(pcm: FloatArray, samples: Int) {
        if (!running || samples <= 0) return
        // Un bloque se pierde si el codificador ya se cerró, pero el hilo del
        // micrófono no puede caerse: NDI y el vídeo de SRT siguen en el aire.
        try {
            val floats = samples * channels
            if (floats > pcm.size) return
            val bytes = floats * 2
            if (bytes > pcm16.size) return
            var index = 0
            for (sample in 0 until floats) {
                val value = (pcm[sample].coerceIn(-1f, 1f) * 32767f).toInt()
                pcm16[index++] = (value and 0xFF).toByte()
                pcm16[index++] = ((value shr 8) and 0xFF).toByte()
            }
            // El codificador puede tardar en soltar buffers: se vacía antes de
            // pedir entrada para no atascar la cola de salida.
            drain()
            val input = codec.dequeueInputBuffer(0)
            if (input < 0) {
                Log.w(TAG, "Sin búfer de entrada: bloque de audio descartado")
                return
            }
            val buffer = codec.getInputBuffer(input) ?: return
            buffer.clear()
            buffer.put(pcm16, 0, bytes)
            codec.queueInputBuffer(
                input,
                0,
                bytes,
                startUs + submittedSamples * 1_000_000L / sampleRate,
                0
            )
            submittedSamples += samples
            drain()
        } catch (t: Throwable) {
            Log.w(TAG, "Bloque de audio descartado", t)
        }
    }

    @Synchronized
    fun release() {
        if (!running) return
        running = false
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    private fun drain() {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            if (index < 0) return
            val buffer = codec.getOutputBuffer(index)
            if (buffer != null && info.size > 0 &&
                info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
            ) {
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                val raw = ByteArray(info.size)
                buffer.get(raw)
                onFrame?.invoke(withAdts(raw), info.presentationTimeUs)
            }
            codec.releaseOutputBuffer(index, false)
        }
    }

    /** Trama AAC cruda -> trama ADTS (MPEG-4, AAC-LC, sin CRC). */
    private fun withAdts(frame: ByteArray): ByteArray {
        val length = frame.size + 7
        val rateIndex = when (sampleRate) {
            96000 -> 0
            88200 -> 1
            64000 -> 2
            48000 -> 3
            44100 -> 4
            32000 -> 5
            24000 -> 6
            22050 -> 7
            16000 -> 8
            12000 -> 9
            11025 -> 10
            8000 -> 11
            else -> 3
        }
        val header = ByteArray(7)
        header[0] = 0xFF.toByte()
        header[1] = 0xF1.toByte()               // MPEG-4, layer 0, sin CRC
        header[2] = (
            (0x01 shl 6) or                       // perfil AAC-LC
                (rateIndex shl 2) or
                ((channels shr 2) and 0x01)
            ).toByte()
        header[3] = (
            ((channels and 0x03) shl 6) or
                ((length shr 11) and 0x03)
            ).toByte()
        header[4] = ((length shr 3) and 0xFF).toByte()
        header[5] = (((length and 0x07) shl 5) or 0x1F).toByte()
        header[6] = 0xFC.toByte()                // buffer lleno y un solo bloque
        val adts = ByteArray(length)
        System.arraycopy(header, 0, adts, 0, 7)
        System.arraycopy(frame, 0, adts, 7, frame.size)
        return adts
    }
}
