package com.vending.kiosk.app.data.backend

import com.vending.kiosk.app.data.session.AuthSessionManager
import com.vending.kiosk.app.domain.catalog.CatalogItem
import com.vending.kiosk.app.domain.catalog.CatalogUseCase
import com.vending.kiosk.integration.backend.contracts.VendingBackendGateway
import com.vending.kiosk.integration.backend.models.CancelOrderResult
import com.vending.kiosk.integration.backend.models.CatalogResponse
import com.vending.kiosk.integration.backend.models.ConfirmPaymentCodeResult
import com.vending.kiosk.integration.backend.models.CreateOrderQrRequest
import com.vending.kiosk.integration.backend.models.CreateOrderQrResponse
import com.vending.kiosk.integration.backend.models.DispenseStatusRequest
import com.vending.kiosk.integration.backend.models.DispenseStatusResult
import com.vending.kiosk.integration.backend.models.PaymentMethod
import com.vending.kiosk.integration.backend.models.PaymentStatus
import org.json.JSONArray
import org.json.JSONObject

data class CatalogData(
    val items: List<CatalogItem>
)

/**
 * The kiosk's backend: the Laravel API under `/api/v1`.
 *
 * Every call goes through [BackendHttp] with the machine's access token, and the machine NEVER travels
 * in the URL: the token says which machine is asking, so one machine cannot read the planogram of
 * another nor charge against it.
 */
