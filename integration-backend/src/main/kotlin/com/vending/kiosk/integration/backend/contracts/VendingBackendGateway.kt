package com.vending.kiosk.integration.backend.contracts

import com.vending.kiosk.integration.backend.models.CancelOrderResult
import com.vending.kiosk.integration.backend.models.CatalogResponse
import com.vending.kiosk.integration.backend.models.CreateOrderQrRequest
import com.vending.kiosk.integration.backend.models.CreateOrderQrResponse
import com.vending.kiosk.integration.backend.models.DispenseStatusRequest
import com.vending.kiosk.integration.backend.models.DispenseStatusResult
import com.vending.kiosk.integration.backend.models.PaymentMethod
import com.vending.kiosk.integration.backend.models.PaymentStatus

interface VendingBackendGateway {
    suspend fun fetchCatalog(machineId: Long): CatalogResponse
    suspend fun fetchEnabledPaymentMethods(): List<PaymentMethod>
    suspend fun createOrderQr(request: CreateOrderQrRequest): CreateOrderQrResponse
    suspend fun fetchPaymentStatus(orderId: Long): PaymentStatus
    suspend fun cancelOrder(orderId: Long): CancelOrderResult
    suspend fun reportDispenseStatus(request: DispenseStatusRequest): DispenseStatusResult
}
