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

sealed interface PaymentStep {
    data object Closed : PaymentStep
    data object MethodSelection : PaymentStep
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
    data object SessionLost : PaymentEvent
    data object RefreshCatalogRequested : PaymentEvent
}

data class PaymentUiState(
    val step: PaymentStep = PaymentStep.Closed,
    val items: List<CartItem> = emptyList(),
    val total: Double = 0.0,
    val paymentMethods: List<PaymentMethod> = emptyList(),
    val selectedPaymentMethod: PaymentMethod? = null,
    val qrOrder: CreateOrderQrResponse? = null,
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

    fun startPayment(items: List<CartItem>) {
        if (items.isEmpty()) return

        paymentPollingJob?.cancel()
        val snapshot = items.map { it.copy() }
        _uiState.value = PaymentUiState(
            step = PaymentStep.MethodSelection,
            items = snapshot,
            total = snapshot.sumOf { it.unitPrice * it.quantity }
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
            _uiState.value = state.copy(paymentMethods = cachedPaymentMethods, error = null)
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
                            paymentMethods = result.methods,
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
                            paymentMethods = result.methods,
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
        _uiState.value = state.copy(step = PaymentStep.Checkout, error = null)
    }

    fun returnToMethodSelection() {
        val state = _uiState.value
        if (state.step == PaymentStep.Checkout && !state.isCreatingQr) {
            _uiState.value = state.copy(step = PaymentStep.MethodSelection, error = null)
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

        _uiState.value = state.copy(isCreatingQr = true, error = null)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                createOrderQrWithSingleRefresh(state.items, method.id)
            }
            when (result) {
                is CreateQrResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        step = PaymentStep.Qr,
                        qrOrder = result.order,
                        isCreatingQr = false,
                        statusMessage = "Esperando confirmacion de pago...",
                        error = null
                    )
                    startPaymentPolling(result.order.orderId)
                }

                is CreateQrResult.Error -> {
                    _uiState.value = _uiState.value.copy(isCreatingQr = false, error = result.message)
                    if (result.sessionLost) publishSessionLost()
                }
            }
        }
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
                        startPaymentPolling(order.orderId)
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

    private fun startPaymentPolling(orderId: Int) {
        paymentPollingJob?.cancel()
        paymentPollingJob = viewModelScope.launch {
            val startedAtMs = System.currentTimeMillis()
            while (isActive && System.currentTimeMillis() - startedAtMs <= PAYMENT_TIMEOUT_MS) {
                when (val result = withContext(Dispatchers.IO) { fetchPaymentStatusWithSingleRefresh(orderId) }) {
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
                        complete(PaymentTerminalResult.Cancelled(orderId, result.message))
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

            if (isActive) cancelAfterTimeout(orderId)
        }
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
        paymentMethodId: Int
    ): CreateQrResult {
        if (resolveValidSession().isNullOrBlank()) {
            return CreateQrResult.Error("Sesion de maquina expirada", sessionLost = true)
        }

        var result = createOrderQr(items, paymentMethodId)
        if (result is CreateQrResult.Error && result.unauthorized) {
            if (resolveValidSession(forceRefresh = true).isNullOrBlank()) {
                return CreateQrResult.Error("Sesion de maquina expirada", sessionLost = true)
            }
            result = createOrderQr(items, paymentMethodId)
        }
        return if (result is CreateQrResult.Error && result.unauthorized) {
            result.copy(sessionLost = true)
        } else {
            result
        }
    }

    private suspend fun createOrderQr(items: List<CartItem>, paymentMethodId: Int): CreateQrResult = try {
        CreateQrResult.Success(
            vendingBackendGateway.createOrderQr(
                CreateOrderQrRequest(
                    machineId = machineId,
                    paymentMethodId = paymentMethodId,
                    customerName = DEFAULT_CUSTOMER_NAME,
                    customerPhone = DEFAULT_CUSTOMER_PHONE,
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

    private suspend fun fetchPaymentStatusWithSingleRefresh(orderId: Int): PaymentPollResult {
        if (resolveValidSession().isNullOrBlank()) {
            return PaymentPollResult.Error("Sesion de maquina expirada", sessionLost = true)
        }

        var result = fetchPaymentStatus(orderId)
        if (result is PaymentPollResult.Error && result.unauthorized) {
            if (resolveValidSession(forceRefresh = true).isNullOrBlank()) {
                return PaymentPollResult.Error("Sesion de maquina expirada", sessionLost = true)
            }
            result = fetchPaymentStatus(orderId)
        }
        return if (result is PaymentPollResult.Error && result.unauthorized) {
            result.copy(sessionLost = true)
        } else {
            result
        }
    }

    private suspend fun fetchPaymentStatus(orderId: Int): PaymentPollResult = try {
        when (val status = vendingBackendGateway.fetchPaymentStatus(orderId.toLong())) {
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

    private companion object {
        const val PAYMENT_POLL_INTERVAL_MS = 5_000L
        const val PAYMENT_TIMEOUT_MS = 120_000L
        const val PAYMENT_METHODS_CACHE_TTL_MS = 120_000L
        const val DEFAULT_CUSTOMER_NAME = "Sin nombre"
        const val DEFAULT_CUSTOMER_PHONE = "9999999"
        const val DEFAULT_CUSTOMER_CI_NIT = "9999999"
    }
}
