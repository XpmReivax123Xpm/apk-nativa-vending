package com.vending.kiosk.app.data.backend

import com.vending.kiosk.app.data.session.AuthSessionManager
import org.json.JSONObject

/**
 * Session of the machine that runs this kiosk.
 *
 * The machine authenticates with the identifier of ITS OWN device —read from the equipment, or typed
 * once by whoever installs the kiosk— and the PIN an operator types. The CODE printed on the front of
 * the machine does NOT authenticate: anybody who knew it would enter the planogram of another machine
 * and charge against it.
 */
object MachineAuthGateway {

    private const val LOGIN_PATH = "/maquina-auth/login"

    fun loginMachine(direccionMac: String, pin: String): MachineLoginResult {
        val mac = direccionMac.trim()
        if (mac.isBlank() || pin.isBlank()) {
            return MachineLoginResult.Error("Identificador del equipo o PIN invalido")
        }

        val payload = JSONObject().apply {
            put("tcDireccionMac", mac)
            put("tcPin", pin)
        }

        return try {
            val response = BackendHttp.request("POST", LOGIN_PATH, body = payload)

            if (!response.isSuccess) {
                return MachineLoginResult.Error(response.errorMessage("No se pudo autenticar la maquina"))
            }

            val json = response.jsonObjectOrNull()
                ?: return MachineLoginResult.Error(
                    "El servidor respondio sin datos al autenticar la maquina (HTTP ${response.statusCode})"
                )
            val accessToken = json.optString("access_token", "").trim()
            val tokenType = json.optString("token_type", "Bearer").trim().ifBlank { "Bearer" }
            // El backend dice cuantos MINUTOS dura el token de acceso: el kiosko no usa el refresh
            // token, se vuelve a autenticar cuando el suyo vence.
            val expiresInMinutes = json.optLong("expires_in", 0L)

            if (accessToken.isBlank() || expiresInMinutes <= 0L) {
                return MachineLoginResult.Error("Login de maquina sin token valido")
            }

            val maquina = json.optJSONObject("Maquina") ?: JSONObject()

            MachineLoginResult.Success(
                accessToken = accessToken,
                tokenType = tokenType,
                expiresInMinutes = expiresInMinutes,
                machineId = maquina.optInt("tnMaquina", 0),
                machineCode = maquina.optString("tcCodigoMaquina", "").trim(),
                machineLocation = if (maquina.isNull("tcUbicacion")) "" else maquina.optString("tcUbicacion", "").trim()
            )
        } catch (ex: Exception) {
            MachineLoginResult.Error("Fallo de conexion al autenticar la maquina: ${ex.message ?: "sin detalle"}")
        }
    }

    /**
     * Se vuelve a autenticar con el identificador de equipo y el PIN que el kiosko ya tiene, y renueva
     * la sesion guardada. Es lo que usan el arranque automatico y la recuperacion de una sesion vencida.
     */
    fun refreshSessionWithStoredMachineCredentials(authSessionManager: AuthSessionManager): MachineLoginResult {
        val equipo = authSessionManager.getEquipo()
            ?: return MachineLoginResult.Error("El kiosko no tiene configurado su identificador de equipo")

        val loginResult = loginMachine(equipo.direccionMac, equipo.pin)

        if (loginResult is MachineLoginResult.Success) {
            authSessionManager.saveSession(
                accessToken = loginResult.accessToken,
                tokenType = loginResult.tokenType,
                expiresInMinutes = loginResult.expiresInMinutes
            )
            authSessionManager.saveMaquina(
                machineId = if (loginResult.machineId > 0) loginResult.machineId else equipo.machineId,
                machineCode = loginResult.machineCode.ifBlank { equipo.machineCode }
            )
        }

        return loginResult
    }
}

sealed interface MachineLoginResult {
    data class Success(
        val accessToken: String,
        val tokenType: String,
        val expiresInMinutes: Long,
        val machineId: Int,
        val machineCode: String,
        val machineLocation: String = ""
    ) : MachineLoginResult

    data class Error(val message: String) : MachineLoginResult
}
