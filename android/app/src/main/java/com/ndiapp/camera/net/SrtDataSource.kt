package com.ndiapp.camera.net

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException

/**
 * Fuente de datos para ExoPlayer sobre un socket SRT: el reproductor pide bytes
 * y acá se leen del socket. El flujo recibido es MPEG-TS, que ExoPlayer ya sabe
 * demultiplexar y decodificar con su módulo de transporte.
 */
class SrtDataSource : BaseDataSource(true) {

    private var handle = 0L
    private var currentUri: Uri? = null
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        if (!SrtNative.available) {
            throw IOException("SRT no disponible en este dispositivo")
        }
        val socket = SrtNative.connect(dataSpec.uri.toString(), 8000)
        if (socket == 0L) {
            throw IOException("SRT: ${SrtNative.lastError()}")
        }
        handle = socket
        currentUri = dataSpec.uri
        opened = true
        transferStarted(dataSpec)
        // Un vivo no declara longitud.
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        // La lectura espera en tramos cortos: un 0 sólo significa que todavía no
        // llegaron datos, así que se sigue esperando al emisor (o a que se cancele).
        while (true) {
            val read = SrtNative.read(handle, buffer, offset, length)
            if (read > 0) {
                bytesTransferred(read)
                return read
            }
            if (read < 0) throw IOException("SRT: ${SrtNative.lastError()}")
        }
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        if (!opened) return
        opened = false
        SrtNative.close(handle)
        handle = 0L
        currentUri = null
        transferEnded()
    }

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = SrtDataSource()
    }
}
