package com.vending.kiosk.app.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vending.kiosk.app.MainActivity

/**
 * Devuelve la tablet al kiosco sola despues de un corte de luz.
 *
 * Es el camino propio de la app y no depende de que el firmware respete `persist.sys.bootPkg*`: en
 * esta generacion de equipos (Android 7.1) un receptor todavia puede abrir una activity desde el
 * arranque. A partir de Android 10 esa misma llamada queda bloqueada y habria que volver al
 * mecanismo del fabricante o a un servicio en primer plano.
 *
 * El arranque con la app recien instalada no llega hasta que alguien la abre una vez: Android no
 * entrega `BOOT_COMPLETED` a una app que nunca corrio o que fue detenida a la fuerza.
 */
class InicioAutomaticoReceiver : BroadcastReceiver() {

    override fun onReceive(contexto: Context, intencion: Intent?) {
        Registro.i("arranque del sistema (${intencion?.action}): se abre el kiosco")

        contexto.startActivity(
            Intent(contexto, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
