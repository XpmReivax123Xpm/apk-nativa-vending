package com.vending.kiosk.app.device

import android.content.Context
import android.util.Log
import com.vending.kiosk.BuildConfig
import com.vending.kiosk.app.SimulationRuntime
import java.io.File

/**
 * Identifier of the equipment this kiosk runs on.
 *
 * No se teclea ni se pide en pantalla: lo lee el propio equipo de su tarjeta de red. Sin ese dato el
 * kiosko NO inicia sesion —no existe el ingreso manual— para que la APK solo pueda operar en un aparato
 * que entregue su identificador. Este objeto es el unico que sabe de donde sale: la pantalla de acceso no
 * lo consulta.
 */
object DeviceIdentity {

    private const val TAG = "DeviceIdentity"

    /**
     * Nodo del kernel de la interfaz ETHERNET. Se lee como cualquier archivo de texto: sin permisos, sin
     * root y sin Device Owner. Es la MAC de FABRICA de la placa de red (estable entre reinicios, redes y
     * actualizaciones) y es la unica de las dos que se puede leer: Android protege el nodo de la Wi-Fi con
     * la etiqueta `sysfs_mac_address` y un `neverallow` para apps —solo el sistema puede leerlo, y es el
     * archivo que la API de Device Owner consulta por nosotros—. Ademas la Wi-Fi no siempre esta encendida
     * y su MAC puede ser aleatoria por red, asi que no serviria como identificador.
     *
     * `eth0` primero porque es el nombre que usa la API del fabricante (ZtlApi `getMacAddress`); los otros
     * dos son el mismo nodo con el nombre que algunas placas le dan a la interfaz cableada.
     */
    private val ETHERNET_MAC_NODES = listOf(
        "/sys/class/net/eth0/address",
        "/sys/class/net/eth1/address",
        "/sys/class/net/end0/address"
    )

    /** MAC que no identifica nada: la entregan las placas de red sin direccion asignada. */
    private const val UNASSIGNED_MAC = "00:00:00:00:00:00"

    /** Equipo que espera el backend simulado. Solo en builds de simulador (ver `simuladorMac`). */
    private const val SIMULATOR_MAC = "02:SIM:00:00:00:01"

    /** La MAC, escrita como la escribe Android: seis pares hexadecimales separados por dos puntos. */
    private val MAC_REGEX = Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")

    /** MAC del equipo, o el motivo por el que no se pudo obtener. */
    fun read(context: Context): DeviceIdentifierResult {
        simuladorMac()?.let { return DeviceIdentifierResult.Available(it) }

        val intentados = mutableListOf<String>()
        for (path in ETHERNET_MAC_NODES) {
            val node = File(path)
            if (!node.isFile) continue
            intentados += path

            val mac = macDelNodo(node)
            if (mac != null) {
                Log.i(TAG, "Identificador del equipo: $mac (leido de $path)")
                return DeviceIdentifierResult.Available(mac)
            }
        }

        if (intentados.isEmpty()) {
            Log.w(TAG, "Sin identificador: no existe ninguno de ${ETHERNET_MAC_NODES.joinToString(", ")}")
            return DeviceIdentifierResult.Unavailable(DeviceIdentifierFailure.NotAvailable)
        }

        Log.w(TAG, "Sin identificador: no hay una MAC utilizable en $intentados")
        return DeviceIdentifierResult.Unavailable(
            DeviceIdentifierFailure.Error(
                "No hay una MAC utilizable en ${intentados.joinToString(" ni en ")}"
            )
        )
    }

    /** La MAC que contiene el nodo, o null si no se pudo leer o no tiene una que sirva. */
    private fun macDelNodo(node: File): String? {
        val contenido = try {
            node.readText()
        } catch (ex: Exception) {
            // El firmware puede bloquear el nodo (SELinux): queda dicho en el log, que es lo que se mira
            // cuando el kiosko no arranca.
            Log.w(TAG, "No se pudo leer ${node.path}: ${ex.message ?: "sin detalle"}")
            return null
        }

        val mac = MAC_REGEX.find(contenido)?.value
        if (mac == null) {
            Log.w(TAG, "${node.path} no tiene una MAC: ${contenido.trim().take(60)}")
            return null
        }
        if (mac.equals(UNASSIGNED_MAC, ignoreCase = true)) {
            Log.w(TAG, "${node.path} devolvio la MAC sin asignar ($UNASSIGNED_MAC)")
            return null
        }

        return mac.uppercase()
    }

    /**
     * Atajo SOLO para builds de simulador: en la PC no hay tarjeta que entregue la MAC, y sin el cerrojo la
     * pantalla de acceso no tendria con que entrar. Una release no lo ve nunca (el modo simulador no existe
     * cuando `BuildConfig.DEBUG` es falso, ver `SimulationRuntime`).
     */
    private fun simuladorMac(): String? {
        if (!BuildConfig.DEBUG) return null
        SimulationRuntime.apply()
        return if (SimulationRuntime.isSimulator()) SIMULATOR_MAC else null
    }
}

sealed interface DeviceIdentifierResult {
    data class Available(val macAddress: String) : DeviceIdentifierResult
    data class Unavailable(val failure: DeviceIdentifierFailure) : DeviceIdentifierResult
}

sealed interface DeviceIdentifierFailure {
    /** El equipo no expone ninguna interfaz de red cableada: sin identificador el kiosko no se autentica. */
    data object NotAvailable : DeviceIdentifierFailure

    data class Error(val detail: String) : DeviceIdentifierFailure
}
