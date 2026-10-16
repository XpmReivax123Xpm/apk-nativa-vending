package com.vending.kiosk.integration.backend.contracts

import com.vending.kiosk.integration.backend.models.CancelOrderResult
import com.vending.kiosk.integration.backend.models.CatalogResponse
import com.vending.kiosk.integration.backend.models.ConfirmPaymentCodeResult
import com.vending.kiosk.integration.backend.models.CreateOrderQrRequest
import com.vending.kiosk.integration.backend.models.CreateOrderQrResponse
import com.vending.kiosk.integration.backend.models.DispenseStatusRequest
import com.vending.kiosk.integration.backend.models.DispenseStatusResult
import com.vending.kiosk.integration.backend.models.BitacoraUploadResult
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

    /**
     * Sube la bitacora de UNA unidad: el gzip del texto que el kiosko escribio durante esa
     * dispensacion.
     *
     * Va aparte del reporte de estado y no lo bloquea: la bitacora es DIAGNOSTICO y el reporte de
     * negocio no puede esperarla. El pedido tiene que ser de la maquina del token (si no, el backend
     * responde 404).
     */
    suspend fun uploadDispensacionBitacora(
        orderId: Int,
        orderDetailId: Int,
        contenido: ByteArray,
        versionApk: String
    ): BitacoraUploadResult
}
