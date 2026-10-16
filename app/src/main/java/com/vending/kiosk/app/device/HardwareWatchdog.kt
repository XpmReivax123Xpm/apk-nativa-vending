package com.vending.kiosk.app.device

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Watchdog de HARDWARE del equipo (el de la placa, no un temporizador de la app).
 *
 * Se alimenta llamando al servicio del fabricante (`com.ztl.helper.ZTLHelperService` con
 * `cmd=appWatchDog`): al primer alimento el sistema DEJA de alimentarlo por su cuenta y pasa a depender
 * de esta app, asi que si el kiosko se cuelga o lo matan, nadie manda el alimento y la placa reinicia la
 * tablet sola (~200 segundos). El fabricante pide alimentarlo cada 20 segundos.
 *
 * Dos consecuencias que no hay que perder de vista:
 * - Desde que se alimenta una vez, el equipo depende de que ESTA app este corriendo: cerrarla (o
 *   desinstalarla) termina en reinicio. Por eso el alimento arranca con la app y solo en equipos que
 *   tengan el servicio del fabricante (`isSupported`); en cualquier otro no se toca nada.
 * - Ademas del software, el watchdog necesita el hardware: en la placa hay que sacar el jumper que lo
 *   habilita (si esta puesto, la placa reinicia por su cuenta o el watchdog queda deshabilitado).
 */
object HardwareWatchdog {

    private const val TAG = "HardwareWatchdog"

    private const val HELPER_PACKAGE = "com.ztl.helper"
    private const val HELPER_SERVICE = "com.ztl.helper.ZTLHelperService"
    private const val COMMAND_EXTRA = "cmd"
    private const val COMMAND_FEED = "appWatchDog"

    /** Cadencia que pide el fabricante (el reinicio ocurre a los ~200 s sin alimento). */
    private const val FEED_INTERVAL_MS = 20_000L

    @Volatile private var feeding = false

    /** El equipo tiene el servicio del fabricante que alimenta el watchdog de la placa. */
    fun isSupported(context: Context): Boolean = resolveHelper(context) != null

    /**
     * Empieza a alimentar el watchdog mientras este proceso viva. Idempotente: llamarlo de nuevo no abre
     * otro hilo. Si el equipo no tiene el servicio del fabricante, no hace nada y lo deja en el log.
     */
    fun start(context: Context) {
        if (feeding) return
        synchronized(this) {
            if (feeding) return
            feeding = true
        }

        val applicationContext = context.applicationContext
        if (!isSupported(applicationContext)) {
            Log.w(TAG, "El equipo no tiene $HELPER_PACKAGE/$HELPER_SERVICE: no hay watchdog de placa que alimentar")
            return
        }

        Log.i(TAG, "Alimentando el watchdog de la placa cada ${FEED_INTERVAL_MS / 1000} s")
        // Hilo propio y permanente a proposito: el alimento tiene que seguir aunque la UI se cuelgue (es
        // justo el caso que el watchdog tiene que detectar).
        Thread {
            while (true) {
                feed(applicationContext)
                try {
                    Thread.sleep(FEED_INTERVAL_MS)
                } catch (ex: InterruptedException) {
                    Log.w(TAG, "Se interrumpio el alimento del watchdog: ${ex.message}")
                    return@Thread
                }
            }
        }.apply {
            name = "hardware-watchdog"
            isDaemon = true
            start()
        }
    }

    private fun feed(context: Context) {
        try {
            context.startService(watchdogIntent())
        } catch (ex: Exception) {
            // En Android 8+ un servicio en segundo plano puede rechazarse: se registra y se sigue, que el
            // siguiente alimento lo vuelve a intentar.
            Log.w(TAG, "No se pudo alimentar el watchdog: ${ex.message ?: "sin detalle"}")
        }
    }

    private fun watchdogIntent(): Intent = Intent().apply {
        component = ComponentName(HELPER_PACKAGE, HELPER_SERVICE)
        putExtra(COMMAND_EXTRA, COMMAND_FEED)
    }

    @Suppress("DEPRECATION")
    private fun resolveHelper(context: Context): ComponentName? {
        val resolved = context.packageManager.resolveService(watchdogIntent(), 0) ?: return null
        return resolved.serviceInfo?.let { ComponentName(it.packageName, it.name) }
    }
}
