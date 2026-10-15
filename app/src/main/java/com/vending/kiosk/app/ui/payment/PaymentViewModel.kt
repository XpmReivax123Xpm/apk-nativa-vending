package com.vending.kiosk.app.ui.payment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vending.kiosk.app.data.backend.CreateOrderQrGatewayException
import com.vending.kiosk.app.data.backend.MachineAuthGateway
import com.vending.kiosk.app.data.backend.MachineLoginResult
import com.vending.kiosk.app.data.backend.PaymentMethodsGatewayException
import com.vending.kiosk.app.data.session.AuthSessionManager
import com.vending.kiosk.app.domain.cart.CartItem
import com.vending.kiosk.integration.backend.contracts.VendingBackendGateway
import com.vending.kiosk.integration.backend.models.CancelOrderResult
import com.vending.kiosk.integration.backend.models.ConfirmPaymentCodeResult
import com.vending.kiosk.integration.backend.models.CreateOrderQrRequest
import com.vending.kiosk.integration.backend.models.CreateOrderQrResponse
import com.vending.kiosk.integration.backend.models.PaymentMethod
import com.vending.kiosk.integration.backend.models.PaymentStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

sealed interface PaymentStep {
    data object Closed : PaymentStep
    data object MethodSelection : PaymentStep

    /** El cliente teclea su celular: solo para los métodos que cobran ahí. */
    data object Phone : PaymentStep

    /** El cliente teclea el código del banco: solo para los métodos que cobran ahí y lo piden. */
    data object Code : PaymentStep
    data object Checkout : PaymentStep
    data object Qr : PaymentStep
    data object Completed : PaymentStep
}

sealed interface PaymentTerminalResult {
    data class Paid(val order: CreateOrderQrResponse) : PaymentTerminalResult
    data class Cancelled(val orderId: Int, val message: String) : PaymentTerminalResult
    data class Failed(val message: String) : PaymentTerminalResult
    data class TimedOut(val orderId: Int, val message: String) : PaymentTerminalResult
}

sealed interface PaymentEvent {
    data class Terminal(val result: PaymentTerminalResult) : PaymentEvent
    data class DispenseTestRequested(
        val items: List<CartItem>,
        val paymentMethodLabel: String
    ) : PaymentEvent
    data object SessionLost : PaymentEvent
    data object RefreshCatalogRequested : PaymentEvent
}

data class PaymentUiState(
    val step: PaymentStep = PaymentStep.Closed,
    val items: List<CartItem> = emptyList(),
    val total: Double = 0.0,
    val paymentMethods: List<PaymentMethod> = emptyList(),
    val allowDispenseTest: Boolean = false,
    val selectedPaymentMethod: PaymentMethod? = null,
    /** El celular del cliente, para los métodos que cobran ahí: viaja con el pedido. */
    val customerPhone: String = "",
    /** El código que el banco le da al cliente, para los métodos que lo piden (`tlRequiereCodigo`). */
    val customerCode: String = "",
    val qrOrder: CreateOrderQrResponse? = null,
    val qrExpiresAtMs: Long? = null,
    val isLoadingPaymentMethods: Boolean = false,
    val isCreatingQr: Boolean = false,
    val isCancellingOrder: Boolean = false,
    val statusMessage: String? = null,
    val error: String? = null,
    val sessionLost: Boolean = false,
    val terminalResult: PaymentTerminalResult? = null
)

