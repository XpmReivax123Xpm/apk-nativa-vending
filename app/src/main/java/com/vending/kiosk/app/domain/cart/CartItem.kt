package com.vending.kiosk.app.domain.cart

data class CartItem(
    val planogramCellId: Int,
    val productId: Int,
    val cellCode: String,
    val name: String,
    val unitPrice: Double,
    val availableStock: Int,
    val isVendible: Boolean,
    val physicalCell: Int,
    val primaryImageUrl: String,
    val secondaryImageUrl: String,
    val quantity: Int
)
