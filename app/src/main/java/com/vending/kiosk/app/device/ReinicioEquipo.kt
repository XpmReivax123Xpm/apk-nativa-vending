package com.vending.kiosk.app.device

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Reinicio de la tablet por el servicio del fabricante (`com.ztl.helper`, `cmd=reboot`).
 *
 * Es la misma via que alimenta el watchdog de la placa: la app pide y el servicio de sistema ejecuta,
 * porque reiniciar el equipo con `PowerManager` exige un permiso de sistema que una APK no tiene. Se
 * pasa un margen en segundos para que la pantalla alcance a avisar antes del corte.
 *
 * Sin el servicio del fabricante no hace nada y lo deja en el log.
 */
object ReinicioEquipo {

    private const val TAG = "ReinicioEquipo"

    private const val HELPER_PACKAGE = "com.ztl.helper"
    private const val HELPER_SERVICE = "com.ztl.helper.ZTLHelperService"

    /** @return `true` si el servicio del fabricante acepto la orden. */
    fun reiniciar(contexto: Context, esperaSegundos: Int = 3): Boolean = try {
        val orden = Intent().apply {
            component = ComponentName(HELPER_PACKAGE, HELPER_SERVICE)
            putExtra("cmd", "reboot")
            putExtra("delay", esperaSegundos)
        }

        val atendida = contexto.startService(orden) != null

        if (atendida) {
            Log.i(TAG, "reinicio pedido al fabricante en $esperaSegundos s")
        } else {
            Log.w(TAG, "el servicio del fabricante no acepto el reinicio")
        }

        atendida
    } catch (excepcion: Exception) {
        Log.w(TAG, "no se pudo pedir el reinicio: ${excepcion.message}")

        false
    }
}
