package com.vending.kiosk.app.mqtt

import com.vending.kiosk.app.data.backend.BackendHttp
import com.vending.kiosk.app.data.session.AuthSessionManager

/**
 * Le pide al backend las credenciales del broker para la maquina del token.
 *
 * El backend contesta `data: null` cuando el canal esta apagado (todavia no hay broker, o el ERP no lo
 * configuro): eso NO es un error, es «segui con el sondeo». Se distingue de un fallo de red porque el
 * servicio debe dejar de insistir en un caso y reintentar en el otro.
 */
object MqttCredentialsGateway {

    private const val RUTA = "/maquina-auth/broker"

    fun pedir(sesion: AuthSessionManager): MqttCredentialsResult {
        val autorizacion = sesion.getAuthorizationHeader()
            ?: return MqttCredentialsResult.Error("La maquina no tiene sesion", sesionVencida = true)

        return try {
            val respuesta = BackendHttp.request("POST", RUTA, autorizacion)

            if (respuesta.isUnauthorized) {
                return MqttCredentialsResult.Error("Sesion de maquina vencida", sesionVencida = true)
            }

            if (!respuesta.isSuccess) {
                return MqttCredentialsResult.Error(
                    respuesta.errorMessage("No se pudieron pedir las credenciales del broker")
                )
            }

            val data = respuesta.dataObject()
            val host = data.optString("host", "").trim()
            val usuario = data.optString("usuario", "").trim()
            val clave = data.optString("clave", "").trim()

            if (host.isBlank() || usuario.isBlank() || clave.isBlank()) {
                return MqttCredentialsResult.NoDisponible
            }

            MqttCredentialsResult.Ok(
                MqttCredentials(
                    host = host,
                    puerto = data.optInt("puerto", 1883),
                    tls = data.optBoolean("tls", false),
                    prefijo = data.optString("prefijo", "maq").trim().ifBlank { "maq" },
                    usuario = usuario,
                    clave = clave
                )
            )
        } catch (excepcion: Exception) {
            MqttCredentialsResult.Error(excepcion.message ?: "sin detalle")
        }
    }
}

sealed interface MqttCredentialsResult {
    data class Ok(val credentials: MqttCredentials) : MqttCredentialsResult

    /** El backend no tiene broker configurado: el canal esta apagado y la maquina sigue con el sondeo. */
    data object NoDisponible : MqttCredentialsResult

    data class Error(
        val message: String,
        val sesionVencida: Boolean = false
    ) : MqttCredentialsResult
}
