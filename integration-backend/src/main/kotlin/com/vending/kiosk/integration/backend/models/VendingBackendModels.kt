package com.vending.kiosk.integration.backend.models

data class CatalogResponse(
    val cells: List<Cell>,
    val promotions: List<Promotion>,
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

    data class Promotion(
        val url: String,
        val visualOrder: Int,
        val id: Int
    )
}

data class PaymentMethod(
    val id: Int,
    val label: String
)

data class CreateOrderQrRequest(
    val machineId: Int,
    val paymentMethodId: Int,
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
    val qrBase64: String,
    val expiration: String,
    val details: List<OrderDetail>
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

data class DispenseStatusRequest(
    val orderId: Int,
    val orderDetailId: Int,
    val planogramCellId: Int,
    val dispenseStatusId: Int,
    val dispenseStatus: String
)

sealed interface DispenseStatusResult {
    data object Success : DispenseStatusResult
    data class Error(val message: String) : DispenseStatusResult
}
