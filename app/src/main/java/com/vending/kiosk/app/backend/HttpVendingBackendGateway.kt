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

    override suspend fun fetchCatalog(machineId: Long): CatalogResponse {
        val authHeader = sessionManager.getAuthorizationHeader().orEmpty()
        val endpoint = "https://boxipagobackend.pagofacil.com.bo/api/maquinas/$machineId/planograma"
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
                throw CatalogGatewayException(
                    message = "Respuesta vacia del backend (HTTP $statusCode)",
                    unauthorized = statusCode == HttpURLConnection.HTTP_UNAUTHORIZED
                )
            }

            val json = JSONObject(rawBody)
            val error = json.optInt("error", -1)
            val status = json.optInt("status", 0)
            val message = json.optString("message", "Error consultando catalogo")

            if (statusCode !in 200..299 || error != 0 || status != 1) {
                throw CatalogGatewayException(
                    message = "$message (HTTP $statusCode)",
                    unauthorized = statusCode == HttpURLConnection.HTTP_UNAUTHORIZED
                )
            }

            val values = json.optJSONObject("values") ?: JSONObject()
            val celdasJson =
                values.optJSONArray("celdas")
                    ?: values.optJSONArray("celdasPlanograma")
                    ?: values.optJSONObject("planograma")?.optJSONArray("celdas")

            if (celdasJson == null) {
                throw CatalogGatewayException("Respuesta sin celdas en values")
            }

            val cells = parseCatalogCells(celdasJson).sortedWith(
                compareBy<CatalogResponse.Cell> { parseCellCode(it.cellCode).first }
                    .thenBy { parseCellCode(it.cellCode).second }
                    .thenBy { it.cellCode }
            )
            val background = parseUiBackground(values)
            CatalogResponse(
                cells = cells,
                promotions = parsePromotions(values),
                backgroundImageUrl = background.second,
                backgroundImageId = background.first
            )
        } catch (ex: CatalogGatewayException) {
            throw ex
        } catch (ex: Exception) {
            throw CatalogGatewayException("Fallo de conexion: ${ex.message ?: "sin detalle"}")
        } finally {
            connection?.disconnect()
        }
    }

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

    override suspend fun createOrderQr(request: CreateOrderQrRequest): CreateOrderQrResponse {
        val authHeader = sessionManager.getAuthorizationHeader().orEmpty()
        val endpoint = "https://boxipagobackend.pagofacil.com.bo/api/pedido/crear-y-generar-qr"
        var connection: HttpURLConnection? = null

        return try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 14_000
                readTimeout = 14_000
                doOutput = true
                setRequestProperty("Authorization", authHeader)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }

            val itemsJson = JSONArray()
            request.items.forEach { item ->
                itemsJson.put(
                    JSONObject().apply {
                        put("tnPlanogramaCelda", item.planogramCellId)
                        put("tnProducto", item.productId)
                        put("tnCantidad", item.quantity)
                    }
                )
            }

            val payload = JSONObject().apply {
                put("tnMaquina", request.machineId)
                put("tcNombreCliente", request.customerName)
                put("tcTelefonoCliente", request.customerPhone)
                put("tcNITCliente", request.customerCi)
                put("tnPaymentMethodId", request.paymentMethodId)
                put("taItems", itemsJson)
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
                throw CreateOrderQrGatewayException("Respuesta vacia al generar QR (HTTP $statusCode)")
            }

            val json = JSONObject(rawBody)
            val backendError = json.opt("error")?.toString()?.toIntOrNull()
            val backendStatus = json.opt("status")?.toString()?.toIntOrNull()

            if (statusCode !in 200..299) {
                throw CreateOrderQrGatewayException(
                    buildCreateOrderQrBackendErrorMessage(
                        statusCode = statusCode,
                        rawBody = rawBody,
                        fallbackMessage = "Fallo al generar QR"
                    )
                )
            }

            if (backendError != null && backendError != 0) {
                throw CreateOrderQrGatewayException(
                    buildCreateOrderQrBackendErrorMessage(
                        statusCode = statusCode,
                        rawBody = rawBody,
                        fallbackMessage = "Backend reporto error al generar QR"
                    )
                )
            }

            if (backendStatus != null && backendStatus != 1) {
                throw CreateOrderQrGatewayException(
                    buildCreateOrderQrBackendErrorMessage(
                        statusCode = statusCode,
                        rawBody = rawBody,
                        fallbackMessage = "Backend no confirmo estado exitoso al generar QR"
                    )
                )
            }

            val values = json.optJSONObject("values") ?: json
            val orderId = extractIntFrom(
                values,
                "taPedido.tnPedido",
                "pedido.tnPedido",
                "tnPedido"
            )

            val qrBase64 = extractStringFrom(
                values,
                "taQr.tcQrBase64",
                "taQr.qrBase64",
                "qr.tcQrBase64",
                "qr.qrBase64",
                "tcQrBase64",
                "qrBase64"
            )

            val expiration = extractStringFrom(
                values,
                "taQr.ltExpirationDate",
                "taQr.expirationDate",
                "qr.ltExpirationDate",
                "qr.expirationDate",
                "ltExpirationDate",
                "expirationDate"
            )

            if (orderId <= 0) {
                throw CreateOrderQrGatewayException("Respuesta sin tnPedido valido. Body: $rawBody")
            }
            if (qrBase64.isBlank()) {
                throw CreateOrderQrGatewayException("Respuesta sin QR base64. Body: $rawBody")
            }

            CreateOrderQrResponse(
                orderId = orderId,
                qrBase64 = qrBase64,
                expiration = expiration,
                details = extractOrderDetails(values)
            )
        } catch (ex: CreateOrderQrGatewayException) {
            throw ex
        } catch (ex: Exception) {
            throw CreateOrderQrGatewayException("Fallo de conexion al generar QR: ${ex.message ?: "sin detalle"}")
        } finally {
            connection?.disconnect()
        }
    }

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

    private fun extractIntFrom(source: JSONObject?, vararg paths: String): Int {
        if (source == null) return 0
        for (path in paths) {
            val value = resolvePath(source, path)
            when (value) {
                is Number -> return value.toInt()
                is String -> value.toIntOrNull()?.let { return it }
            }
        }
        return 0
    }

    private fun extractStringFrom(source: JSONObject?, vararg paths: String): String {
        if (source == null) return ""
        for (path in paths) {
            val value = resolvePath(source, path)
            val text = value?.toString()?.trim().orEmpty()
            if (text.isNotBlank()) return text
        }
        return ""
    }

    private fun extractOrderDetails(values: JSONObject): List<CreateOrderQrResponse.OrderDetail> {
        val arrays = listOf(
            values.optJSONArray("taPedidoDetalle"),
            values.optJSONArray("taPedidoDetalles"),
            values.optJSONObject("taPedido")?.optJSONArray("taPedidoDetalle"),
            values.optJSONObject("pedido")?.optJSONArray("taPedidoDetalle")
        )

        val details = mutableListOf<CreateOrderQrResponse.OrderDetail>()
        arrays.forEach { array ->
            if (array == null) return@forEach
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val orderDetailId = extractIntFrom(
                    item,
                    "tnPedidoDetalle",
                    "tnPedidoDetalleId"
                )
                if (orderDetailId <= 0) continue
                val planogramCellId = extractIntFrom(
                    item,
                    "tnPlanogramaCelda",
                    "tnPlanogramaCeldaId"
                )
                details += CreateOrderQrResponse.OrderDetail(
                    orderDetailId = orderDetailId,
                    planogramCellId = planogramCellId
                )
            }
        }
        return details.distinctBy { it.orderDetailId }
    }

    private fun parseCatalogCells(celdasJson: JSONArray): List<CatalogResponse.Cell> {
        val cells = mutableListOf<CatalogResponse.Cell>()

        for (i in 0 until celdasJson.length()) {
            val celda = celdasJson.optJSONObject(i) ?: continue
            val producto = celda.optJSONObject("taProducto") ?: celda.optJSONObject("producto")
            val inventario = celda.optJSONObject("taInventario") ?: celda.optJSONObject("inventario")

            val esActiva = celda.optInt("tnEsActiva", 0) == 1
            val estadoCeldaActiva = celda.optInt("tnEstado", 0) == 1
            val productoActivo = producto?.optInt("tnEstado", 0) == 1
            val inventarioCantidad = inventario?.optInt("tnCantidad", 0) ?: 0
            val reservadas = inventario?.optInt("tnCantidadReservada", 0) ?: 0
            val stockDisponible = (inventarioCantidad - reservadas).coerceAtLeast(0)

            val nombreProducto = producto?.optString("tcNombre", "")?.trim().orEmpty()
            val precio = producto?.optDouble("tnPrecio", 0.0) ?: 0.0
            val codigo = celda.optString("tcCodigo", "--")
            val planogramaCeldaId = when {
                celda.has("tnPlanogramaCelda") -> celda.optInt("tnPlanogramaCelda", 0)
                celda.has("tnCelda") -> celda.optInt("tnCelda", 0)
                else -> 0
            }
            val productoId = when {
                producto?.has("tnProducto") == true -> producto.optInt("tnProducto", 0)
                celda.has("tnProducto") -> celda.optInt("tnProducto", 0)
                else -> 0
            }
            val (principalId, principalRemoteUrl) = extractPrimaryImage(producto)
            val (secondaryId, secondaryRemoteUrl) = extractSecondaryImage(producto)

            cells += CatalogResponse.Cell(
                planogramCellId = planogramaCeldaId,
                productId = productoId,
                cellCode = codigo,
                productName = if (nombreProducto.isBlank()) "Sin producto" else nombreProducto,
                price = precio,
                availableStock = stockDisponible,
                vendible = esActiva && estadoCeldaActiva && producto != null && productoActivo,
                physicalCell = mapCellCodeToPhysical(codigo) ?: 0,
                imageUrl = principalRemoteUrl,
                secondaryImageUrl = secondaryRemoteUrl,
                imageId = principalId,
                secondaryImageId = secondaryId,
                sourceCellId = celda.optInt("tnCelda", i)
            )
        }

        return cells
    }

    private fun parsePromotions(values: JSONObject): List<CatalogResponse.Promotion> {
        val source =
            values.optJSONArray("taPresentacionArchivos")
                ?: values.optJSONObject("presentacion")?.optJSONArray("taPresentacionArchivos")
                ?: values.optJSONObject("planograma")?.optJSONArray("taPresentacionArchivos")
                ?: values.optJSONObject("taPresentacion")?.optJSONArray("taPresentacionArchivos")
                ?: return emptyList()

        val slides = mutableListOf<CatalogResponse.Promotion>()
        for (index in 0 until source.length()) {
            val item = source.optJSONObject(index) ?: continue
            val status = item.optInt("tnEstado", 0)
            if (status != 1) continue
            val usageType = item.optString("tcUsoTipo", "").trim()
            if (usageType.isNotBlank() && !usageType.equals("PROMOCIONAL", ignoreCase = true)) continue
            val mimeType = item.optString("tcMimeType", "").trim()
            if (mimeType.isNotBlank() && !mimeType.startsWith("image/", ignoreCase = true)) continue
            val remoteUrl = item.optString("tcUrl", "").trim()
            if (remoteUrl.isBlank()) continue
            val presentationId = item.optInt("tnPresentacionArchivo", index)

            slides += CatalogResponse.Promotion(
                url = remoteUrl,
                visualOrder = item.optInt("tnOrdenVisual", Int.MAX_VALUE),
                id = presentationId
            )
        }

        return slides.sortedWith(compareBy<CatalogResponse.Promotion> { it.visualOrder }.thenBy { it.id })
    }

    private fun parseUiBackground(values: JSONObject): Pair<Int, String> {
        val source =
            values.optJSONObject("taFondoUiPrincipal")
                ?: values.optJSONObject("presentacion")?.optJSONObject("taFondoUiPrincipal")
                ?: values.optJSONObject("planograma")?.optJSONObject("taFondoUiPrincipal")
                ?: return 0 to ""

        val status = source.optInt("tnEstado", 0)
        if (status != 1) return 0 to ""
        val usageType = source.optString("tcUsoTipo", "").trim()
        if (usageType.isNotBlank() && !usageType.equals("FONDO_PLANOGRAMA", ignoreCase = true)) return 0 to ""
        val mimeType = source.optString("tcMimeType", "").trim()
        if (mimeType.isNotBlank() && !mimeType.startsWith("image/", ignoreCase = true)) return 0 to ""
        val remoteUrl = source.optString("tcUrl", "").trim()
        if (remoteUrl.isBlank()) return 0 to ""
        return source.optInt("tnPresentacionArchivo", 0) to remoteUrl
    }

    private fun extractPrimaryImage(producto: JSONObject?): Pair<Int, String> {
        if (producto == null) return 0 to ""
        val principal = producto.optJSONObject("taImagenPrincipal")
        val principalId = principal?.optInt("tnProductoArchivo", 0) ?: 0
        val principalUrl = principal?.optString("tcUrl", "")?.trim().orEmpty()
        if (principalUrl.isNotBlank()) return principalId to principalUrl
        return 0 to producto.optString("tcImagenUrlPrincipal", "")?.trim().orEmpty()
    }

    private fun extractSecondaryImage(producto: JSONObject?): Pair<Int, String> {
        if (producto == null) return 0 to ""
        val secondaries = producto.optJSONArray("taImagenesSecundarias")
        if (secondaries != null) {
            var chosen: JSONObject? = null
            var chosenOrder = Int.MAX_VALUE
            for (index in 0 until secondaries.length()) {
                val candidate = secondaries.optJSONObject(index) ?: continue
                val candidateUrl = candidate.optString("tcUrl", "").trim()
                if (candidateUrl.isBlank()) continue
                val order = candidate.optInt("tnOrdenVisual", Int.MAX_VALUE)
                if (chosen == null || order < chosenOrder) {
                    chosen = candidate
                    chosenOrder = order
                }
            }
            if (chosen != null) {
                val id = chosen.optInt("tnProductoArchivo", 0)
                val url = chosen.optString("tcUrl", "").trim()
                return id to url
            }
        }

        val fallback = producto.optString("tcImagenUrlSecundaria", "").trim()
        return 0 to fallback
    }

    private fun mapCellCodeToPhysical(code: String): Int? {
        val clean = code.trim().uppercase()
        clean.toIntOrNull()?.let { direct ->
            if (direct in 10..68) return direct
        }

        val match = Regex("^([A-F])(\\d{1,2})$").find(clean) ?: return null
        val letter = match.groupValues[1][0]
        val column = match.groupValues[2].toIntOrNull() ?: return null
        if (column !in 1..9) return null

        val rowIndex = letter - 'A'
        if (rowIndex !in 0..5) return null

        return ((rowIndex + 1) * 10) + (column - 1)
    }

    private fun parseCellCode(code: String): Pair<String, Int> {
        val match = Regex("^([A-Za-z]+)(\\d+)$").find(code.trim())
        if (match != null) {
            val row = match.groupValues[1].uppercase()
            val column = match.groupValues[2].toIntOrNull() ?: Int.MAX_VALUE
            return row to column
        }
        return code.uppercase() to Int.MAX_VALUE
    }

    private fun resolvePath(source: JSONObject, path: String): Any? {
        val parts = path.split(".")
        var current: Any? = source
        for (part in parts) {
            current = when (current) {
                is JSONObject -> if (current.has(part) && !current.isNull(part)) current.opt(part) else null
                else -> null
            }
            if (current == null) return null
        }
        return current
    }

    private fun buildCreateOrderQrBackendErrorMessage(
        statusCode: Int,
        rawBody: String,
        fallbackMessage: String
    ): String {
        return try {
            val json = JSONObject(rawBody)
            val message = json.optString("message", fallbackMessage).ifBlank { fallbackMessage }
            val errorsObj = json.optJSONObject("errors")
            if (errorsObj == null || errorsObj.length() == 0) {
                "$message (HTTP $statusCode)"
            } else {
                val details = mutableListOf<String>()
                val keys = errorsObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = errorsObj.opt(key)
                    when (value) {
                        is JSONArray -> {
                            val joined = (0 until value.length()).joinToString("; ") { idx ->
                                value.optString(idx)
                            }
                            details += "$key: $joined"
                        }

                        else -> details += "$key: ${value?.toString().orEmpty()}"
                    }
                }
                "$message\n${details.joinToString("\n")}".trim()
            }
        } catch (_: Exception) {
            if (rawBody.isBlank()) "$fallbackMessage (HTTP $statusCode)" else rawBody
        }
    }
}

class CreateOrderQrGatewayException(
    override val message: String
) : Exception(message)

class PaymentMethodsGatewayException(
    override val message: String,
    val unauthorized: Boolean = false
) : Exception(message)

class CatalogGatewayException(
    override val message: String,
    val unauthorized: Boolean = false
) : Exception(message)
