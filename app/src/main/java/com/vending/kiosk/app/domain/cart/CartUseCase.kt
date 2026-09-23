package com.vending.kiosk.app.domain.cart

import com.vending.kiosk.app.domain.catalog.CatalogItem

data class CartMutation(
    val items: List<CartItem>,
    val applied: Boolean
)

class CartUseCase {
    fun add(items: List<CartItem>, item: CartItem): CartMutation {
        val existing = items.firstOrNull { it.planogramCellId == item.planogramCellId }
        val requestedQuantity = item.quantity

        if (existing == null) {
            val quantity = minOf(requestedQuantity, item.availableStock)
            if (quantity <= 0) return CartMutation(items, applied = false)

            return CartMutation(items + item.copy(quantity = quantity), applied = true)
        }

        val quantity = minOf(existing.quantity + requestedQuantity, item.availableStock)
        if (quantity <= existing.quantity) return CartMutation(items, applied = false)

        return CartMutation(
            items = items.map {
                if (it.planogramCellId == item.planogramCellId) item.copy(quantity = quantity) else it
            },
            applied = true
        )
    }

    fun increment(items: List<CartItem>, planogramCellId: Int): CartMutation {
        val item = items.firstOrNull { it.planogramCellId == planogramCellId }
            ?: return CartMutation(items, applied = false)

        if (item.quantity >= item.availableStock) return CartMutation(items, applied = false)

        return CartMutation(
            items = items.map {
                if (it.planogramCellId == planogramCellId) it.copy(quantity = it.quantity + 1) else it
            },
            applied = true
        )
    }

    fun decrement(items: List<CartItem>, planogramCellId: Int): CartMutation {
        val item = items.firstOrNull { it.planogramCellId == planogramCellId }
            ?: return CartMutation(items, applied = false)

        val updatedItems = if (item.quantity == 1) {
            items.filterNot { it.planogramCellId == planogramCellId }
        } else {
            items.map {
                if (it.planogramCellId == planogramCellId) it.copy(quantity = it.quantity - 1) else it
            }
        }

        return CartMutation(updatedItems, applied = true)
    }

    fun remove(items: List<CartItem>, planogramCellId: Int): CartMutation {
        val updatedItems = items.filterNot { it.planogramCellId == planogramCellId }
        return CartMutation(updatedItems, applied = updatedItems.size != items.size)
    }

    fun clear(): List<CartItem> = emptyList()

    fun syncWithCatalog(items: List<CartItem>, catalog: List<CatalogItem>): List<CartItem> {
        val catalogByCellId = catalog.associateBy { it.planogramCellId }

        return items.mapNotNull { cartItem ->
            val catalogItem = catalogByCellId[cartItem.planogramCellId] ?: return@mapNotNull null
            if (
                !catalogItem.isVendible ||
                catalogItem.availableStock <= 0 ||
                catalogItem.planogramCellId <= 0 ||
                catalogItem.productId <= 0
            ) {
                return@mapNotNull null
            }

            cartItem.copy(
                productId = catalogItem.productId,
                cellCode = catalogItem.cellCode,
                name = catalogItem.name,
                unitPrice = catalogItem.unitPrice,
                availableStock = catalogItem.availableStock,
                isVendible = catalogItem.isVendible,
                physicalCell = catalogItem.physicalCell,
                primaryImageUrl = catalogItem.primaryImageUrl,
                quantity = minOf(cartItem.quantity, catalogItem.availableStock)
            )
        }.filter { it.quantity > 0 }
    }

    fun totalUnits(items: List<CartItem>): Int = items.sumOf { it.quantity }

    fun totalAmount(items: List<CartItem>): Double = items.sumOf { it.unitPrice * it.quantity }
}
