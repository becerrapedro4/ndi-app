package com.ndiapp.camera.net

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException

/**
 * DataSource de ExoPlayer que lee MPEG-TS de un socket SRT.
 *
 * Uso:
 *   val player = ExoPlayer.Builder(context).build()
 *   player.setMediaItem(
 *       MediaItem.Builder()
 *           .setUri("srt://192.168.1.50:9000?latency=120")
 *           .setMimeType(MimeTypes.VIDEO_MP2T)
 *           .build()
 *   )
 *   player.setMediaSource(ProgressiveMediaSource.Factory(SrtDataSource.Factory()).createMediaSource(item))
 *
 * ExoPlayer interpreta un read() == 0 como fin de stream, así que [read] bloquea
 * hasta tener al menos un byte o agotar el timeout.
 */
@OptIn(UnstableApi::class)
class SrtDataSource : BaseDataSource(/* isNetwork = */ true) {

    /** Fabrica para ProgressiveMediaSource. */
    @OptIn(UnstableApi::class)
    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = SrtDataSource()
    }

    private var socket: Long = SrtNative.INVALID_HANDLE
    private var currentUri: Uri? = null
    private val buffer = ByteArray(READ_CHUNK)
    private var buffered = 0
    private var bufferedOffset = 0
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        Assertions.checkState(!opened)
        transferInitializing(dataSpec)

        val parsed = parse(dataSpec.uri)
        if (!SrtNative.isAvailable) {
            throw IOException("libsrt no incluida en este build")
        }

        socket = SrtNative.createCaller(parsed.latencyMs, parsed.passphrase)
        if (socket == SrtNative.INVALID_HANDLE) {
            throw IOException("No se pudo crear el socket SRT: ${SrtNative.lastError()}")
        }
        if (!SrtNative.connect(socket, parsed.host, parsed.port)) {
            val detail = SrtNative.lastError()
            SrtNative.close(socket)
            socket = SrtNative.INVALID_HANDLE
            throw IOException("No se pudo conectar a srt://${parsed.host}:${parsed.port}: $detail")
        }

        currentUri = dataSpec.uri
        opened = true
        buffered = 0
        bufferedOffset = 0
        transferStarted(dataSpec)
        Log.i(LOG_TAG, "SRT conectado a ${parsed.host}:${parsed.port}")
        // Stream en vivo de longitud desconocida.
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0

        if (buffered > 0) {
            val count = minOf(buffered, length)
            System.arraycopy(buffer, bufferedOffset, target, offset, count)
            bufferedOffset += count
            buffered -= count
            bytesTransferred(count)
            return count
        }

        if (!opened) return C.RESULT_END_OF_INPUT

        val deadline = SystemClock.elapsedRealtime() + READ_TIMEOUT_MS
        while (true) {
            val received = SrtNative.recv(socket, buffer, 0, minOf(buffer.size, length),
                    RECV_SLICE_MS)
            if (received > 0) {
                bufferedOffset = 0
                buffered = received
                return read(target, offset, length)
            }
            if (!opened) return C.RESULT_END_OF_INPUT
            if (SystemClock.elapsedRealtime() > deadline) {
                Log.w(LOG_TAG, "Sin datos SRT tras $READ_TIMEOUT_MS ms: ${SrtNative.lastError()}")
                return C.RESULT_END_OF_INPUT
            }
        }
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        val wasOpened = opened
        opened = false
        if (socket != SrtNative.INVALID_HANDLE) {
            SrtNative.close(socket)
            socket = SrtNative.INVALID_HANDLE
        }
        currentUri = null
        buffered = 0
        bufferedOffset = 0
        if (wasOpened) transferEnded()
    }

    // ------------------------------------------------------------- URL SRT ----

    internal data class SrtAddress(
        val host: String,
        val port: Int,
        val latencyMs: Int,
        val passphrase: String?,
    )

    internal companion object {
        private const val LOG_TAG = "SrtDataSource"
        private const val READ_CHUNK = 188 * 7
        private const val RECV_SLICE_MS = 300
        private const val READ_TIMEOUT_MS = 15_000L

        /** Acepta srt://host:port?latency=120&passphrase=xxx */
        fun parse(uri: Uri): SrtAddress {
            val host = uri.host ?: throw IOException("URL SRT sin host: $uri")
            val port = if (uri.port > 0) uri.port else DEFAULT_PORT
            val latency = uri.getQueryParameter("latency")?.toIntOrNull() ?: DEFAULT_LATENCY_MS
            val passphrase = uri.getQueryParameter("passphrase")?.takeIf { it.isNotEmpty() }
            return SrtAddress(host, port, latency, passphrase)
        }

        private const val DEFAULT_PORT = 9000
        private const val DEFAULT_LATENCY_MS = 120
    }
}
