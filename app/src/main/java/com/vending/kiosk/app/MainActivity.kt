package com.vending.kiosk.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.vending.kiosk.R
import com.vending.kiosk.app.data.backend.MachineAuthGateway
import com.vending.kiosk.app.data.backend.MachineLoginResult
import com.vending.kiosk.app.data.session.AuthSessionManager
import com.vending.kiosk.app.device.Autoarranque
import com.vending.kiosk.app.device.DeviceIdentifierResult
import com.vending.kiosk.app.device.DeviceIdentity
import com.vending.kiosk.app.device.HardwareWatchdog
import com.vending.kiosk.app.device.Registro
import com.vending.kiosk.app.device.ReinicioEquipo
import com.vending.kiosk.app.mqtt.MaquinaMqttService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Punto de entrada de la tablet. Tiene DOS caras, y cual se ve lo decide el intent:
 *
 * - Sin extra: es el ARRANQUE. La tablet entra directo al kiosco, sin menu: espera la red, se
 *   autentica y abre el catalogo (o la pantalla de bloqueo si la maquina esta desactivada).
 * - Con [EXTRA_MENU]: es el MENU DE TECNICO, y la unica forma de abrirlo es el boton del kiosco que
 *   aparece con el PIN. Es la unica puerta al tester, al calibrador y a salir.
 *
 * El arranque no se rinde: sin red espera y vuelve a probar, y un error del servidor se reintenta con
 * espera creciente. Un rechazo de credenciales (401/422/423) corta el bucle y manda al login manual:
 * insistir con un PIN que ya no sirve consume intentos del servidor y a los cinco bloquea la maquina
 * por quince minutos.
 */
class MainActivity : AppCompatActivity() {
    private val authSessionManager by lazy { AuthSessionManager(this) }
    private var arranqueJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Registro.i("MainActivity: menu=${intent.getBooleanExtra(EXTRA_MENU, false)}")

