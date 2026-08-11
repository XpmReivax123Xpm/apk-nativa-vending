package com.vending.kiosk.app.backend

import com.vending.kiosk.app.AuthSessionManager
import com.vending.kiosk.integration.backend.contracts.VendingBackendGateway
import com.vending.kiosk.integration.backend.models.CancelOrderResult
import com.vending.kiosk.integration.backend.models.CatalogResponse
import com.vending.kiosk.integration.backend.models.CreateOrderQrRequest
import com.vending.kiosk.integration.backend.models.CreateOrderQrResponse
import com.vending.kiosk.integration.backend.models.DispenseStatusRequest
import com.vending.kiosk.integration.backend.models.DispenseStatusResult
import com.vending.kiosk.integration.backend.models.PaymentMethod
import com.vending.kiosk.integration.backend.models.PaymentStatus
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class HttpVendingBackendGateway(
    private val sessionManager: AuthSessionManager
) : VendingBackendGateway {

    override suspend fun fetchCatalog(machineId: Long): CatalogResponse = notImplemented()

    override suspend fun fetchEnabledPaymentMethods(): List<PaymentMethod> {
        val authHeader = sessionManager.getAuthorizationHeader().orEmpty()
        val endpoint = "https://boxipagobackend.pagofacil.com.bo/api/maquina/pago/qr/servicios-habilitados"
        var connection: HttpURLConnection? = null

        return try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 12_000
                setRequestProperty("Authorization", authHeader)
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
                throw PaymentMethodsGatewayException(
                    "Respuesta vacia de servicios de pago (HTTP $statusCode)",
                    unauthorized = statusCode == HttpURLConnection.HTTP_UNAUTHORIZED
                )
            }

            val json = JSONObject(rawBody)
            val backendError = json.optInt("error", -1)
            val backendStatus = json.optInt("status", 0)
            val backendMessage = json.optString("message", "No se pudo obtener servicios habilitados")
            if (statusCode !in 200..299 || backendError != 0 || backendStatus != 1) {
                throw PaymentMethodsGatewayException(
                    buildBackendErrorMessage(statusCode, rawBody, backendMessage),
                    unauthorized = statusCode == HttpURLConnection.HTTP_UNAUTHORIZED
                )
            }

            val values = json.optJSONObject("values") ?: JSONObject()
            val providerResponse = values.optJSONObject("taProviderResponse") ?: JSONObject()
            val providerError = providerResponse.optInt("error", -1)
            val providerValues = providerResponse.optJSONArray("values") ?: JSONArray()
            if (providerError != 0 || providerValues.length() <= 0) {
                throw PaymentMethodsGatewayException("No hay servicios de pago habilitados para esta maquina.")
            }

            val methods = mutableListOf<PaymentMethod>()
            for (i in 0 until providerValues.length()) {
                val item = providerValues.optJSONObject(i) ?: continue
                val id = when (val rawId = item.opt("paymentMethodId")) {
                    is Number -> rawId.toInt()
                    is String -> rawId.toIntOrNull() ?: 0
                    else -> item.optInt("paymentMethodId", 0)
                }
                val label = item.optString("paymentMethodName", "").trim()
                if (id > 0 && label.isNotBlank()) {
                    methods += PaymentMethod(id = id, label = label)
                }
            }

            val unique = methods.distinctBy { it.id }
            if (unique.isEmpty()) {
                throw PaymentMethodsGatewayException("No hay servicios de pago validos en la respuesta del proveedor.")
            } else {
                unique
            }
        } catch (ex: PaymentMethodsGatewayException) {
            throw ex
        } catch (ex: Exception) {
            throw PaymentMethodsGatewayException("Fallo obteniendo servicios habilitados: ${ex.message ?: "sin detalle"}")
        } finally {
            connection?.disconnect()
        }
    }

    override suspend fun createOrderQr(request: CreateOrderQrRequest): CreateOrderQrResponse = notImplemented()

    override suspend fun fetchPaymentStatus(orderId: Long): PaymentStatus {
        val authHeader = sessionManager.getAuthorizationHeader().orEmpty()
        val endpoint = "https://boxipagobackend.pagofacil.com.bo/api/pedido/$orderId/estado-pago"
        var connection: HttpURLConnection? = null

        return try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 12_000
                setRequestProperty("Authorization", authHeader)
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
                return PaymentStatus.Error("Sin respuesta de estado pago (HTTP $statusCode)")
            }

            val json = JSONObject(rawBody)
            val backendError = json.optInt("error", -1)
            val backendStatus = json.optInt("status", 0)
            val backendMessage = json.optString("message", "Consultando estado...")

            if (statusCode !in 200..299 || backendError != 0 || backendStatus != 1) {
                return PaymentStatus.Error(buildBackendErrorMessage(statusCode, rawBody, backendMessage))
            }

            val values = json.optJSONObject("values") ?: JSONObject()
            val tnEstadoPago = values.optInt("tnEstadoPago", Int.MIN_VALUE)
            val tnEstadoPedido = values.optInt("tnEstadoPedido", Int.MIN_VALUE)
            val estadoFallback = values.optInt("estado", 1)
            val effectiveState = when {
                tnEstadoPago != Int.MIN_VALUE -> tnEstadoPago
                tnEstadoPedido != Int.MIN_VALUE -> tnEstadoPedido
                else -> estadoFallback
            }

            when (effectiveState) {
                2 -> PaymentStatus.Paid
                3 -> PaymentStatus.Cancelled("Pago cancelado")
                4 -> PaymentStatus.Failed("Pago fallido")
                else -> PaymentStatus.Pending(backendMessage)
            }
        } catch (ex: Exception) {
            PaymentStatus.Error("Error consultando estado de pago: ${ex.message ?: "sin detalle"}")
        } finally {
            connection?.disconnect()
        }
    }

    override suspend fun cancelOrder(orderId: Long): CancelOrderResult {
        val authHeader = sessionManager.getAuthorizationHeader().orEmpty()
        val endpoint = "https://boxipagobackend.pagofacil.com.bo/api/pedido/$orderId/cancelar"
        var connection: HttpURLConnection? = null

        return try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 12_000
                readTimeout = 12_000
                doOutput = true
                setRequestProperty("Authorization", authHeader)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json")
            }

            val payload = JSONObject().apply {
                put("tcMotivo", "CANCELADO_CLIENTE_APK")
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
                return CancelOrderResult.Error("Sin respuesta al cancelar pedido (HTTP $statusCode)")
            }

            val json = JSONObject(rawBody)
            val backendError = json.optInt("error", -1)
            val backendStatus = json.optInt("status", 0)
            val backendMessage = json.optString("message", "No se pudo cancelar el pedido.")
            json.optJSONObject("values") ?: JSONObject()

            return if (statusCode in 200..299 && backendError == 0 && backendStatus == 1) {
                CancelOrderResult.Success(backendMessage)
            } else {
                CancelOrderResult.Error(buildBackendErrorMessage(statusCode, rawBody, backendMessage))
            }
        } catch (ex: Exception) {
            CancelOrderResult.Error("Error cancelando pedido: ${ex.message ?: "sin detalle"}")
        } finally {
            connection?.disconnect()
        }
    }

    override suspend fun reportDispenseStatus(request: DispenseStatusRequest): DispenseStatusResult {
        val authHeader = sessionManager.getAuthorizationHeader().orEmpty()
        if (authHeader.isBlank()) {
            return DispenseStatusResult.Error("Authorization header is blank")
        }

        val endpoint = "https://boxipagobackend.pagofacil.com.bo/api/maquina/pedido/dispensacion"
        var connection: HttpURLConnection? = null

        return try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 12_000
                readTimeout = 12_000
                doOutput = true
                setRequestProperty("Authorization", authHeader)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }

            val payload = JSONObject().apply {
                put("tnPedido", request.orderId)
                put("tnPedidoDetalle", request.orderDetailId)
                put("tnPlanogramaCelda", request.planogramCellId)
                put("tnEstadoDispensacion", request.dispenseStatusId)
                put("tcEstadoDispensacion", request.dispenseStatus)
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
                return if (statusCode in 200..299) {
                    DispenseStatusResult.Success
                } else {
                    DispenseStatusResult.Error("Sin respuesta al reportar dispensacion (HTTP $statusCode)")
                }
            }

            val json = JSONObject(rawBody)
            val backendError = json.optInt("error", -1)
            val backendStatus = json.optInt("status", 0)
            val backendMessage = json.optString("message", "No se pudo reportar dispensacion.")
            json.optJSONObject("values") ?: JSONObject()

            if (statusCode in 200..299 && backendError == 0 && backendStatus == 1) {
                DispenseStatusResult.Success
            } else {
                DispenseStatusResult.Error(buildBackendErrorMessage(statusCode, rawBody, backendMessage))
            }
        } catch (ex: Exception) {
            DispenseStatusResult.Error("Error reportando dispensacion: ${ex.message ?: "sin detalle"}")
        } finally {
            connection?.disconnect()
        }
    }

    private fun notImplemented(): Nothing {
        throw NotImplementedError(
            "HttpVendingBackendGateway is a Phase A Part 1 skeleton and is not wired yet."
        )
    }

    private fun buildBackendErrorMessage(
        statusCode: Int,
        rawBody: String,
        fallbackMessage: String
    ): String {
        val parsedMessage = runCatching {
            val json = JSONObject(rawBody)
            json.optString("message")
                .ifBlank { json.optString("error") }
                .ifBlank { fallbackMessage }
        }.getOrDefault(fallbackMessage)
        return "$parsedMessage (HTTP $statusCode)"
    }
}

class PaymentMethodsGatewayException(
    override val message: String,
    val unauthorized: Boolean = false
) : Exception(message)
