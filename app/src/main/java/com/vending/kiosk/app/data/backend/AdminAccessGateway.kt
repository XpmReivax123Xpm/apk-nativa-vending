package com.vending.kiosk.app.data.backend

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object AdminAccessGateway {

    private const val ADMIN_ACCESS_ENDPOINT = "https://boxipagobackend.pagofacil.com.bo/api/maquinas/acceso"

    fun validateAccess(codigoMaquina: String, pin: String): AdminAccessResult {
        if (codigoMaquina.isBlank()) return AdminAccessResult.Error("Codigo de maquina invalido")

        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(ADMIN_ACCESS_ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 12_000
                readTimeout = 12_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }

            val payload = JSONObject().apply {
                put("tcCodigoMaquina", codigoMaquina)
                put("tcPin", pin)
            }.toString()

            connection.outputStream.use { output ->
                output.write(payload.toByteArray(Charsets.UTF_8))
            }

            val statusCode = connection.responseCode
            val rawBody = runCatching {
                if (statusCode in 200..299) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                }
            }.getOrDefault("")

            if (rawBody.isBlank()) {
                return AdminAccessResult.Error("Sin respuesta de validacion de PIN")
            }

            val json = JSONObject(rawBody)
            val backendError = json.optInt("error", -1)
            val backendStatus = json.optInt("status", 0)
            val backendMessage = json.optString("message", "No se pudo validar acceso")
            val values = json.optJSONObject("values") ?: JSONObject()
            val acceso = values.optInt("tnAcceso", 0)

            if (statusCode in 200..299 && backendError == 0 && backendStatus == 1 && acceso == 1) {
                AdminAccessResult.Granted
            } else if (acceso == 0 || backendError != 0 || backendStatus != 1) {
                AdminAccessResult.Denied(backendMessage.ifBlank { "PIN invalido" })
            } else {
                AdminAccessResult.Error("$backendMessage (HTTP $statusCode)")
            }
        } catch (ex: Exception) {
            AdminAccessResult.Error("Error validando acceso: ${ex.message ?: "sin detalle"}")
        } finally {
            connection?.disconnect()
        }
    }
}

sealed interface AdminAccessResult {
    data object Granted : AdminAccessResult
    data class Denied(val message: String) : AdminAccessResult
    data class Error(val message: String) : AdminAccessResult
}