        // Si la tablet trae bloqueo de pantalla, el kiosco tiene que quedar visible igual: es un equipo
        // que arranca solo despues de un corte y nadie va a pasar a desbloquearlo.
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)

        SimulationRuntime.apply()

        // El watchdog de placa se alimenta mientras la app viva: si el kiosko se cuelga, la placa reinicia
        // la tablet sola. En equipos sin el servicio del fabricante no hace nada.
        HardwareWatchdog.start(this)

        // Autostart del firmware (persist.sys.bootPkg*): la tablet vuelve sola al kiosco despues de un
        // corte, sin depender de que alguien lo configure a mano en cada equipo.
        Autoarranque.asegurar(this, MainActivity::class.java.name)

        // Ademas del autostart del firmware (que este equipo ignora), la app se deja como escritorio:
        // es el unico arranque que Android garantiza, porque siempre abre la pantalla de inicio.
        Autoarranque.dejarComoEscritorio(this, MainActivity::class.java.name)

        val startupLoadingView = findViewById<View>(R.id.startupLoadingView)
        val mainMenuContent = findViewById<View>(R.id.mainMenuContent)
        val startupStatusText = findViewById<TextView>(R.id.startupStatusText)

        // El menu de tecnico solo se abre desde el kiosco; el arranque entra directo al kiosco.
        if (intent.getBooleanExtra(EXTRA_MENU, false)) {
            startupLoadingView.visibility = View.GONE
            mainMenuContent.visibility = View.VISIBLE
        } else {
            startupLoadingView.visibility = View.VISIBLE
            mainMenuContent.visibility = View.GONE
            arranqueJob = lifecycleScope.launch { arrancarKiosco(startupStatusText) }
        }

        setupMenuButtons()
    }

    private fun setupMenuButtons() {
        findViewById<View>(R.id.btnVendingKiosk).setOnClickListener {
            startActivity(Intent(this, KioskAccessActivity::class.java))
        }
        findViewById<View>(R.id.btnVendingTester).setOnClickListener {
            startActivity(Intent(this, VendingTesterActivity::class.java))
        }
        findViewById<View>(R.id.btnVendingCalibrator).setOnClickListener {
            startActivity(Intent(this, VendingCalibratorActivity::class.java))
        }
        findViewById<View>(R.id.btnRebootTablet).setOnClickListener {
            confirmarReinicio()
        }
        findViewById<View>(R.id.btnExitApp).setOnClickListener {
            // Salir del kiosco apaga el canal: la maquina deja de estar en linea en el ERP, que es
            // justo lo que se busca si alguien la usa con otra aplicacion.
            MaquinaMqttService.detener(this)
            abrirOtroEscritorio()
            finishAffinity()
        }
    }

    /**
     * Con la app como escritorio del equipo, un ACTION_HOME volveria a abrirla: se busca el otro
     * escritorio por nombre (el launcher del sistema). Sin candidato se usa el intent de siempre.
     */
    private fun abrirOtroEscritorio() {
        val intencion = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val otro = packageManager.queryIntentActivities(intencion, 0)
            .map { it.activityInfo }
            .firstOrNull { it.packageName != packageName }

        if (otro == null) {
            startActivity(intencion.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

            return
        }

        startActivity(
            Intent().setComponent(ComponentName(otro.packageName, otro.name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /**
     * El reinicio lo ejecuta el servicio del fabricante; el dialogo existe para que no se dispare por
     * un toque de mas en una pantalla de tecnico.
     */
    private fun confirmarReinicio() {
        AlertDialog.Builder(this)
            .setTitle(R.string.reboot_title)
            .setMessage(R.string.reboot_message)
            .setPositiveButton(R.string.reboot_confirm) { _, _ ->
                ReinicioEquipo.reiniciar(this)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Arranque: autologueo con el equipo y el PIN guardados, y salida directa al catalogo.
     *
     * Sin red no se rinde (una tablet recien encendida tarda en asociarse o en levantar los datos
     * moviles); con red, el rechazo de credenciales es el unico caso que no se reintenta.
     */
    private suspend fun arrancarKiosco(estado: TextView) {
        try {
            arrancarKioscoInterno(estado)
        } catch (cancelada: CancellationException) {
            throw cancelada
        } catch (excepcion: Exception) {
            // Un fallo aca no puede dejar la tablet en una pantalla muerta: el menu de tecnico es la
            // unica puerta con PIN y sirve para diagnosticar en el lugar.
            Registro.e("arranque: fallo inesperado, se abre el menu", excepcion)

            startActivity(Intent(this, MainActivity::class.java).putExtra(EXTRA_MENU, true))
            finish()
        }
    }

    private suspend fun arrancarKioscoInterno(estado: TextView) {
        val equipo = authSessionManager.getEquipo()
        val identificador = DeviceIdentity.read(this)

        val puedeAutologuear = authSessionManager.isKioskAutoResumeEnabled() &&
            equipo != null && equipo.machineId > 0 &&
            identificador is DeviceIdentifierResult.Available &&
            authSessionManager.isDeviceIdentifierRevealed()

        if (!puedeAutologuear) {
            Registro.i("Arranque sin autologueo: se pide el PIN en la pantalla de acceso")
            abrirAcceso()
            return
        }

        var esperaMs = ESPERA_INICIAL_MS

        while (true) {
            if (!hayRed()) {
                estado.text = getString(R.string.startup_loading_sin_red)
                delay(ESPERA_SIN_RED_MS)
                continue
            }

            estado.text = getString(R.string.startup_loading_conectando)

            val resultado = withContext(Dispatchers.IO) {
                MachineAuthGateway.refreshSessionWithStoredMachineCredentials(authSessionManager)
            }

            when (resultado) {
                is MachineLoginResult.Success -> {
                    abrirCatalogo(resultado)
                    return
                }

                is MachineLoginResult.Error -> when {
                    resultado.inactive -> {
                        Registro.i("Arranque: la maquina esta desactivada, se abre el bloqueo")
                        abrirBloqueo()
                        return
                    }

                    resultado.credencialesRechazadas -> {
                        Registro.w("Arranque: credenciales rechazadas (${resultado.message}); PIN manual")
                        abrirAcceso()
                        return
                    }

                    else -> {
                        Registro.w("Arranque: ${resultado.message}; se reintenta en ${esperaMs / 1000} s")
                        estado.text = getString(R.string.startup_loading_reintentando)
                        delay(esperaMs)
                        esperaMs = (esperaMs * 2).coerceAtMost(ESPERA_MAXIMA_MS)
                    }
                }
            }
        }
    }

    private fun abrirCatalogo(resultado: MachineLoginResult.Success) {
        val equipo = authSessionManager.getEquipo()
        val maquinaId = equipo?.machineId?.takeIf { it > 0 } ?: resultado.machineId
        val maquinaCodigo = equipo?.machineCode?.ifBlank { resultado.machineCode } ?: resultado.machineCode

        if (maquinaId <= 0 || maquinaCodigo.isBlank()) {
            Registro.w("Arranque: el login no devolvio una maquina valida")
            abrirAcceso()
            return
        }

        Registro.i("Abriendo catalogo: maquina=$maquinaId")
        startActivity(
            Intent(this, KioskCatalogActivity::class.java).apply {
                putExtra(KioskCatalogActivity.EXTRA_MACHINE_ID, maquinaId)
                putExtra(KioskCatalogActivity.EXTRA_MACHINE_CODE, maquinaCodigo)
                putExtra(
                    KioskCatalogActivity.EXTRA_MACHINE_LOCATION,
                    authSessionManager.getMachineLocation().orEmpty()
                )
            }
        )
        finish()
    }

    private fun abrirBloqueo() {
        Registro.i("Abriendo bloqueo")

        startActivity(Intent(this, MachineOutOfServiceActivity::class.java))
        finish()
    }

    /** Login manual, sin vuelta al menu: la unica salida de esa pantalla es autenticarse. */
    private fun abrirAcceso() {
        Registro.i("Abriendo acceso sin salida")

        startActivity(
            Intent(this, KioskAccessActivity::class.java)
                .putExtra(KioskAccessActivity.EXTRA_SIN_SALIDA, true)
        )
        finish()
    }

    private fun hayRed(): Boolean {
        return try {
            val gestor = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val red = gestor.activeNetwork ?: return false
            val capacidades = gestor.getNetworkCapabilities(red) ?: return false

            capacidades.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (excepcion: Exception) {
            // Si el sistema no contesta, se intenta igual: el fallo real lo devuelve la peticion, y
            // quedarse esperando "sin conexion" para siempre seria peor que un intento fallido.
            Registro.w("hayRed: ${excepcion.message}")

            true
        }
    }

    override fun onDestroy() {
        arranqueJob?.cancel()
        arranqueJob = null
        super.onDestroy()
    }

    companion object {
        /**
         * Unica puerta al menu de tecnico: la manda el kiosco desde su boton "volver al menu", que
         * aparece recien con el PIN. El arranque de la tablet no la trae, y por eso entra al kiosco.
         */
        const val EXTRA_MENU = "com.vending.kiosk.extra.MENU"

        /** Sin red no se rinde: vuelve a mirar a los pocos segundos. */
        private const val ESPERA_SIN_RED_MS = 5_000L

        private const val ESPERA_INICIAL_MS = 10_000L

        /** Tope del reintento cuando hay red pero el servidor no responde. */
        private const val ESPERA_MAXIMA_MS = 120_000L
    }
}
