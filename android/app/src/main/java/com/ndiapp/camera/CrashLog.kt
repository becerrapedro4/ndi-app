package com.ndiapp.camera

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Último fallo no controlado, guardado en un archivo para poder leerlo después.
 *
 * La app se cierra sin dejar rastro cuando el proceso muere: aquí se anota la
 * excepción y el hilo para que el arranque siguiente pueda mostrar la causa.
 */
object CrashLog {

    private const val FILE = "ultimo-error.txt"

    /** Anota cualquier fallo no controlado y sigue con el manejador anterior. */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val text = buildString {
                    append(stamp)
                    append(" · hilo ")
                    append(thread.name)
                    append('\n')
                    append(Log.getStackTraceString(error))
                }
                File(app.filesDir, FILE).writeText(text)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Texto del último fallo (y lo borra, para no mostrarlo siempre). */
    fun take(context: Context): String? {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return null
        val text = runCatching { file.readText() }.getOrNull()
        runCatching { file.delete() }
        return text?.takeIf { it.isNotBlank() }
    }

    /** Resumen de una línea: tipo de excepción, mensaje y primer marco de la app. */
    fun summary(text: String): String {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val exception = lines.firstOrNull { it.contains("Exception") || it.contains("Error") }
            ?: lines.lastOrNull()
            ?: "Error desconocido"
        val frame = lines.firstOrNull { it.contains("com.ndiapp.camera") }
            ?: lines.getOrNull(1)
            ?: ""
        return "$exception${if (frame.isEmpty()) "" else " · $frame"}"
            .replace("\n", " ")
            .take(220)
    }
}
