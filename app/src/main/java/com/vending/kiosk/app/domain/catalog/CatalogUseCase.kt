package com.vending.kiosk.app.domain.catalog

object CatalogUseCase {
    fun calculateAvailableStock(quantity: Int, reserved: Int): Int =
        maxOf(quantity - reserved, 0)

    fun isVendible(
        baseVendible: Boolean,
        availableStock: Int,
        planogramCellId: Int,
        productId: Int
    ): Boolean = baseVendible && availableStock > 0 && planogramCellId > 0 && productId > 0
}
