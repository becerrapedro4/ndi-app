package com.ndiapp.camera.ndi

/**
 * Enlace JNI con libndi.so (NDI SDK para Android).
 * Todas las llamadas son bloqueantes; usar fuera del hilo principal salvo sendVideo/sendAudio.
 */
object NdiNative {

    init {
        System.loadLibrary("ndi_app")
        System.loadLibrary("ndi")
    }

    external fun isSdkAvailable(): Boolean

    external fun sdkVersion(): String

    external fun init(): Boolean

    external fun sendCreate(name: String): Long

    external fun sendVideo(handle: Long, rgba: ByteArray, width: Int, height: Int, fpsNum: Int, fpsDen: Int)

    external fun sendAudio(handle: Long, pcm: FloatArray, samples: Int, channels: Int, sampleRate: Int)

    /** Nº de receptores conectados a la fuente (tally: alguien está usando la señal). */
    external fun sendConnections(handle: Long): Int

    external fun sendDestroy(handle: Long)

    external fun findCreate(): Long

    /** Devuelve entradas "nombre\turl" de las fuentes visibles en la red. */
    external fun findSources(handle: Long, timeoutMs: Int): Array<String>?

    external fun findDestroy(handle: Long)

    external fun recvCreate(name: String, url: String, lowBandwidth: Boolean): Long

    /**
     * Espera un frame. Devuelve [tipo, ancho, alto, samples, canales, sampleRate]
     * donde tipo es 1 = video, 2 = audio, 0 = sin datos.
     */
    external fun recvPoll(handle: Long, rgba: ByteArray, pcm: FloatArray, timeoutMs: Int): IntArray?

    external fun recvDestroy(handle: Long)
}
