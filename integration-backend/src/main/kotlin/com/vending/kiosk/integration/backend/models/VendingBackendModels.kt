package com.vending.kiosk.integration.backend.models

data class CatalogResponse(
    val cells: List<Cell>,
    val backgroundImageUrl: String,
    val backgroundImageId: Int = 0
) {
    data class Cell(
        val planogramCellId: Int,
        val productId: Int,
        val cellCode: String,
        val productName: String,
        val price: Double,
        val availableStock: Int,
        val vendible: Boolean,
        val physicalCell: Int,
        val imageUrl: String,
        val secondaryImageUrl: String,
        val imageId: Int = 0,
        val secondaryImageId: Int = 0,
        val sourceCellId: Int = 0
    )

}

data class PaymentMethod(
    val id: Int,
    val label: String,
    /**
     * Cómo cobra la pasarela: `QR` (el cliente escanea un código) o `CELULAR` (el cobro viaja a su
     * teléfono y él lo cierra ahí). Lo manda el backend en `tcFormaDeCobro`; una pasarela que no
     * sepa cobrar de ninguna forma no llega en esta lista.
     */
    val formaDeCobro: String = FORMA_QR,
    /** Slug de la pasarela que cobra (`tcSlugPasarela`): es lo que identifica al proveedor. */
    val slugPasarela: String = "",
    /**
     * El cobro pide además el código que el banco le da al cliente (`tlRequiereCodigo`): sin ese
     * código el pago no se cierra, así que el kiosko lo teclea antes de confirmar.
     */
    val requiereCodigo: Boolean = false,
    val isDispenseTest: Boolean = false
) {
    /** El cobro se cierra en el teléfono del cliente: hay que pedir su número antes de cobrar. */
    val cobraEnElCelular: Boolean get() = formaDeCobro.equals(FORMA_CELULAR, ignoreCase = true)

    companion object {
        const val FORMA_QR = "QR"
        const val FORMA_CELULAR = "CELULAR"
    }
}

data class CreateOrderQrRequest(
    val machineId: Int,
    val paymentMethodId: Int,
    /**
     * La clave del alta del pedido: el reintento de la MISMA compra va con la misma clave y el
     * backend devuelve el pedido que ya existe en vez de abrir otro. Vacia = sin proteccion.
     */
    val idempotencyKey: String = "",
    val customerName: String,
    val customerPhone: String,
    val customerCi: String,
    val items: List<Item>
) {
    data class Item(
        val planogramCellId: Int,
        val productId: Int,
        val quantity: Int
    )
}

data class CreateOrderQrResponse(
    val orderId: Int,
    /**
     * El id del PAGO: es con el que se consulta el estado del cobro (el pedido no se sondea).
     */
    val paymentId: Int = 0,
    val qrBase64: String,
    val expiration: String,
    val details: List<OrderDetail>,
    val serverNowMs: Long? = null
) {
    data class OrderDetail(
        val orderDetailId: Int,
        val planogramCellId: Int
    )
}

sealed interface PaymentStatus {
    data object Paid : PaymentStatus
    data class Pending(val message: String) : PaymentStatus
    data class Cancelled(val message: String) : PaymentStatus
    data class Failed(val message: String) : PaymentStatus
    data class Error(val message: String) : PaymentStatus
}

sealed interface CancelOrderResult {
    data class Success(val message: String) : CancelOrderResult
    data class Error(val message: String) : CancelOrderResult
}

/** El resultado de confirmar el cobro con el código que el banco le dio al cliente. */
sealed interface ConfirmPaymentCodeResult {
    data object Success : ConfirmPaymentCodeResult
    data class Error(val message: String) : ConfirmPaymentCodeResult
}

data class DispenseStatusRequest(
    val orderId: Int,
    val orderDetailId: Int,
    val planogramCellId: Int,
    val dispenseStatusId: Int,
    val dispenseStatus: String
)

/**
 * Lo que respondio el backend al reporte de estado de UNA unidad.
 *
 * Son TRES casos y no dos a proposito: un fallo temporal (red caida, token vencido, 5xx) se reintenta
 * desde la cola de pendientes de la APK, mientras que un rechazo del backend (esa linea no es de este
 * pedido, la celda no coincide) da lo mismo por mas veces que se repita.
 */
sealed interface DispenseStatusResult {
    data object Success : DispenseStatusResult
    data class Rechazada(val message: String) : DispenseStatusResult
    data class Error(val message: String) : DispenseStatusResult
}

/**
 * Lo que respondio el backend a la bitacora de una unidad.
 *
 * Son TRES casos y no dos a proposito: un fallo temporal (la red del local) se reintenta en la
 * proxima subida, mientras que un rechazo del backend (esa linea no es de este pedido, el cuerpo no
 * es el gzip, no hay sesion valida) da lo mismo por mas veces que se repita.
 */
sealed interface BitacoraUploadResult {
    data object Success : BitacoraUploadResult
    data class Rechazada(val message: String) : BitacoraUploadResult
    data class Error(val message: String) : BitacoraUploadResult
}
