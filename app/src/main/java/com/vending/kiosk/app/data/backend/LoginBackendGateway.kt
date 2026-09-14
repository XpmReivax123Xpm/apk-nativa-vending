package com.vending.kiosk.app.data.backend

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class LoginBackendGateway {

    fun login(correo: String, password: String): LoginBackendResult {
        val endpoint = "https://boxipagobackend.pagofacil.com.bo/api/login"
        var connection: HttpURLConnection? = null

        return try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 12_000
                readTimeout = 12_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }

            val payload = JSONObject().apply {
                put("tcCorreo", correo)
                put("tcPassword", password)
            }.toString()

            connection.outputStream.use { out ->
                out.write(payload.toByteArray(Charsets.UTF_8))
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
                return LoginBackendResult.Error("Respuesta vacia del backend (HTTP $statusCode)")
            }

            val json = JSONObject(rawBody)
            val error = json.optInt("error", -1)
            val status = json.optInt("status", 0)
            val message = json.optString("message", "No se pudo autenticar")

            if (statusCode in 200..299 && error == 0 && status == 1) {
                val values = json.optJSONObject("values")
                    ?: return LoginBackendResult.Error("Respuesta sin 'values'")

                val accessToken = values.optString("accessToken", "")
                val tokenType = values.optString("tokenType", "bearer")
                val expiresInMinutes = values.optLong("expiresInMinutes", 0L)

                if (accessToken.isBlank() || expiresInMinutes <= 0L) {
                    return LoginBackendResult.Error("Token o expiracion invalidos")
                }

                LoginBackendResult.Success(accessToken, tokenType, expiresInMinutes)
            } else {
                LoginBackendResult.Error(message)
            }
        } catch (ex: Exception) {
            LoginBackendResult.Error("Fallo de conexion: ${ex.message ?: "sin detalle"}")
        } finally {
            connection?.disconnect()
        }
    }
}

sealed interface LoginBackendResult {
    data class Success(
        val accessToken: String,
        val tokenType: String,
        val expiresInMinutes: Long
    ) : LoginBackendResult

    data class Error(val message: String) : LoginBackendResult
}
