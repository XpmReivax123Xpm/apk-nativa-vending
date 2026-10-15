package com.vending.kiosk.integration.backend.contracts

import com.vending.kiosk.integration.backend.models.CancelOrderResult
import com.vending.kiosk.integration.backend.models.CatalogResponse
import com.vending.kiosk.integration.backend.models.ConfirmPaymentCodeResult
import com.vending.kiosk.integration.backend.models.CreateOrderQrRequest
import com.vending.kiosk.integration.backend.models.CreateOrderQrResponse
import com.vending.kiosk.integration.backend.models.DispenseStatusRequest
import com.vending.kiosk.integration.backend.models.DispenseStatusResult
import com.vending.kiosk.integration.backend.models.PaymentMethod
import com.vending.kiosk.integration.backend.models.PaymentStatus

interface VendingBackendGateway {
    suspend fun fetchCatalog(): CatalogResponse
    suspend fun fetchEnabledPaymentMethods(): List<PaymentMethod>
    suspend fun createOrderQr(request: CreateOrderQrRequest): CreateOrderQrResponse
    suspend fun confirmPaymentWithCode(paymentId: Long, code: String): ConfirmPaymentCodeResult
    suspend fun fetchPaymentStatus(pagoId: Long): PaymentStatus
    suspend fun cancelOrder(orderId: Long): CancelOrderResult
    suspend fun reportDispenseStatus(request: DispenseStatusRequest): DispenseStatusResult
}
