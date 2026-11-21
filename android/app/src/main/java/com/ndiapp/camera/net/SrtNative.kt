package com.ndiapp.camera.net

import android.util.Log

/**
 * Enlace JNI con libsrt (SRT SDK oficial de Haivision), compilada desde
 * https://github.com/Haivision/srt y enlazada estáticamente en libsrt_app.so.
 *
 * Todas las llamadas nativas son bloqueantes. Nunca llamarlas desde el hilo
 * principal: [nativeAccept], [nativeSend] y [nativeRecv] esperan hasta su
 * timeout.
 */
object SrtNative {

    private const val TAG = "SrtNative"

    /** Handle inválido devuelto por las funciones nativas. */
    const val INVALID_HANDLE: Long = -1L

    private val libraryLoaded: Boolean = runCatching { System.loadLibrary("srt_app") }
            .onFailure { Log.e(TAG, "No se pudo cargar libsrt_app.so", it) }
            .isSuccess

    private val started: Boolean = libraryLoaded && runCatching { nativeStartup() }
            .onFailure { Log.e(TAG, "srt_startup falló", it) }
            .getOrDefault(false)

    /** True si libsrt está incluida en este build y srt_startup() funcionó. */
    val isAvailable: Boolean get() = started

    /** Versión de libsrt compilada (por ejemplo "1.5.4"). */
    fun version(): String = if (libraryLoaded) runCatching { nativeVersion() }.getOrDefault("?") else "no incluida"

    /**
     * Crea un socket SRT en modo caller (emisor) listo para [connect].
     * @return handle o [INVALID_HANDLE]
     */
    fun createCaller(latencyMs: Int = 120, passphrase: String? = null): Long =
            if (started) nativeCallerCreate(latencyMs, passphrase) else INVALID_HANDLE

    /**
     * Crea un socket SRT en modo listener (receptor) ya enlazado al puerto.
     * @return handle o [INVALID_HANDLE]
     */
    fun createListener(port: Int, latencyMs: Int = 120, passphrase: String? = null): Long =
            if (started) nativeListenerCreate(port, latencyMs, passphrase) else INVALID_HANDLE

    fun connect(handle: Long, host: String, port: Int): Boolean =
            handle != INVALID_HANDLE && nativeConnect(handle, host, port) == 0

    /** Acepta una conexión entrante en un listener. Devuelve un handle nuevo. */
    fun accept(listener: Long, timeoutMs: Int = 1000): Long =
            if (listener == INVALID_HANDLE) INVALID_HANDLE else nativeAccept(listener, timeoutMs)

    /** Envía un mensaje. Devuelve los bytes enviados o -1. */
    fun send(handle: Long, data: ByteArray, offset: Int = 0, length: Int = data.size,
             timeoutMs: Int = 1000): Int =
            if (handle == INVALID_HANDLE) -1 else nativeSend(handle, data, offset, length, timeoutMs)

    /** Recibe un mensaje. Devuelve bytes recibidos, -1 si no hay datos o hubo error. */
    fun recv(handle: Long, buffer: ByteArray, offset: Int = 0, length: Int = buffer.size,
             timeoutMs: Int = 500): Int =
            if (handle == INVALID_HANDLE) -1 else nativeRecv(handle, buffer, offset, length, timeoutMs)

    fun close(handle: Long) {
        if (handle != INVALID_HANDLE) nativeClose(handle)
    }

    fun lastError(): String = if (libraryLoaded) runCatching { nativeLastError() }.getOrDefault("?") else "libsrt no incluida"

    fun socketState(handle: Long): Int = if (handle == INVALID_HANDLE) -1 else nativeSocketState(handle)

    private external fun nativeStartup(): Boolean
    private external fun nativeCleanup()
    private external fun nativeVersion(): String
    private external fun nativeCallerCreate(latencyMs: Int, passphrase: String?): Long
    private external fun nativeListenerCreate(port: Int, latencyMs: Int, passphrase: String?): Long
    private external fun nativeConnect(handle: Long, host: String, port: Int): Int
    private external fun nativeAccept(listener: Long, timeoutMs: Int): Long
    private external fun nativeSend(handle: Long, data: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int
    private external fun nativeRecv(handle: Long, buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int
    private external fun nativeClose(handle: Long)
    private external fun nativeLastError(): String
    private external fun nativeSocketState(handle: Long): Int
}
