package com.ndiapp.camera.mixer

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * API HTTP mínima del mezclador.
 *
 * Abre un puerto en la red local para que otro equipo (o un script) haga lo
 * mismo que los botones: CUT/FADE, capas, audio, keyer y grabación. No hay
 * estado propio: cada petición se resuelve con [onCommand], que trabaja sobre el
 * mezclador ya abierto, y la respuesta es JSON.
 *
 * Deliberadamente es un servidor de una conexión por vez y sin TLS: vive sólo
 * mientras la pestaña Mezclador está abierta y se pensó para la LAN de un
 * estudio, no para exponerse a internet.
 */
class MixerApi(private val port: Int = DEFAULT_PORT) {

    companion object {
        const val DEFAULT_PORT = 8095

        private const val TAG = "MixerApi"
        private const val CRLF = "\r\n"
        private const val UNKNOWN =
            "{\"ok\":false,\"error\":\"ruta desconocida\"}"
    }

    /** Devuelve el cuerpo JSON de la respuesta para [path] y sus parámetros. */
    var onCommand: ((path: String, params: Map<String, String>) -> String)? = null

    private val active = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var thread: Thread? = null

    val isRunning: Boolean
        get() = active.get()

    fun start(): Boolean {
        if (active.get()) return true
        val socket = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
            }
        }.onFailure { Log.w(TAG, "No se pudo abrir el puerto $port", it) }
            .getOrNull() ?: return false
        server = socket
        active.set(true)
        val worker = Thread { accept(socket) }
        worker.name = "mixer-api"
        worker.isDaemon = true
        thread = worker
        worker.start()
        return true
    }

    fun stop() {
        active.set(false)
        runCatching { server?.close() }
        server = null
        thread?.let { worker ->
            worker.interrupt()
            runCatching { worker.join(500) }
        }
        thread = null
    }

    private fun accept(socket: ServerSocket) {
        while (active.get()) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            runCatching { handle(client) }
                .onFailure { Log.w(TAG, "Petición descartada", it) }
            runCatching { client.close() }
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = 3000
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
        val request = reader.readLine() ?: return
        // Se leen y descartan las cabeceras: el cuerpo no se usa.
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }
        val parts = request.split(' ')
        if (parts.size < 2) return
        val method = parts[0].uppercase()
        val target = parts[1]
        val path = target.substringBefore('?').trimEnd('/').ifEmpty { "/" }
        val params = parseQuery(target.substringAfter('?', ""))

        val body = if (method == "OPTIONS") "" else {
            onCommand?.invoke(path, params) ?: UNKNOWN
        }
        respond(client, body)
    }

    private fun respond(client: Socket, body: String) {
        val payload = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 200 OK").append(CRLF)
            append("Content-Type: application/json; charset=utf-8").append(CRLF)
            append("Access-Control-Allow-Origin: *").append(CRLF)
            append("Access-Control-Allow-Headers: *").append(CRLF)
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS").append(CRLF)
            append("Cache-Control: no-store").append(CRLF)
            append("Content-Length: ").append(payload.size).append(CRLF)
            append("Connection: close").append(CRLF)
            append(CRLF)
        }
        val out = client.getOutputStream()
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(payload)
        out.flush()
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isBlank()) continue
            val key = pair.substringBefore('=', "").trim()
            if (key.isEmpty()) continue
            val value = pair.substringAfter('=', "")
            result[key] = runCatching {
                URLDecoder.decode(value, "UTF-8")
            }.getOrDefault(value)
        }
        return result
    }
}