class HttpVendingBackendGateway(
    private val sessionManager: AuthSessionManager
) : VendingBackendGateway {

    /**
     * Lo que la maquina tiene cargado, celda por celda, mas el fondo que tiene puesto.
     *
     * No lleva el id de la maquina —lo dice el token— y la celda trae el CODIGO con el que la maquina
     * la acciona: es de ahi de donde sale la celda fisica que se le manda a la placa.
     */
    override suspend fun fetchCatalog(): CatalogResponse {
        val response = BackendHttp.request("GET", PLANOGRAMA_PATH, sessionManager.getAuthorizationHeader())

        if (!response.isSuccess) {
            throw CatalogGatewayException(
                message = response.errorMessage("Error consultando el planograma"),
                unauthorized = response.isUnauthorized
            )
        }

        val data = response.dataObject()
        if (!data.has("celdas")) {
            throw CatalogGatewayException("Respuesta sin celdas en el planograma")
        }

        val cells = parseCatalogCells(data.optJSONArray("celdas") ?: JSONArray()).sortedWith(
            compareBy<CatalogResponse.Cell> { parseCellCode(it.cellCode).first }
                .thenBy { parseCellCode(it.cellCode).second }
                .thenBy { it.cellCode }
        )
        val background = parseBackground(data.optJSONObject("fondo"))

        return CatalogResponse(
            cells = cells,
            backgroundImageUrl = background.second,
            backgroundImageId = background.first
        )
    }

    suspend fun fetchCatalogData(): CatalogData {
        val catalog = fetchCatalog()

        return CatalogData(
            items = catalog.cells.map { cell ->
                CatalogItem(
                    planogramCellId = cell.planogramCellId,
                    productId = cell.productId,
                    cellCode = cell.cellCode,
                    name = cell.productName,
                    unitPrice = cell.price,
                    availableStock = cell.availableStock,
                    isVendible = CatalogUseCase.isVendible(
                        baseVendible = cell.vendible,
                        availableStock = cell.availableStock,
                        planogramCellId = cell.planogramCellId,
                        productId = cell.productId
                    ),
                    primaryImageUrl = cell.imageUrl,
                    imageId = cell.imageId,
                    physicalCell = cell.physicalCell
                )
            }
        )
    }

    /**
     * Los metodos de pago que ESTA maquina ofrece. La lista ya viene filtrada y normalizada por el
     * backend (los dos interruptores de datos y la pasarela registrada), asi que lo unico que hace el
     * kiosko es pintarla y mandar el id con el que se cobra.
     */
    override suspend fun fetchEnabledPaymentMethods(): List<PaymentMethod> {
        val response = BackendHttp.request("GET", METODOS_PAGO_PATH, sessionManager.getAuthorizationHeader())

        if (!response.isSuccess) {
            throw PaymentMethodsGatewayException(
                response.errorMessage("No se pudo obtener los metodos de pago"),
                unauthorized = response.isUnauthorized
            )
        }

        val methods = response.dataArray().mapNotNull { item ->
            val id = item.optInt("tnMetodoPago", 0)
            val label = textoDe(item, "tcNombreMetodoPago")
            if (id > 0 && label.isNotBlank()) {
                PaymentMethod(
                    id = id,
                    label = label,
                    formaDeCobro = textoDe(item, "tcFormaDeCobro").ifBlank { PaymentMethod.FORMA_QR },
                    slugPasarela = textoDe(item, "tcSlugPasarela"),
                    requiereCodigo = item.optBoolean("tlRequiereCodigo", false)
                )
            } else {
                null
            }
        }.distinctBy { it.id }

        if (methods.isEmpty()) {
            throw PaymentMethodsGatewayException("No hay metodos de pago habilitados para esta maquina.")
        }

        return methods
    }

    /**
     * La compra del kiosko son DOS llamadas del backend: el pedido (con una linea por unidad) y su
     * cobro, que es el que devuelve la forma de pago. La APK las une aca porque su pantalla muestra una
     * sola cosa —el QR— y necesita ademas el id del PAGO para saber como va el cobro.
     */
    override suspend fun createOrderQr(request: CreateOrderQrRequest): CreateOrderQrResponse {
        val pedido = crearPedido(request)
        val pago = iniciarCobro(pedido.id, request.paymentMethodId)

        return CreateOrderQrResponse(
            orderId = pedido.id,
            paymentId = pago.id,
            qrBase64 = pago.qrBase64,
            expiration = pago.expira,
            details = pedido.detalles,
            serverNowMs = pago.serverNowMs
        )
    }

    /**
     * El estado del COBRO: se sondea con el id del pago que devolvio [createOrderQr]. Los ids del
     * catalogo del backend son los mismos que ya entendia el kiosko (2 pagado, 3 cancelado, 4 fallido).
     */
    override suspend fun fetchPaymentStatus(pagoId: Long): PaymentStatus {
        val response = try {
            BackendHttp.request("GET", "$PAGOS_PATH/$pagoId", sessionManager.getAuthorizationHeader())
        } catch (ex: Exception) {
            return PaymentStatus.Error("Error consultando el estado del pago: ${ex.message ?: "sin detalle"}")
        }

        if (!response.isSuccess) {
            return PaymentStatus.Error(response.errorMessage("Consultando el estado del pago"))
        }

        val data = response.dataObject()
        val estado = data.optInt("tnEstadoPago", ESTADO_PAGO_PENDIENTE)

        return when (estado) {
            ESTADO_PAGO_PAGADO -> PaymentStatus.Paid
            ESTADO_PAGO_CANCELADO -> PaymentStatus.Cancelled(textoDe(data, "tcEstadoPago", "Pago cancelado"))
            ESTADO_PAGO_FALLIDO -> PaymentStatus.Failed(textoDe(data, "tcEstadoPago", "Pago fallido"))
            else -> PaymentStatus.Pending("Esperando confirmacion de pago...")
        }
    }

    /**
     * Cancela el PEDIDO de la maquina. El backend solo cancela lo que todavia no se cobro: un pedido
     * pagado responde 422 con el motivo (le toca una devolucion, no una cancelacion).
     */
    override suspend fun cancelOrder(orderId: Long): CancelOrderResult {
        val payload = JSONObject().apply { put("tcMotivo", MOTIVO_CANCELACION_KIOSKO) }

        val response = try {
            BackendHttp.request("POST", "$PEDIDOS_PATH/$orderId/cancelar", sessionManager.getAuthorizationHeader(), payload)
        } catch (ex: Exception) {
            return CancelOrderResult.Error("Error cancelando el pedido: ${ex.message ?: "sin detalle"}")
        }

        return if (response.isSuccess) {
            CancelOrderResult.Success("Pedido cancelado.")
        } else {
            CancelOrderResult.Error(response.errorMessage("No se pudo cancelar el pedido."))
        }
    }

    /**
     * Confirma el COBRO con el codigo que el banco le dio al cliente.
     *
     * Es el cierre de los metodos que cobran en el celular y ademas piden codigo: hasta que el
     * cliente no lo teclee el pago queda pendiente. Con el pago ya confirmado, la espera lo marca
     * pagado y la dispensacion arranca sola.
     */
    override suspend fun confirmPaymentWithCode(paymentId: Long, code: String): ConfirmPaymentCodeResult {
        val payload = JSONObject().apply { put("tcCodigoPago", code) }

        val response = try {
            BackendHttp.request(
                method = "POST",
                path = "$PAGOS_PATH/$paymentId/confirmar",
                authorization = sessionManager.getAuthorizationHeader(),
                body = payload
            )
        } catch (ex: Exception) {
            return ConfirmPaymentCodeResult.Error("Error confirmando el cobro: ${ex.message ?: "sin detalle"}")
        }

        return if (response.isSuccess) {
            ConfirmPaymentCodeResult.Success
        } else {
            ConfirmPaymentCodeResult.Error(response.errorMessage("No se pudo confirmar el cobro con el codigo."))
        }
    }

    /**
     * Reporta lo que la maquina hizo con UNA unidad del pedido.
     *
     * Va fire-and-forget: el kiosko no bloquea la pantalla por un reporte. Lo que se pierda se cierra
     * solo en el backend (el pedido colgado vence a las 24 h).
     */
    override suspend fun reportDispenseStatus(request: DispenseStatusRequest): DispenseStatusResult {
        val authorization = sessionManager.getAuthorizationHeader()
        if (authorization.isNullOrBlank()) {
            return DispenseStatusResult.Error("Sesion de maquina no disponible")
        }

        val payload = JSONObject().apply {
            put("PedidoDetalle", request.orderDetailId)
            put("DispensacionEstado", request.dispenseStatusId)
            if (request.planogramCellId > 0) put("MaquinaCelda", request.planogramCellId)
            if (request.dispenseStatus.isNotBlank()) put("CodigoEvento", request.dispenseStatus)
        }

        val response = try {
            BackendHttp.request(
                method = "POST",
                path = "$PEDIDOS_PATH/${request.orderId}/dispensacion",
                authorization = authorization,
                body = payload
            )
        } catch (ex: Exception) {
            return DispenseStatusResult.Error("Error reportando la dispensacion: ${ex.message ?: "sin detalle"}")
        }

        return if (response.isSuccess) {
            DispenseStatusResult.Success
        } else {
            DispenseStatusResult.Error(response.errorMessage("No se pudo reportar la dispensacion."))
        }
    }

    /** El alta del pedido: una linea por unidad, con la celda del planograma que la maquina dispensara. */
    private fun crearPedido(request: CreateOrderQrRequest): LineasDelPedido {
        val detalles = JSONArray()
        request.items.forEach { item ->
            detalles.put(
                JSONObject().apply {
                    put("MaquinaCelda", item.planogramCellId)
                    put("Producto", item.productId)
                    put("Cantidad", item.quantity)
                }
            )
        }

        val payload = JSONObject().apply {
            put("Detalles", detalles)
            // El comprador es opcional: el backend busca al cliente que ya esta —por documento o por
            // telefono— o lo crea con estos datos.
            if (request.customerName.isNotBlank()) put("tcNombreCliente", request.customerName)
            if (request.customerPhone.isNotBlank()) put("tcTelefonoCliente", request.customerPhone)
            if (request.customerCi.isNotBlank()) put("tcNITCliente", request.customerCi)
            // Con clave, un reintento del mismo alta devuelve el pedido que ya existe: la maquina no
            // abre un segundo pedido —ni reserva dos veces— por una respuesta que se perdio.
            if (request.idempotencyKey.isNotBlank()) put("ClaveIdempotencia", request.idempotencyKey)
        }

        val response = BackendHttp.request(
            method = "POST",
            path = PEDIDOS_PATH,
            authorization = sessionManager.getAuthorizationHeader(),
            body = payload,
            timeoutMs = ORDER_TIMEOUT_MS
        )

        if (!response.isSuccess) {
            throw CreateOrderQrGatewayException(response.errorMessage("No se pudo generar el pedido"))
        }

        val data = response.dataObject()
        val pedidoId = data.optInt("tnPedido", 0)
        if (pedidoId <= 0) {
            throw CreateOrderQrGatewayException("Respuesta sin el id del pedido")
        }

        val lineas = data.optJSONArray("taDetalles") ?: JSONArray()
        val detallesDelPedido = (0 until lineas.length()).mapNotNull { index ->
            val linea = lineas.optJSONObject(index) ?: return@mapNotNull null
            val id = linea.optInt("tnPedidoDetalle", 0)
            if (id <= 0) return@mapNotNull null

            CreateOrderQrResponse.OrderDetail(
                orderDetailId = id,
                planogramCellId = linea.optInt("tnPlanogramaCelda", 0)
            )
        }.distinctBy { it.orderDetailId }

        return LineasDelPedido(id = pedidoId, detalles = detallesDelPedido)
    }

    /** El cobro del pedido: es el que devuelve la forma de pago que la pantalla muestra. */
    private fun iniciarCobro(pedidoId: Int, metodoPago: Int): CobroDelPedido {
        val payload = JSONObject().apply {
            put("Pedido", pedidoId)
            put("MetodoPago", metodoPago)
        }

        val response = BackendHttp.request(
            method = "POST",
            path = PAGOS_PATH,
            authorization = sessionManager.getAuthorizationHeader(),
            body = payload,
            timeoutMs = ORDER_TIMEOUT_MS
        )

        if (!response.isSuccess) {
            throw CreateOrderQrGatewayException(response.errorMessage("No se pudo iniciar el cobro"))
        }

        val data = response.dataObject()
        val pagoId = data.optInt("tnPago", 0)
        val qrBase64 = textoDe(data, "tcQrBase64")

        if (pagoId <= 0) {
            throw CreateOrderQrGatewayException("Respuesta sin el id del pago")
        }

        // El QR puede venir vacio y no es un error del gateway: los metodos que cobran en el celular
        // del cliente no tienen codigo que mostrar. Quien sabe que forma de cobro eligio el cliente
        // es la pantalla, y es ella la que exige el QR cuando corresponde.
        return CobroDelPedido(
            id = pagoId,
            qrBase64 = qrBase64,
            expira = textoDe(data, "ttExpira"),
            serverNowMs = response.serverNowMs
        )
    }

    private fun parseCatalogCells(celdasJson: JSONArray): List<CatalogResponse.Cell> {
        val cells = mutableListOf<CatalogResponse.Cell>()

        for (i in 0 until celdasJson.length()) {
            val celda = celdasJson.optJSONObject(i) ?: continue
            val producto = celda.optJSONObject("producto")

            val codigoCelda = textoDe(celda, "tcCodigoCelda")
            val codigoProducto = textoDe(producto, "tcCodigoProducto")
            val nombreProducto = textoDe(producto, "tcNombreProducto")
                .ifBlank { codigoProducto }
                .ifBlank { "Sin producto" }

            // El backend ya dice cuanto se puede VENDER de la celda: tiene lo cargado menos lo
            // reservado por el otro sistema.
            val disponible = celda.optInt("tnDisponible", 0)
            val celdaOperativa = celda.optBoolean("tlEstado", false)
            val celdaOcupada = celda.optBoolean("tlOcupada", false)
            val productoActivo = producto?.optBoolean("tlEstado", false) == true

            val imagenPrincipal = producto?.optJSONObject("archivoPrincipal")
            val imagenSecundaria = producto?.optJSONObject("archivoSecundarioPrincipal")

            cells += CatalogResponse.Cell(
                planogramCellId = celda.optInt("tnMaquinaCelda", 0),
                productId = producto?.optInt("tnProducto", 0) ?: 0,
                cellCode = codigoCelda.ifBlank { "--" },
                productName = nombreProducto,
                price = producto?.optDouble("tnPrecioVenta", 0.0) ?: 0.0,
                availableStock = disponible,
                vendible = celdaOperativa && celdaOcupada && producto != null && productoActivo,
                physicalCell = mapCellCodeToPhysical(codigoCelda) ?: 0,
                imageUrl = textoDe(imagenPrincipal, "tcUrl"),
                secondaryImageUrl = textoDe(imagenSecundaria, "tcUrl"),
                imageId = imagenPrincipal?.optInt("tnArchivo", 0) ?: 0,
                secondaryImageId = imagenSecundaria?.optInt("tnArchivo", 0) ?: 0,
                sourceCellId = celda.optInt("tnMaquinaCelda", i)
            )
        }

        return cells
    }

    /**
     * Reads a text field the backend may send as an explicit `null`. `JSONObject.optString` answers the
     * four letters "null" for those, and the kiosk would paint them (a machine without location, a
     * product without name). Absent and null both fall back to [porDefecto].
     */
    private fun textoDe(json: JSONObject?, clave: String, porDefecto: String = ""): String =
        if (json == null || json.isNull(clave)) porDefecto else json.optString(clave, porDefecto).trim()

    /**
     * El fondo de planograma que la maquina tiene puesto: la imagen que el kiosko pinta detras de la
     * rejilla. Viene del backend con su id y su URL; sin fondo, el kiosko se pinta como puede.
     */
    private fun parseBackground(fondo: JSONObject?): Pair<Int, String> {
        if (fondo == null) return 0 to ""

        val url = textoDe(fondo.optJSONObject("archivo"), "tcUrl")
        if (url.isBlank()) return 0 to ""

        return (fondo.optInt("tnArchivo", 0)) to url
    }

    /**
     * El codigo de la celda tal como lo manda el backend (`A1`, `C7`) es la posicion en la rejilla, y de
     * ahi sale el numero de salida que acciona la placa: la bandeja es la decena y la columna la unidad.
     *
     * Tambien se acepta un numero ya listo en el rango de la placa (10..68), que es lo que un operador
     * puede haber cargado como codigo de su celda.
     */
    private fun mapCellCodeToPhysical(code: String): Int? {
        val clean = code.trim().uppercase()
        clean.toIntOrNull()?.let { direct ->
            if (direct in PHYSICAL_CELL_RANGE) return direct
        }

        val match = Regex("^([A-Z])(\\d{1,2})$").find(clean) ?: return null
        val bandeja = match.groupValues[1][0]
        val columna = match.groupValues[2].toIntOrNull() ?: return null
        if (columna !in 1..9) return null

        val rowIndex = bandeja - 'A'
        if (rowIndex < 0) return null

        val physical = ((rowIndex + 1) * 10) + (columna - 1)

        return physical.takeIf { it in PHYSICAL_CELL_RANGE }
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

    private data class LineasDelPedido(
        val id: Int,
        val detalles: List<CreateOrderQrResponse.OrderDetail>
    )

    private data class CobroDelPedido(
        val id: Int,
        val qrBase64: String,
        val expira: String,
        val serverNowMs: Long?
    )

    private companion object {
        const val PLANOGRAMA_PATH = "/maquina/planograma"
        const val METODOS_PAGO_PATH = "/maquina/metodos-pago"
        const val PEDIDOS_PATH = "/pedidos"
        const val PAGOS_PATH = "/pagos"
        const val ORDER_TIMEOUT_MS = 14_000

        /** Catalogo `ESTADOPAGO` del backend. */
        const val ESTADO_PAGO_PENDIENTE = 1
        const val ESTADO_PAGO_PAGADO = 2
        const val ESTADO_PAGO_CANCELADO = 3
        const val ESTADO_PAGO_FALLIDO = 4

        /** El motivo con el que queda el pedido de un cliente que se arrepiente o abandona el kiosko. */
        const val MOTIVO_CANCELACION_KIOSKO = "CANCELADO_CLIENTE_APK"

        val PHYSICAL_CELL_RANGE = 10..68
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