class PaymentViewModel(
    private val vendingBackendGateway: VendingBackendGateway,
    private val machineAuthGateway: MachineAuthGateway,
    private val authSessionManager: AuthSessionManager,
    private val machineId: Int
) : ViewModel() {

    private val _uiState = MutableStateFlow(PaymentUiState())
    val uiState: StateFlow<PaymentUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<PaymentEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<PaymentEvent> = _events.asSharedFlow()

    private var cachedPaymentMethods: List<PaymentMethod> = emptyList()
    private var cachedPaymentMethodsAtMs = 0L
    private var paymentMethodsLoadJob: Job? = null
    private var paymentPollingJob: Job? = null

    /**
     * La clave con la que se da de alta el pedido de ESTA pantalla de pago.
     *
     * El reintento tiene que volver con la MISMA clave: si el alta llego al backend y la respuesta se
     * perdio (red lenta, intento que volvio con 401), el backend devuelve ese mismo pedido en vez de
     * abrir otro —y de reservar el stock dos veces—. Se renueva cuando cambia la compra (otro carrito u
     * otro metodo de pago) y en cada entrada nueva a la pantalla, que es una compra nueva.
     */
    private var checkoutKey: String = ""
    private var checkoutSignature: String = ""

    fun startPayment(items: List<CartItem>, allowDispenseTest: Boolean = false) {
        if (items.isEmpty()) return

        paymentPollingJob?.cancel()
        checkoutKey = ""
        checkoutSignature = ""
        val snapshot = items.map { it.copy() }
        _uiState.value = PaymentUiState(
            step = PaymentStep.MethodSelection,
            items = snapshot,
            total = snapshot.sumOf { it.unitPrice * it.quantity },
            paymentMethods = if (allowDispenseTest) listOf(dispenseTestPaymentMethod) else emptyList(),
            allowDispenseTest = allowDispenseTest
        )
        loadPaymentMethods()
    }

    fun loadPaymentMethods(forceRefresh: Boolean = false) {
        val state = _uiState.value
        if (state.step != PaymentStep.MethodSelection || state.isLoadingPaymentMethods) return

        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedPaymentMethods.isNotEmpty() &&
            now - cachedPaymentMethodsAtMs < PAYMENT_METHODS_CACHE_TTL_MS
        ) {
            _uiState.value = state.copy(
                paymentMethods = withDispenseTestMethod(cachedPaymentMethods, state.allowDispenseTest),
                error = null
            )
            return
        }

        _uiState.value = state.copy(isLoadingPaymentMethods = true, error = null)
        if (paymentMethodsLoadJob?.isActive == true) return
        paymentMethodsLoadJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { fetchPaymentMethodsWithSingleRefresh() }
            when (result) {
                is PaymentMethodsLoadResult.Success -> {
                    cachedPaymentMethods = result.methods
                    cachedPaymentMethodsAtMs = System.currentTimeMillis()
                    if (_uiState.value.step == PaymentStep.MethodSelection) {
                        _uiState.value = _uiState.value.copy(
                            paymentMethods = withDispenseTestMethod(
                                result.methods,
                                _uiState.value.allowDispenseTest
                            ),
                            isLoadingPaymentMethods = false,
                            error = null
                        )
                    }
                }

                is PaymentMethodsLoadResult.Error -> {
                    if (_uiState.value.step == PaymentStep.MethodSelection) {
                        _uiState.value = _uiState.value.copy(
                            isLoadingPaymentMethods = false,
                            error = result.message
                        )
                        if (result.sessionLost) publishSessionLost()
                    }
                }
            }
        }
    }

    fun prefetchPaymentMethodsIfNeeded() {
        val now = System.currentTimeMillis()
        if (cachedPaymentMethods.isNotEmpty() && now - cachedPaymentMethodsAtMs < PAYMENT_METHODS_CACHE_TTL_MS ||
            paymentMethodsLoadJob?.isActive == true
        ) return

        paymentMethodsLoadJob = viewModelScope.launch {
            when (val result = withContext(Dispatchers.IO) { fetchPaymentMethodsWithSingleRefresh() }) {
                is PaymentMethodsLoadResult.Success -> {
                    cachedPaymentMethods = result.methods
                    cachedPaymentMethodsAtMs = System.currentTimeMillis()
                    if (_uiState.value.step == PaymentStep.MethodSelection) {
                        _uiState.value = _uiState.value.copy(
                            paymentMethods = withDispenseTestMethod(
                                result.methods,
                                _uiState.value.allowDispenseTest
                            ),
                            isLoadingPaymentMethods = false,
                            error = null
                        )
                    }
                }

                is PaymentMethodsLoadResult.Error -> Unit
            }
        }
    }

    fun selectPaymentMethod(paymentMethodId: Int) {
        val method = _uiState.value.paymentMethods.firstOrNull { it.id == paymentMethodId }
        if (method == null) {
            _uiState.value = _uiState.value.copy(error = "Metodo de pago invalido")
            return
        }
        _uiState.value = _uiState.value.copy(selectedPaymentMethod = method, error = null)
    }

    fun continueToCheckout() {
        val state = _uiState.value
        val selectedMethod = state.selectedPaymentMethod
        if (state.step != PaymentStep.MethodSelection || selectedMethod == null ||
            state.paymentMethods.none { it.id == selectedMethod.id }
        ) {
            _uiState.value = state.copy(error = "Selecciona un metodo de pago")
            return
        }

        val siguiente = if (selectedMethod.cobraEnElCelular) PaymentStep.Phone else PaymentStep.Checkout
        _uiState.value = state.copy(step = siguiente, error = null)
    }

    fun returnFromPhone() {
        val state = _uiState.value
        if (state.step == PaymentStep.Phone) {
            _uiState.value = state.copy(step = PaymentStep.MethodSelection, qrOrder = null, error = null)
        }
    }

    /**
     * El código que el banco le dio al cliente, para los métodos que cobran en el celular y lo piden.
     * Es texto —la pasarela decide el formato— así que solo se exige que venga y no pase del tope.
     */
    fun submitCustomerCode(codigo: String) {
        val state = _uiState.value
        if (state.step != PaymentStep.Code) return

        val limpio = codigo.trim()
        if (limpio.isEmpty()) {
            _uiState.value = state.copy(error = CODIGO_VACIO)
            return
        }
        if (limpio.length > CODIGO_MAX) {
            _uiState.value = state.copy(error = CODIGO_LARGO)
            return
        }

        _uiState.value = state.copy(customerCode = limpio, step = PaymentStep.Checkout, error = null)
    }

    fun returnFromCode() {
        val state = _uiState.value
        if (state.step == PaymentStep.Code) {
            // Al volver se descarta el cobro que quedó a medias: si el cliente teclea de nuevo, se
            // confirma sobre un cobro nuevo y no sobre el del código que ya rechazó.
            _uiState.value = state.copy(step = PaymentStep.Phone, qrOrder = null, error = null)
        }
    }

    /**
     * El celular que el cliente teclea cuando el método cobra ahí: el cobro viaja a ese número y
     * queda guardado con la venta, así que no se cobra con uno inventado.
     */
    fun submitCustomerPhone(telefono: String) {
        val state = _uiState.value
        if (state.step != PaymentStep.Phone) return

        val limpio = telefono.filter { it.isDigit() }
        if (!esTelefonoValido(limpio)) {
            _uiState.value = state.copy(error = TELEFONO_INVALIDO)
            return
        }

        // Los métodos que además piden el código del banco llevan al paso del código antes de cobrar.
        val siguiente = if (state.selectedPaymentMethod?.requiereCodigo == true) {
            PaymentStep.Code
        } else {
            PaymentStep.Checkout
        }
        _uiState.value = state.copy(customerPhone = limpio, step = siguiente, error = null)
    }

    fun returnToMethodSelection() {
        val state = _uiState.value
        if (state.step == PaymentStep.Checkout && !state.isCreatingQr) {
            _uiState.value = state.copy(step = PaymentStep.MethodSelection, qrOrder = null, error = null)
        }
    }

    fun closePayment() {
        if (_uiState.value.isCreatingQr || _uiState.value.isCancellingOrder) return
        paymentPollingJob?.cancel()
        _uiState.value = PaymentUiState()
    }

    fun confirmCheckout() {
        val state = _uiState.value
        val method = state.selectedPaymentMethod ?: return
        if (state.step != PaymentStep.Checkout || state.items.isEmpty() || state.isCreatingQr) return

        if (method.isDispenseTest) {
            paymentPollingJob?.cancel()
            val testItems = state.items.map { it.copy() }
            _uiState.value = PaymentUiState()
            _events.tryEmit(
                PaymentEvent.DispenseTestRequested(
                    items = testItems,
                    paymentMethodLabel = method.label
                )
            )
            return
        }

        if (method.requiereCodigo && state.customerCode.isBlank()) {
            _uiState.value = state.copy(step = PaymentStep.Code, error = CODIGO_VACIO)
            return
        }

        // Un código rechazado deja el cobro YA iniciado esperando uno válido: se vuelve a confirmar
        // sobre ESE pago en vez de abrir otro y dejar el anterior colgado.
        val pendingOrder = state.qrOrder?.takeIf { it.paymentId > 0 && method.requiereCodigo }
        if (pendingOrder != null) {
            _uiState.value = state.copy(isCreatingQr = true, error = null)
            viewModelScope.launch {
                when (val confirm = withContext(Dispatchers.IO) {
                    confirmCodeWithSingleRefresh(pendingOrder.paymentId, state.customerCode)
                }) {
                    CodeConfirmResult.Ok -> startWaitingForPayment(pendingOrder, method)
                    is CodeConfirmResult.Rejected -> rejectCode(confirm.message, pendingOrder, confirm.sessionLost)
                }
            }
            return
        }

        _uiState.value = state.copy(isCreatingQr = true, error = null)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                createOrderQrWithSingleRefresh(state.items, method.id, state.customerPhone)
            }
            when (result) {
                is CreateQrResult.Success -> {
                    // El QR es obligatorio solo para los metodos que cobran mostrandolo: uno que cobra
                    // en el celular del cliente no tiene nada que mostrar, solo se espera.
                    if (result.order.qrBase64.isBlank() && !method.cobraEnElCelular) {
                        _uiState.value = _uiState.value.copy(
                            isCreatingQr = false,
                            error = "El metodo de pago no devolvio un codigo QR"
                        )
                        return@launch
                    }

                    // Los métodos que piden código confirman el cobro con él antes de esperar el pago.
                    if (method.requiereCodigo) {
                        when (val confirm = withContext(Dispatchers.IO) {
                            confirmCodeWithSingleRefresh(result.order.paymentId, state.customerCode)
                        }) {
                            CodeConfirmResult.Ok -> Unit
                            is CodeConfirmResult.Rejected -> {
                                rejectCode(confirm.message, result.order, confirm.sessionLost)
                                return@launch
                            }
                        }
                    }

                    startWaitingForPayment(result.order, method)
                }

                is CreateQrResult.Error -> {
                    _uiState.value = _uiState.value.copy(isCreatingQr = false, error = result.message)
                    if (result.sessionLost) publishSessionLost()
                }
            }
        }
    }

    /** Deja la pantalla esperando el pago: se resuelve el vencimiento y arranca el sondeo de siempre. */
    private fun startWaitingForPayment(order: CreateOrderQrResponse, method: PaymentMethod) {
        val qrExpiresAtMs = resolveQrExpirationMs(
            rawExpiration = order.expiration,
            serverNowMs = order.serverNowMs
        )
        _uiState.value = _uiState.value.copy(
            step = PaymentStep.Qr,
            qrOrder = order,
            qrExpiresAtMs = qrExpiresAtMs,
            isCreatingQr = false,
            statusMessage = if (method.cobraEnElCelular) {
                "Esperando que el cliente confirme en su celular..."
            } else {
                "Esperando confirmacion de pago..."
            },
            error = null
        )
        startPaymentPolling(order.paymentId, order.orderId, qrExpiresAtMs)
    }

    /**
     * El banco rechazó el código: el motivo se muestra en el paso del código y el cliente puede
     * volver a teclearlo, sin perder el cobro que ya está iniciado.
     */
    private fun rejectCode(message: String, order: CreateOrderQrResponse, sessionLost: Boolean = false) {
        _uiState.value = _uiState.value.copy(
            step = PaymentStep.Code,
            qrOrder = order,
            isCreatingQr = false,
            error = message
        )
        if (sessionLost) publishSessionLost()
    }

    fun cancelPendingOrder() {
        val order = _uiState.value.qrOrder ?: return
        if (_uiState.value.step != PaymentStep.Qr || _uiState.value.isCancellingOrder) return

        paymentPollingJob?.cancel()
        _uiState.value = _uiState.value.copy(
            isCancellingOrder = true,
            statusMessage = "Cancelando pedido...",
            error = null
        )
        viewModelScope.launch {
            when (val result = withContext(Dispatchers.IO) { cancelOrder(order.orderId) }) {
                is CancelOrderResult.Success -> complete(
                    PaymentTerminalResult.Cancelled(
                        orderId = order.orderId,
                        message = result.message.ifBlank { "Pedido cancelado." }
                    ),
                    refreshCatalog = true
                )

                is CancelOrderResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isCancellingOrder = false,
                        statusMessage = result.message.ifBlank { "No se pudo cancelar el pedido." },
                        error = result.message.ifBlank { "No se pudo cancelar el pedido." }
                    )
                    if (isUnauthorizedMessage(result.message)) {
                        publishSessionLost()
                    } else {
                        startPaymentPolling(order.paymentId, order.orderId, _uiState.value.qrExpiresAtMs)
                    }
                }
            }
        }
    }

    fun consumeSessionLost() {
        _uiState.value = _uiState.value.copy(sessionLost = false)
    }

    override fun onCleared() {
        paymentMethodsLoadJob?.cancel()
        paymentPollingJob?.cancel()
        super.onCleared()
    }

    /**
     * Sondea el COBRO y, si el tiempo se agota, cancela el PEDIDO: son dos ids distintos —el pago
     * tiene su propia fila— y el backend cancela pedidos, no pagos.
     */
    private fun startPaymentPolling(pagoId: Int, pedidoId: Int, qrExpiresAtMs: Long?) {
        paymentPollingJob?.cancel()
        paymentPollingJob = viewModelScope.launch {
            val expiresAtMs = qrExpiresAtMs ?: (System.currentTimeMillis() + PAYMENT_TIMEOUT_MS)
            while (isActive && System.currentTimeMillis() <= expiresAtMs) {
                when (val result = withContext(Dispatchers.IO) { fetchPaymentStatusWithSingleRefresh(pagoId) }) {
                    PaymentPollResult.Paid -> {
                        val order = _uiState.value.qrOrder ?: return@launch
                        complete(PaymentTerminalResult.Paid(order))
                        return@launch
                    }

                    is PaymentPollResult.Pending -> {
                        _uiState.value = _uiState.value.copy(
                            statusMessage = "Esperando confirmacion de pago...",
                            error = null
                        )
                    }

                    is PaymentPollResult.Cancelled -> {
                        complete(PaymentTerminalResult.Cancelled(pedidoId, result.message))
                        return@launch
                    }

                    is PaymentPollResult.Failed -> {
                        complete(PaymentTerminalResult.Failed(result.message))
                        return@launch
                    }

                    is PaymentPollResult.Error -> {
                        _uiState.value = _uiState.value.copy(
                            statusMessage = result.message,
                            error = result.message
                        )
                        if (result.sessionLost) {
                            publishSessionLost()
                            return@launch
                        }
                    }
                }
                delay(PAYMENT_POLL_INTERVAL_MS)
            }

            if (isActive) cancelAfterTimeout(pedidoId)
        }
    }

    private fun resolveQrExpirationMs(rawExpiration: String, serverNowMs: Long?): Long {
        val deviceNowMs = System.currentTimeMillis()
        val referenceNowMs = serverNowMs ?: deviceNowMs
        val parsedExpirationMs = parseQrExpirationMs(rawExpiration, referenceNowMs)
            ?: return deviceNowMs + PAYMENT_TIMEOUT_MS

        val remainingMs = parsedExpirationMs - referenceNowMs
        return deviceNowMs + remainingMs
    }

    private fun parseQrExpirationMs(rawExpiration: String, nowMs: Long): Long? {
        val value = rawExpiration.trim()
        if (value.isBlank()) return null

        value.toLongOrNull()?.let { numericValue ->
            return when {
                numericValue >= 100_000_000_000L -> numericValue
                numericValue >= 1_000_000_000L -> numericValue * 1_000L
                numericValue >= 10_000L -> nowMs + numericValue
                else -> nowMs + (numericValue * 1_000L)
            }
        }

        val normalizedValue = value.replace(
            Regex("\\.(\\d{3})\\d+(?=(Z|[+-]\\d{2}:?\\d{2})?$)"),
            ".$1"
        )

        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSX",
            "yyyy-MM-dd'T'HH:mm:ssX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
            "yyyy-MM-dd'T'HH:mm:ssZ",
            "yyyy-MM-dd'T'HH:mm:ss.SSS",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss.SSS",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "dd/MM/yyyy HH:mm:ss",
            "dd/MM/yyyy HH:mm"
        )

        formats.forEach { pattern ->
            runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = false
                    if (!pattern.contains('X')) {
                        timeZone = TimeZone.getTimeZone(BACKEND_TIME_ZONE_ID)
                    }
                }.parse(normalizedValue)?.time
            }.getOrNull()?.let { return it }
        }

        val durationMatch = Regex("^(\\d{1,3}):(\\d{2})(?::(\\d{2}))?$").matchEntire(normalizedValue)
        if (durationMatch != null) {
            val hasHours = durationMatch.groupValues[3].isNotBlank()
            val hours = if (hasHours) durationMatch.groupValues[1].toLong() else 0L
            val minutes = if (hasHours) durationMatch.groupValues[2].toLong() else durationMatch.groupValues[1].toLong()
            val seconds = if (hasHours) {
                durationMatch.groupValues[3].toLong()
            } else {
                durationMatch.groupValues[2].toLong()
            }
            return nowMs + ((hours * 3600L + minutes * 60L + seconds) * 1000L)
        }

        return null
    }

    private suspend fun cancelAfterTimeout(orderId: Int) {
        _uiState.value = _uiState.value.copy(
            isCancellingOrder = true,
            statusMessage = "Tiempo de espera agotado. Cancelando pedido...",
            error = null
        )
        val result = withContext(Dispatchers.IO) { cancelOrder(orderId) }
        if (result is CancelOrderResult.Error && isUnauthorizedMessage(result.message)) {
            publishSessionLost()
        }
        val message = when (result) {
            is CancelOrderResult.Success -> result.message.ifBlank { "QR vencido. Pedido cancelado." }
            is CancelOrderResult.Error -> "QR vencido. No se pudo notificar la cancelacion."
        }
        complete(PaymentTerminalResult.TimedOut(orderId, message), refreshCatalog = true)
    }

    private suspend fun fetchPaymentMethodsWithSingleRefresh(): PaymentMethodsLoadResult {
        if (resolveValidSession().isNullOrBlank()) {
            return PaymentMethodsLoadResult.Error("Sesion de maquina expirada", sessionLost = true)
        }

        var result = fetchPaymentMethods()
        if (result is PaymentMethodsLoadResult.Error && result.unauthorized) {
            if (resolveValidSession(forceRefresh = true).isNullOrBlank()) {
                return PaymentMethodsLoadResult.Error("Sesion de maquina expirada", sessionLost = true)
            }
            result = fetchPaymentMethods()
        }
        return if (result is PaymentMethodsLoadResult.Error && result.unauthorized) {
            result.copy(sessionLost = true)
        } else {
            result
        }
    }

    private suspend fun fetchPaymentMethods(): PaymentMethodsLoadResult = try {
        val methods = vendingBackendGateway.fetchEnabledPaymentMethods()
            .filter { it.id > 0 && it.label.isNotBlank() }
            .distinctBy { it.id }
        if (methods.isEmpty()) {
            PaymentMethodsLoadResult.Error("No hay servicios de pago validos en la respuesta del proveedor.")
        } else {
            PaymentMethodsLoadResult.Success(methods)
        }
    } catch (exception: PaymentMethodsGatewayException) {
        PaymentMethodsLoadResult.Error(exception.message, unauthorized = exception.unauthorized)
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: Exception) {
        PaymentMethodsLoadResult.Error(
            exception.message ?: "Fallo obteniendo servicios habilitados: sin detalle"
        )
    }

    private suspend fun createOrderQrWithSingleRefresh(
        items: List<CartItem>,
        paymentMethodId: Int,
        telefono: String
    ): CreateQrResult {
        if (resolveValidSession().isNullOrBlank()) {
            return CreateQrResult.Error("Sesion de maquina expirada", sessionLost = true)
        }

        val clave = checkoutKeyFor(items, paymentMethodId, telefono)
        var result = createOrderQr(items, paymentMethodId, clave, telefono)
        if (result is CreateQrResult.Error && result.unauthorized) {
            if (resolveValidSession(forceRefresh = true).isNullOrBlank()) {
                return CreateQrResult.Error("Sesion de maquina expirada", sessionLost = true)
            }
            result = createOrderQr(items, paymentMethodId, clave, telefono)
        }
        return if (result is CreateQrResult.Error && result.unauthorized) {
            result.copy(sessionLost = true)
        } else {
            result
        }
    }

    /**
     * La clave del alta: la misma mientras la compra sea la misma —mismo carrito, mismo metodo y el
     * mismo celular—, una nueva cuando cambia cualquiera de los tres (el pedido que ya existe en el
     * backend es de OTRA compra, y con otro celular el cobro viajaria al numero viejo).
     */
    private fun checkoutKeyFor(items: List<CartItem>, paymentMethodId: Int, telefono: String): String {
        val firma = items
            .sortedWith(compareBy({ it.planogramCellId }, { it.productId }))
            .joinToString("|") { "${it.planogramCellId}:${it.productId}:${it.quantity}" } + ">$paymentMethodId@$telefono"

        if (firma != checkoutSignature) {
            checkoutSignature = firma
            checkoutKey = "KIOSKO-${UUID.randomUUID()}"
        }
        return checkoutKey
    }

    /** Un movil boliviano son 8 digitos. */
    private fun esTelefonoValido(telefono: String): Boolean =
        telefono.length == 8 && telefono.all { it.isDigit() }

    private suspend fun createOrderQr(
        items: List<CartItem>,
        paymentMethodId: Int,
        idempotencyKey: String,
        telefono: String
    ): CreateQrResult = try {
        CreateQrResult.Success(
            vendingBackendGateway.createOrderQr(
                CreateOrderQrRequest(
                    machineId = machineId,
                    paymentMethodId = paymentMethodId,
                    idempotencyKey = idempotencyKey,
                    customerName = DEFAULT_CUSTOMER_NAME,
                    customerPhone = telefono.ifBlank { DEFAULT_CUSTOMER_PHONE },
                    customerCi = DEFAULT_CUSTOMER_CI_NIT,
                    items = items.map {
                        CreateOrderQrRequest.Item(
                            planogramCellId = it.planogramCellId,
                            productId = it.productId,
                            quantity = it.quantity
                        )
                    }
                )
            )
        )
    } catch (exception: CreateOrderQrGatewayException) {
        CreateQrResult.Error(exception.message, unauthorized = isUnauthorizedMessage(exception.message))
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: Exception) {
        val message = "Fallo de conexion al generar QR: ${exception.message ?: "sin detalle"}"
        CreateQrResult.Error(message, unauthorized = isUnauthorizedMessage(message))
    }

    private suspend fun fetchPaymentStatusWithSingleRefresh(pagoId: Int): PaymentPollResult {
        if (resolveValidSession().isNullOrBlank()) {
            return PaymentPollResult.Error("Sesion de maquina expirada", sessionLost = true)
        }

        var result = fetchPaymentStatus(pagoId)
        if (result is PaymentPollResult.Error && result.unauthorized) {
            if (resolveValidSession(forceRefresh = true).isNullOrBlank()) {
                return PaymentPollResult.Error("Sesion de maquina expirada", sessionLost = true)
            }
            result = fetchPaymentStatus(pagoId)
        }
        return if (result is PaymentPollResult.Error && result.unauthorized) {
            result.copy(sessionLost = true)
        } else {
            result
        }
    }

    private suspend fun fetchPaymentStatus(pagoId: Int): PaymentPollResult = try {
        when (val status = vendingBackendGateway.fetchPaymentStatus(pagoId.toLong())) {
            PaymentStatus.Paid -> PaymentPollResult.Paid
            is PaymentStatus.Pending -> PaymentPollResult.Pending(status.message)
            is PaymentStatus.Cancelled -> PaymentPollResult.Cancelled(status.message)
            is PaymentStatus.Failed -> PaymentPollResult.Failed(status.message)
            is PaymentStatus.Error -> PaymentPollResult.Error(
                message = status.message,
                unauthorized = isUnauthorizedMessage(status.message)
            )
        }
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: Exception) {
        PaymentPollResult.Error("Error consultando estado de pago: ${exception.message ?: "sin detalle"}")
    }

    private suspend fun cancelOrder(orderId: Int): CancelOrderResult = try {
        if (resolveValidSession().isNullOrBlank()) {
            CancelOrderResult.Error("Sesion de maquina expirada")
        } else {
            vendingBackendGateway.cancelOrder(orderId.toLong())
        }
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: Exception) {
        CancelOrderResult.Error("Error cancelando pedido: ${exception.message ?: "sin detalle"}")
    }

    private suspend fun confirmCodeWithSingleRefresh(paymentId: Int, code: String): CodeConfirmResult {
        if (resolveValidSession().isNullOrBlank()) {
            return CodeConfirmResult.Rejected("Sesion de maquina expirada", sessionLost = true)
        }

        var result = confirmCode(paymentId, code)
        if (result is CodeConfirmResult.Rejected && result.unauthorized) {
            if (resolveValidSession(forceRefresh = true).isNullOrBlank()) {
                return CodeConfirmResult.Rejected("Sesion de maquina expirada", sessionLost = true)
            }
            result = confirmCode(paymentId, code)
        }
        return if (result is CodeConfirmResult.Rejected && result.unauthorized) {
            result.copy(sessionLost = true)
        } else {
            result
        }
    }

    private suspend fun confirmCode(paymentId: Int, code: String): CodeConfirmResult = try {
        when (val result = vendingBackendGateway.confirmPaymentWithCode(paymentId.toLong(), code)) {
            is ConfirmPaymentCodeResult.Success -> CodeConfirmResult.Ok
            is ConfirmPaymentCodeResult.Error -> CodeConfirmResult.Rejected(
                message = result.message,
                unauthorized = isUnauthorizedMessage(result.message)
            )
        }
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: Exception) {
        CodeConfirmResult.Rejected("Error confirmando el cobro: ${exception.message ?: "sin detalle"}")
    }

    private fun resolveValidSession(forceRefresh: Boolean = false): String? {
        if (!forceRefresh) authSessionManager.getAuthorizationHeader()?.let { return it }
        return when (machineAuthGateway.refreshSessionWithStoredMachineCredentials(authSessionManager)) {
            is MachineLoginResult.Success -> authSessionManager.getAuthorizationHeader()
            is MachineLoginResult.Error -> null
        }
    }

    private fun complete(result: PaymentTerminalResult, refreshCatalog: Boolean = false) {
        paymentPollingJob?.cancel()
        _uiState.value = _uiState.value.copy(
            step = PaymentStep.Completed,
            isCancellingOrder = false,
            statusMessage = when (result) {
                is PaymentTerminalResult.Paid -> "Pago confirmado"
                is PaymentTerminalResult.Cancelled -> result.message
                is PaymentTerminalResult.Failed -> result.message
                is PaymentTerminalResult.TimedOut -> result.message
            },
            error = null,
            terminalResult = result
        )
        _events.tryEmit(PaymentEvent.Terminal(result))
        if (refreshCatalog) _events.tryEmit(PaymentEvent.RefreshCatalogRequested)
    }

    private fun publishSessionLost() {
        _uiState.value = _uiState.value.copy(sessionLost = true)
        _events.tryEmit(PaymentEvent.SessionLost)
    }

    private fun withDispenseTestMethod(
        methods: List<PaymentMethod>,
        enabled: Boolean
    ): List<PaymentMethod> {
        if (!enabled || methods.any { it.isDispenseTest }) return methods
        return methods + dispenseTestPaymentMethod
    }

    private fun isUnauthorizedMessage(message: String): Boolean =
        message.contains("HTTP 401", ignoreCase = true)

    private sealed interface PaymentMethodsLoadResult {
        data class Success(val methods: List<PaymentMethod>) : PaymentMethodsLoadResult
        data class Error(
            val message: String,
            val unauthorized: Boolean = false,
            val sessionLost: Boolean = false
        ) : PaymentMethodsLoadResult
    }

    private sealed interface CreateQrResult {
        data class Success(val order: CreateOrderQrResponse) : CreateQrResult
        data class Error(
            val message: String,
            val unauthorized: Boolean = false,
            val sessionLost: Boolean = false
        ) : CreateQrResult
    }

    private sealed interface PaymentPollResult {
        data object Paid : PaymentPollResult
        data class Pending(val message: String) : PaymentPollResult
        data class Cancelled(val message: String) : PaymentPollResult
        data class Failed(val message: String) : PaymentPollResult
        data class Error(
            val message: String,
            val unauthorized: Boolean = false,
            val sessionLost: Boolean = false
        ) : PaymentPollResult
    }

    private sealed interface CodeConfirmResult {
        data object Ok : CodeConfirmResult
        data class Rejected(
            val message: String,
            val unauthorized: Boolean = false,
            val sessionLost: Boolean = false
        ) : CodeConfirmResult
    }

    private companion object {
        val dispenseTestPaymentMethod = PaymentMethod(
            id = -1,
            label = "Dispensación de prueba",
            isDispenseTest = true
        )
        const val PAYMENT_POLL_INTERVAL_MS = 5_000L
        const val PAYMENT_TIMEOUT_MS = 120_000L
        const val PAYMENT_METHODS_CACHE_TTL_MS = 120_000L
        const val BACKEND_TIME_ZONE_ID = "America/La_Paz"
        const val DEFAULT_CUSTOMER_NAME = "Sin nombre"
        const val DEFAULT_CUSTOMER_PHONE = "9999999"
        const val DEFAULT_CUSTOMER_CI_NIT = "9999999"

        /** Un movil boliviano son 8 digitos: es lo que se le pide al cliente cuando el cobro va a su celular. */
        const val TELEFONO_INVALIDO = "Teclea un celular de 8 digitos"

        /** El codigo lo emite el banco: es texto y solo se le pone un tope, sin fijarle digitos. */
        const val CODIGO_MAX = 20
        const val CODIGO_VACIO = "Teclea el codigo que te dio el banco"
        const val CODIGO_LARGO = "El codigo tiene como maximo 20 caracteres"
    }
}
