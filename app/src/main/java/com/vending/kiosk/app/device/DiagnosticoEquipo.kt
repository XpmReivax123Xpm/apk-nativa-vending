package com.vending.kiosk.app.device

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Volcado de diagnostico de una sola vez al log de campo: propiedades del sistema y apps del
 * fabricante. En un equipo sin adb es la unica forma de ver como esta configurado el autostart, que
 * desktop trae el firmware y si la escritura de `persist.sys.bootPkg*` realmente quedo.
 *
 * Corre en su propio hilo y no toca la UI: son dos comandos y una consulta, y el arranque del kiosko
 * no puede esperarlos.
 */
object DiagnosticoEquipo {

    private val CLAVES_PROPIEDAD = listOf(
        "boot",
        "launch",
        "desktop",
        "ztl",
        "autostart",
        "kiosk",
        "persist.sys."
    )

    private val CLAVES_APP = listOf("ztl", "kiosk", "launcher", "desktop", "home")

    fun volcar(contexto: Context) {
        Thread({
            propiedades()
            escritorio(contexto)
            paquetes(contexto)
            Registro.i("diagnostico: fin")
        }, "diagnostico").start()
    }

    /** `getprop` sin argumentos lista todo: se filtra por las claves que importan. */
    private fun propiedades() {
        val salida = ejecutar("getprop") ?: return

        salida.lineSequence()
            .filter { linea -> CLAVES_PROPIEDAD.any { linea.lowercase().contains(it) } }
            .forEach { linea -> Registro.i("prop $linea") }
    }

    private fun escritorio(contexto: Context) {
        val intencion = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val gestor = contexto.packageManager

        gestor.queryIntentActivities(intencion, 0).forEach {
            Registro.i("home candidato: ${it.activityInfo.packageName}/${it.activityInfo.name}")
        }

        val porDefecto = gestor.resolveActivity(intencion, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo

        Registro.i(
            "home por defecto: " +
                (porDefecto?.let { "${it.packageName}/${it.name}" } ?: "sin resolver")
        )
    }

    private fun paquetes(contexto: Context) {
        contexto.packageManager.getInstalledPackages(0)
            .map { it.packageName }
            .filter { nombre -> CLAVES_APP.any { nombre.lowercase().contains(it) } }
            .sorted()
            .forEach { Registro.i("app instalada: $it") }
    }

    private fun ejecutar(vararg comando: String): String? = try {
        val proceso = ProcessBuilder(*comando).redirectErrorStream(true).start()
        val texto = proceso.inputStream.bufferedReader().use { it.readText() }

        proceso.waitFor()

        texto
    } catch (excepcion: Exception) {
        Registro.w("diagnostico: no se pudo ejecutar ${comando.joinToString(" ")}: ${excepcion.message}")

        null
    }
}
