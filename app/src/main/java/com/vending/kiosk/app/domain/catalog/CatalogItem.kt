package com.vending.kiosk.app.domain.catalog

data class CatalogItem(
    val planogramCellId: Int,
    val productId: Int,
    val cellCode: String,
    val name: String,
    val unitPrice: Double,
    val availableStock: Int,
    val isVendible: Boolean,
    val primaryImageUrl: String,
    val secondaryImageUrl: String,
    val imageId: Int = 0,
    val secondaryImageId: Int = 0,
    val physicalCell: Int
)
