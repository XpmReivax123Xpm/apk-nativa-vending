package com.vending.kiosk.app.device

import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Deja a esta app como la que arranca sola con la tablet (autostart del FIRMWARE, familia ZTL).
 *
 * No es un receptor de BOOT_COMPLETED: el firmware lee `persist.sys.bootPkgName` y
 * `persist.sys.bootPkgActivity` y abre esa activity al encender, que es justo lo que Android ya no
 * permite hacer desde un receiver. Es el mismo interruptor que se configura a mano en el menu del
 * fabricante, pero puesto por la propia app: una tablet reseteada se configura sola la proxima vez
 * que alguien abra el kiosko.
 *
 * Las propiedades se escriben por reflection sobre `android.os.SystemProperties`, igual que la API
 * del fabricante (`ZtlApi.ZtlManager.setBootPackageActivity`). En Android 9+ esa clase esta en la
 * lista de APIs ocultas y la escritura puede quedar bloqueada; ahi se avisa por log y el autostart
 * se configura a mano.
 */
object Autoarranque {

    private const val CLAVE_PAQUETE = "persist.sys.bootPkgName"
    private const val CLAVE_ACTIVIDAD = "persist.sys.bootPkgActivity"
    private const val CLAVE_ESCRITORIO = "persist.ztl.desktopName"

    private const val HELPER_PACKAGE = "com.ztl.helper"
    private const val HELPER_SERVICE = "com.ztl.helper.ZTLHelperService"

    /**
     * Idempotente: si ya apunta a esta app, no escribe nada.
     *
     * @return lo que quedo en `persist.sys.bootPkgName` despues del intento, para dejarlo en el log.
     */
    fun asegurar(contexto: Context, actividad: String): String? {
        val antes = leerPropiedad(CLAVE_PAQUETE)

        if (antes != contexto.packageName) {
            escribir(CLAVE_PAQUETE, contexto.packageName)
            escribir(CLAVE_ACTIVIDAD, actividad)
        }

        val quedado = leerPropiedad(CLAVE_PAQUETE)

        Registro.i(
            "autostart: antes=${antes ?: "sin valor"}, " +
                "actividad=${leerPropiedad(CLAVE_ACTIVIDAD) ?: "sin valor"}, despues=${quedado ?: "sin valor"}"
        )

        if (quedado != contexto.packageName) {
            Registro.w("autostart: el firmware no dejo escribir $CLAVE_PAQUETE")
        }

        return quedado
    }

    fun leerPropiedad(clave: String): String? = runCatching {
        val sistema = Class.forName("android.os.SystemProperties")
        val obtener = sistema.getMethod("get", String::class.java, String::class.java)

        (obtener.invoke(null, clave, "") as? String)?.trim()
    }.getOrElse {
        Registro.w("autostart: no se pudo leer $clave: ${it.message}")

        null
    }

    private fun escribir(clave: String, valor: String) {
        runCatching {
            val sistema = Class.forName("android.os.SystemProperties")
            val fijar = sistema.getMethod("set", String::class.java, String::class.java)

            fijar.invoke(null, clave, valor)
        }.onFailure {
            Registro.w("autostart: no se pudo escribir $clave: ${it.message}")
        }
    }

    /**
     * Deja la app como ESCRITORIO del equipo, que es el unico arranque que Android garantiza: al
     * encender, el sistema abre siempre la pantalla de inicio. Es la via documentada del fabricante
     * (`ZtlManager.setLauncher`): se le pide al servicio de sistema y ademas queda anotado en
     * `persist.ztl.desktopName`.
     *
     * Consecuencia buscada en un kiosko: el boton HOME y el arranque llevan a la app. Por eso "Salir"
     * tiene que abrir el otro escritorio por nombre, no con ACTION_HOME (ver MainActivity).
     */
    fun dejarComoEscritorio(contexto: Context, actividad: String): Boolean {
        val pedido = runCatching {
            contexto.startService(
                Intent().apply {
                    component = ComponentName(HELPER_PACKAGE, HELPER_SERVICE)
                    putExtra("cmd", "setLauncher")
                    putExtra("package", contexto.packageName)
                    putExtra("activity", actividad)
                }
            )
        }

        escribir(CLAVE_ESCRITORIO, contexto.packageName)

        Registro.i(
            "escritorio: pedido=${pedido.isSuccess}, " +
                "desktopName=${leerPropiedad(CLAVE_ESCRITORIO) ?: "sin valor"}"
        )

        return pedido.isSuccess
    }
}
