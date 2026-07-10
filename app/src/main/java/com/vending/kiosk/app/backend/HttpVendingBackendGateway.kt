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

class HttpVendingBackendGateway(
    private val sessionManager: AuthSessionManager
) : VendingBackendGateway {

    override suspend fun fetchCatalog(machineId: Long): CatalogResponse = notImplemented()

    override suspend fun fetchEnabledPaymentMethods(): List<PaymentMethod> = notImplemented()

    override suspend fun createOrderQr(request: CreateOrderQrRequest): CreateOrderQrResponse = notImplemented()

    override suspend fun fetchPaymentStatus(orderId: Long): PaymentStatus = notImplemented()

    override suspend fun cancelOrder(orderId: Long): CancelOrderResult = notImplemented()

    override suspend fun reportDispenseStatus(request: DispenseStatusRequest): DispenseStatusResult = notImplemented()

    private fun notImplemented(): Nothing {
        throw NotImplementedError(
            "HttpVendingBackendGateway is a Phase A Part 1 skeleton and is not wired yet."
        )
    }
}
