package com.ndiapp.camera.net

import android.util.Log

/**
 * Puente con libsrt (MPL-2.0). La biblioteca se compila para arm64-v8a y
 * armeabi-v7a en el flujo de compilación; si el APK no la trae, [available] es
 * falso y la app sigue funcionando sin SRT en lugar de cerrarse.
 */
object SrtNative {

    private const val TAG = "SrtNative"

    private val loaded: Boolean = runCatching { System.loadLibrary("srt_app") }
        .onFailure { Log.w(TAG, "libsrt no está en este APK", it) }
        .isSuccess

    val available: Boolean get() = loaded

    /** Abre un srt:// (llamante o escucha, según ?mode=) y devuelve el socket. */
    fun connect(url: String, timeoutMs: Int = 8000): Long =
        if (loaded) nativeConnect(url, timeoutMs) else 0L

    /** Bloquea hasta recibir datos. Devuelve bytes, 0 sin datos o -1 con error. */
    fun read(handle: Long, buffer: ByteArray, offset: Int, length: Int): Int =
        if (loaded && handle != 0L) nativeRead(handle, buffer, offset, length) else -1

    /** Envía el bloque completo (lo parte en trozos del MTU de SRT). */
    fun write(handle: Long, buffer: ByteArray, offset: Int, length: Int): Int =
        if (loaded && handle != 0L) nativeWrite(handle, buffer, offset, length) else -1

    fun close(handle: Long) {
        if (loaded && handle != 0L) nativeClose(handle)
    }

    /** Corta una escucha en curso: el hilo que espera al emisor termina solo. */
    fun cancel() {
        if (loaded) nativeCancel()
    }

    fun lastError(): String = if (loaded) nativeLastError() else "libsrt no disponible"

    private external fun nativeConnect(url: String, timeoutMs: Int): Long
    private external fun nativeRead(handle: Long, buffer: ByteArray, offset: Int, length: Int): Int
    private external fun nativeWrite(handle: Long, buffer: ByteArray, offset: Int, length: Int): Int
    private external fun nativeClose(handle: Long)
    private external fun nativeCancel()
    private external fun nativeLastError(): String
}
