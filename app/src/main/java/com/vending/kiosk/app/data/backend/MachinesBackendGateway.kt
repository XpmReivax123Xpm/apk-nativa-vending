package com.vending.kiosk.app.data.backend

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object MachinesBackendGateway {

    private const val MACHINES_ENDPOINT = "https://boxipagobackend.pagofacil.com.bo/api/maquinas"

    fun fetchMachines(authorization: String): MachinesBackendResult {
        var connection: HttpURLConnection? = null

        return try {
            connection = (URL(MACHINES_ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 12_000
                setRequestProperty("Authorization", authorization)
                setRequestProperty("Accept", "application/json")
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
                return MachinesBackendResult.Error("Respuesta vacia del backend (HTTP $statusCode)")
            }

            val json = JSONObject(rawBody)
            val error = json.optInt("error", -1)
            val status = json.optInt("status", 0)
            val message = json.optString("message", "Error consultando maquinas")

            if (statusCode !in 200..299 || error != 0 || status != 1) {
                return MachinesBackendResult.Error(message)
            }

            val values = json.optJSONObject("values") ?: JSONObject()
            val maquinasJson = values.optJSONArray("maquinas")
            val maquinas = mutableListOf<Machine>()

            if (maquinasJson != null) {
                for (i in 0 until maquinasJson.length()) {
                    val item = maquinasJson.optJSONObject(i) ?: continue
                    maquinas += Machine(
                        id = item.optInt("tnMaquina", 0),
                        codigo = item.optString("tcCodigo", "SIN-CODIGO"),
                        locacion = item.optString("tcLocacion", "Sin locacion"),
                        estado = item.optInt("tnEstado", 0)
                    )
                }
            }

            MachinesBackendResult.Success(maquinas)
        } catch (ex: Exception) {
            MachinesBackendResult.Error("Fallo de conexion: ${ex.message ?: "sin detalle"}")
        } finally {
            connection?.disconnect()
        }
    }
}

data class Machine(
    val id: Int,
    val codigo: String,
    val locacion: String,
    val estado: Int
)

sealed interface MachinesBackendResult {
    data class Success(val maquinas: List<Machine>) : MachinesBackendResult
    data class Error(val message: String) : MachinesBackendResult
}
