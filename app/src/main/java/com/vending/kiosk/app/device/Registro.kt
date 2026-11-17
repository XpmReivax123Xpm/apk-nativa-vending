package com.vending.kiosk.app.device

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Log de campo en archivo: en el kiosko no hay consola, y cuando la app se cierra al arrancar el
 * `logcat` se va con el proceso. Lo que se escribe aca queda en la tablet para copiarlo por USB y
 * trae, ademas de las lineas de arranque, el stack trace de cualquier excepcion no atrapada.
 *
 * Escribe siempre en almacenamiento interno y espeja en `Android/data/<paquete>/files/logs/kiosko.log`,
 * que es el que se ve por USB. El interno manda porque en un reinicio la tarjeta emulada puede tardar
 * en montarse, y es justo el momento en el que hace falta el log. Se corta solo a los 512 KB (deja el
 * anterior al lado) para que una app que se cae en bucle no llene el disco.
 */
object Registro {

    private const val TAG = "Kiosko"

    private const val CARPETA = "logs"
    private const val ARCHIVO = "kiosko.log"
    private const val MAXIMO_BYTES = 512 * 1024L

    @Volatile
    private var interno: File? = null

    @Volatile
    private var externo: File? = null

    @Volatile
    private var aplicacion: Context? = null

    /** Idempotente: se llama una sola vez, desde [com.vending.kiosk.app.AplicacionKiosko]. */
    fun iniciar(contexto: Context) {
        if (interno != null) return

        aplicacion = contexto.applicationContext
        interno = preparar(File(contexto.filesDir, CARPETA))

        val previo = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { hilo, excepcion ->
            escribir("CRASH", "hilo ${hilo.name}: ${excepcion.javaClass.name}: ${excepcion.message}")
            escribir("CRASH", excepcion.stackTraceToString())
            previo?.uncaughtException(hilo, excepcion)
        }

        escribir(
            "INICIO",
            "version ${version(contexto)} en Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
                "${Build.MANUFACTURER} ${Build.MODEL}"
        )
        escribir("INICIO", "archivo: ${archivoExterno()?.absolutePath ?: "solo interno"}")
    }

    fun i(mensaje: String) = escribir("INFO", mensaje)

    fun w(mensaje: String) = escribir("AVISO", mensaje)

    fun e(mensaje: String, excepcion: Throwable? = null) =
        escribir("ERROR", mensaje + (excepcion?.let { "\n${it.stackTraceToString()}" } ?: ""))

    private fun escribir(nivel: String, mensaje: String) {
        Log.println(
            when (nivel) {
                "ERROR", "CRASH" -> Log.ERROR
                "AVISO" -> Log.WARN
                else -> Log.INFO
            },
            TAG,
            mensaje
        )

        val linea = "${marcaDeTiempo()} $nivel $mensaje\n"

        synchronized(this) {
            interno?.let { anexar(it, linea) }

            archivoExterno()?.let { espejo ->
                if (espejo.path != interno?.path) anexar(espejo, linea)
            }
        }
    }

    private fun anexar(destino: File, linea: String) {
        try {
            FileOutputStream(destino, true).use { salida -> salida.write(linea.toByteArray()) }
        } catch (_: Exception) {
            // Escribir el log nunca puede tumbar la app: si el archivo falla, queda solo el logcat.
        }
    }

    private fun preparar(carpeta: File): File? = try {
        carpeta.mkdirs()

        val destino = File(carpeta, ARCHIVO)

        if (destino.length() > MAXIMO_BYTES) {
            val anterior = File(carpeta, "$ARCHIVO.anterior")

            anterior.delete()
            destino.renameTo(anterior)
        }

        destino
    } catch (_: Exception) {
        null
    }

    /** Se resuelve en el primer uso que funcione: al arrancar la tablet todavia puede no estar. */
    private fun archivoExterno(): File? {
        externo?.let { return it }

        val contexto = aplicacion ?: return null

        externo = try {
            contexto.getExternalFilesDir(null)?.let { preparar(File(it, CARPETA)) }
        } catch (_: Exception) {
            null
        }

        return externo
    }

    private fun marcaDeTiempo(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    @Suppress("DEPRECATION")
    private fun version(contexto: Context): String = try {
        val info = contexto.packageManager.getPackageInfo(contexto.packageName, 0)
        val instalada = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(info.lastUpdateTime))

        "${info.versionName} (${info.versionCode}), instalada $instalada"
    } catch (_: Exception) {
        "?"
    }
}
