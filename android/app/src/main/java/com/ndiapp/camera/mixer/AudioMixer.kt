package com.ndiapp.camera.mixer

import java.util.Arrays
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.locks.LockSupport
import kotlin.math.abs

/**
 * Mezclador de audio de dos entradas: el micrófono (A) y el audio recibido por
 * NDI (B).
 *
 * Las dos entradas se normalizan a PCM float intercalado, estéreo y 48 kHz, y
 * un reloj propio saca bloques de 20 ms: si una entrada se atrasa o desaparece,
 * la mezcla sigue saliendo con silencio en su lugar, así el audio de NDI no se
 * corta. Cada canal tiene ganancia y mute; los medidores muestran el pico de
 * cada entrada y el de la mezcla.
 *
 * El resultado es continuo: se entrega siempre, aunque no haya señal.
 */
class AudioMixer {

    companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2

        /** 20 ms por bloque: el mismo tamaño que entrega el micrófono. */
        const val FRAME_SAMPLES = 960

        /** Bloques en cola por entrada (~160 ms): más sería audio retrasado. */
        private const val QUEUE_BLOCKS = 8

        /** Los medidores se publican 10 veces por segundo, no 50. */
        private const val LEVEL_INTERVAL_NS = 100_000_000L

        /** Liberación del medidor: pico nuevo al instante y caída suave. */
        private const val LEVEL_DECAY = 0.85f
    }

    /** Picos (0..1) de cada entrada y de la mezcla que sale por NDI. */
    data class Levels(
        val micLeft: Float = 0f,
        val micRight: Float = 0f,
        val ndiLeft: Float = 0f,
        val ndiRight: Float = 0f,
        val masterLeft: Float = 0f,
        val masterRight: Float = 0f
    )

    /** Bloque mezclado listo para NDI. El búfer se reutiliza entre bloques. */
    var onMixed: ((pcm: FloatArray, samples: Int, channels: Int, sampleRate: Int) -> Unit)? = null

    /** Niveles para la interfaz; se llama desde el hilo del mezclador. */
    var onLevels: ((Levels) -> Unit)? = null

    /** Ganancia lineal de cada canal (1 = sin cambios). */
    @Volatile
    var micGain: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 4f)
        }

    @Volatile
    var ndiGain: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 4f)
        }

    @Volatile
    var micOpen: Boolean = true

    @Volatile
    var ndiOpen: Boolean = true

    @Volatile
    private var running = false

    private var thread: Thread? = null

    /**
     * Una entrada normalizada: cola de bloques estéreo a 48 kHz, remanente del
     * bloque anterior y fase del remuestreo cuando la fuente no trae 48 kHz.
     */
    private inner class Line {
        private val queue = ArrayBlockingQueue<FloatArray>(QUEUE_BLOCKS)
        private var carry = FloatArray(0)
        private var carryPos = 0
        private var phase = 0.0

        fun submit(pcm: FloatArray, samples: Int, channels: Int, sampleRate: Int) {
            if (samples <= 0 || channels <= 0 || sampleRate <= 0) return
            val stereo = if (sampleRate == SAMPLE_RATE) {
                toStereo(pcm, samples, channels)
            } else {
                resample(pcm, samples, channels, sampleRate)
            }
            if (stereo.isEmpty()) return
            // Si el consumidor se atrasó, se tira el bloque más viejo: la mezcla
            // no puede acumular audio de hace un segundo.
            if (!queue.offer(stereo)) {
                queue.poll()
                queue.offer(stereo)
            }
        }

        /**
         * Rellena [out] con el siguiente bloque de 20 ms. Lo que falte queda en
         * silencio: el reloj no espera a ninguna entrada.
         */
        fun pull(out: FloatArray) {
            var written = 0
            while (written < out.size) {
                if (carryPos >= carry.size) {
                    carry = queue.poll() ?: break
                    carryPos = 0
                }
                val count = minOf(out.size - written, carry.size - carryPos)
                System.arraycopy(carry, carryPos, out, written, count)
                carryPos += count
                written += count
            }
        }

        private fun toStereo(pcm: FloatArray, samples: Int, channels: Int): FloatArray {
            val out = FloatArray(samples * CHANNELS)
            var source = 0
            var target = 0
            for (index in 0 until samples) {
                val left = pcm[source]
                out[target] = left
                out[target + 1] = if (channels > 1) pcm[source + 1] else left
                source += channels
                target += CHANNELS
            }
            return out
        }

        /**
         * Remuestreo por vecino más cercano con fase entre bloques. NDI entrega
         * audio a 48 kHz, así que esto sólo cubre fuentes raras; no vale la pena
         * un interpolador para un caso que casi no ocurre.
         */
        private fun resample(
            pcm: FloatArray,
            samples: Int,
            channels: Int,
            sampleRate: Int
        ): FloatArray {
            val step = sampleRate.toDouble() / SAMPLE_RATE
            if (step <= 0.0) return FloatArray(0)
            val out = FloatArray(((samples / step).toInt() + 2) * CHANNELS)
            var position = phase
            var written = 0
            while (position < samples) {
                val index = position.toInt()
                val base = index * channels
                out[written] = pcm[base]
                out[written + 1] = if (channels > 1) pcm[base + 1] else pcm[base]
                written += CHANNELS
                position += step
            }
            phase = (position - samples).coerceAtLeast(0.0)
            return if (written == out.size) out else out.copyOf(written)
        }
    }

    private val mic = Line()
    private val ndi = Line()

    private var levelMicLeft = 0f
    private var levelMicRight = 0f
    private var levelNdiLeft = 0f
    private var levelNdiRight = 0f
    private var levelMasterLeft = 0f
    private var levelMasterRight = 0f

    val isRunning: Boolean
        get() = running

    fun start() {
        if (running) return
        running = true
        val worker = Thread { mixLoop() }
        worker.name = "mixer-audio"
        worker.priority = Thread.MAX_PRIORITY
        worker.isDaemon = true
        thread = worker
        worker.start()
    }

    /** PCM del micrófono tal como sale de AudioCapture. */
    fun submitMic(pcm: FloatArray, samples: Int, channels: Int, sampleRate: Int) {
        if (!running) return
        mic.submit(pcm, samples, channels, sampleRate)
    }

    /** PCM recibido de la fuente NDI. */
    fun submitNdi(pcm: FloatArray, samples: Int, channels: Int, sampleRate: Int) {
        if (!running) return
        ndi.submit(pcm, samples, channels, sampleRate)
    }

    fun stop() {
        if (!running && thread == null) return
        running = false
        thread?.let { worker ->
            worker.interrupt()
            runCatching { worker.join(600) }
        }
        thread = null
        levelMicLeft = 0f
        levelMicRight = 0f
        levelNdiLeft = 0f
        levelNdiRight = 0f
        levelMasterLeft = 0f
        levelMasterRight = 0f
    }

    // ------------------------------------------------------------- reloj --**

    private fun mixLoop() {
        val micFrame = FloatArray(FRAME_SAMPLES * CHANNELS)
        val ndiFrame = FloatArray(FRAME_SAMPLES * CHANNELS)
        val mixed = FloatArray(FRAME_SAMPLES * CHANNELS)
        val tick = FRAME_SAMPLES * 1_000_000_000L / SAMPLE_RATE
        var next = System.nanoTime()
        var lastLevels = 0L

        while (running) {
            val now = System.nanoTime()
            if (now < next) {
                LockSupport.parkNanos(next - now)
                continue
            }
            next += tick
            // Si el hilo se atrasó, se retoma el ritmo en vez de acumular retraso.
            if (next < System.nanoTime()) next = System.nanoTime()

            Arrays.fill(micFrame, 0f)
            Arrays.fill(ndiFrame, 0f)
            mic.pull(micFrame)
            ndi.pull(ndiFrame)

            var micPeakLeft = 0f
            var micPeakRight = 0f
            var ndiPeakLeft = 0f
            var ndiPeakRight = 0f
            var masterPeakLeft = 0f
            var masterPeakRight = 0f

            val micLevel = if (micOpen) micGain else 0f
            val ndiLevel = if (ndiOpen) ndiGain else 0f
            var index = 0
            while (index < mixed.size) {
                val micLeftSample = micFrame[index]
                val micRightSample = micFrame[index + 1]
                val ndiLeftSample = ndiFrame[index]
                val ndiRightSample = ndiFrame[index + 1]

                micPeakLeft = maxOf(micPeakLeft, abs(micLeftSample))
                micPeakRight = maxOf(micPeakRight, abs(micRightSample))
                ndiPeakLeft = maxOf(ndiPeakLeft, abs(ndiLeftSample))
                ndiPeakRight = maxOf(ndiPeakRight, abs(ndiRightSample))

                val left = (micLeftSample * micLevel + ndiLeftSample * ndiLevel)
                    .coerceIn(-1f, 1f)
                val right = (micRightSample * micLevel + ndiRightSample * ndiLevel)
                    .coerceIn(-1f, 1f)
                mixed[index] = left
                mixed[index + 1] = right

                masterPeakLeft = maxOf(masterPeakLeft, abs(left))
                masterPeakRight = maxOf(masterPeakRight, abs(right))
                index += CHANNELS
            }

            levelMicLeft = maxOf(micPeakLeft, levelMicLeft * LEVEL_DECAY)
            levelMicRight = maxOf(micPeakRight, levelMicRight * LEVEL_DECAY)
            levelNdiLeft = maxOf(ndiPeakLeft, levelNdiLeft * LEVEL_DECAY)
            levelNdiRight = maxOf(ndiPeakRight, levelNdiRight * LEVEL_DECAY)
            levelMasterLeft = maxOf(masterPeakLeft, levelMasterLeft * LEVEL_DECAY)
            levelMasterRight = maxOf(masterPeakRight, levelMasterRight * LEVEL_DECAY)

            onMixed?.invoke(mixed, FRAME_SAMPLES, CHANNELS, SAMPLE_RATE)

            val timestamp = System.nanoTime()
            if (timestamp - lastLevels >= LEVEL_INTERVAL_NS) {
                lastLevels = timestamp
                onLevels?.invoke(
                    Levels(
                        micLeft = levelMicLeft,
                        micRight = levelMicRight,
                        ndiLeft = levelNdiLeft,
                        ndiRight = levelNdiRight,
                        masterLeft = levelMasterLeft,
                        masterRight = levelMasterRight
                    )
                )
            }
        }
    }
}
