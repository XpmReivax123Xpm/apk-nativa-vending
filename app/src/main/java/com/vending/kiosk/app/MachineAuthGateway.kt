package com.vending.kiosk.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object MachineAuthGateway {

    private const val MACHINE_LOGIN_ENDPOINT = "https://boxipagobackend.pagofacil.com.bo/api/maquinas/login"

    fun loginMachine(machineCode: String, pin: String): MachineLoginResult {
        if (machineCode.isBlank() || pin.isBlank()) {
            return MachineLoginResult.Error("Codigo/PIN de maquina invalido")
        }

        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(MACHINE_LOGIN_ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 12_000
                readTimeout = 12_000
                doOutput = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json")
            }

            val payload = JSONObject().apply {
                put("tcCodigoMaquina", machineCode)
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
                return MachineLoginResult.Error("Respuesta vacia login maquina (HTTP $statusCode)")
            }

            val json = JSONObject(rawBody)
            val backendError = json.optInt("error", -1)
            val backendStatus = json.optInt("status", 0)
            val message = json.optString("message", "No se pudo autenticar maquina")

            if (statusCode !in 200..299 || backendError != 0 || backendStatus != 1) {
                return MachineLoginResult.Error("$message (HTTP $statusCode)")
            }

            val values = json.optJSONObject("values") ?: JSONObject()
            val accessToken = values.optString("accessToken", "").trim()
            val tokenType = values.optString("tokenType", "bearer").trim().ifBlank { "bearer" }
            val expiresInMinutes = values.optLong("expiresInMinutes", 0L)
            val machineId = values.optInt("tnMaquina", 0)
            val machineCodeResolved = values.optString("tcCodigoMaquina", machineCode).trim().ifBlank { machineCode }

            if (accessToken.isBlank() || expiresInMinutes <= 0L) {
                return MachineLoginResult.Error("Login maquina sin token valido")
            }

            MachineLoginResult.Success(
                accessToken = accessToken,
                tokenType = tokenType,
                expiresInMinutes = expiresInMinutes,
                machineId = machineId,
                machineCode = machineCodeResolved
            )
        } catch (ex: Exception) {
            MachineLoginResult.Error("Fallo login maquina: ${ex.message ?: "sin detalle"}")
        } finally {
            connection?.disconnect()
        }
    }

    fun refreshSessionWithStoredMachineCredentials(authSessionManager: AuthSessionManager): MachineLoginResult {
        val credentials = authSessionManager.getMachineCredentials()
            ?: return MachineLoginResult.Error("No hay credenciales de maquina guardadas")

        val loginResult = loginMachine(credentials.machineCode, credentials.machinePin)
        if (loginResult is MachineLoginResult.Success) {
            authSessionManager.saveSession(
                accessToken = loginResult.accessToken,
                tokenType = loginResult.tokenType,
                expiresInMinutes = loginResult.expiresInMinutes
            )
            authSessionManager.saveMachineCredentials(
                machineId = if (loginResult.machineId > 0) loginResult.machineId else credentials.machineId,
                machineCode = loginResult.machineCode,
                machinePin = credentials.machinePin
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
        val machineCode: String
    ) : MachineLoginResult

    data class Error(val message: String) : MachineLoginResult
}
