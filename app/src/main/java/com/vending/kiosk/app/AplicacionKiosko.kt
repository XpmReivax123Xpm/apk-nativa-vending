package com.vending.kiosk.app

import android.app.Application
import com.vending.kiosk.app.device.DiagnosticoEquipo
import com.vending.kiosk.app.device.Registro

/**
 * Arranca el log de archivo antes que cualquier activity. Es lo que permite ver por que se cierra la
 * app cuando no hay adb en el equipo: el archivo ya existe y el handler de crashes esta puesto antes
 * de que se cree la primera pantalla.
 */
class AplicacionKiosko : Application() {
    override fun onCreate() {
        super.onCreate()

        Registro.iniciar(this)
        DiagnosticoEquipo.volcar(this)
    }
}
