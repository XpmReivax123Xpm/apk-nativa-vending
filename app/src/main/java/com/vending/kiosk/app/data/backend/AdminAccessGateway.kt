package com.vending.kiosk.app.data.backend

/**
 * Validacion del PIN para SALIR del kiosko (mantenimiento).
 *
 * Es el MISMO PIN con el que la maquina entra: el backend no tiene un segundo camino para validar
 * credenciales sin abrir sesion, asi que se valida con el login de maquina y el token que devuelve se
 * descarta —al kiosko le alcanza con saber que el PIN es el de esta maquina—.
 */
object AdminAccessGateway {

    fun validateAccess(direccionMac: String, pin: String): AdminAccessResult {
        if (direccionMac.isBlank()) return AdminAccessResult.Error("El kiosko no tiene configurado su equipo")

        return when (val result = MachineAuthGateway.loginMachine(direccionMac, pin)) {
            is MachineLoginResult.Success -> AdminAccessResult.Granted
            is MachineLoginResult.Error -> {
                val message = result.message.ifBlank { "PIN invalido" }
                if (message.contains("HTTP 4", ignoreCase = true)) {
                    AdminAccessResult.Denied(message)
                } else {
                    AdminAccessResult.Error(message)
                }
            }
        }
    }
}

sealed interface AdminAccessResult {
    data object Granted : AdminAccessResult
    data class Denied(val message: String) : AdminAccessResult
    data class Error(val message: String) : AdminAccessResult
}
