package com.vending.kiosk.app.ui.cart

import androidx.lifecycle.ViewModel
import com.vending.kiosk.app.domain.cart.CartItem
import com.vending.kiosk.app.domain.cart.CartMutation
import com.vending.kiosk.app.domain.cart.CartUseCase
import com.vending.kiosk.app.domain.catalog.CatalogItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CartUiState(
    val items: List<CartItem> = emptyList(),
    val totalUnits: Int = 0,
    val totalAmount: Double = 0.0,
    val isCartOpen: Boolean = false,
    val operationNotApplied: Boolean = false
)

class CartViewModel(
    private val cartUseCase: CartUseCase = CartUseCase()
) : ViewModel() {

    private val _uiState = MutableStateFlow(CartUiState())
    val uiState: StateFlow<CartUiState> = _uiState.asStateFlow()

    fun addProduct(catalogItem: CatalogItem, quantity: Int = 1) {
        applyMutation(cartUseCase.add(_uiState.value.items, catalogItem.toCartItem(quantity)))
    }

    fun increment(planogramCellId: Int) {
        applyMutation(cartUseCase.increment(_uiState.value.items, planogramCellId))
    }

    fun decrement(planogramCellId: Int) {
        applyMutation(cartUseCase.decrement(_uiState.value.items, planogramCellId))
    }

    fun remove(planogramCellId: Int) {
        applyMutation(cartUseCase.remove(_uiState.value.items, planogramCellId))
    }

    fun clear() {
        updateItems(cartUseCase.clear())
    }

    fun syncWithCatalog(catalogItems: List<CatalogItem>) {
        updateItems(cartUseCase.syncWithCatalog(_uiState.value.items, catalogItems))
    }

    fun openCart() {
        _uiState.value = _uiState.value.copy(isCartOpen = true)
    }

    fun closeCart() {
        _uiState.value = _uiState.value.copy(isCartOpen = false)
    }

    fun consumeOperationNotApplied() {
        _uiState.value = _uiState.value.copy(operationNotApplied = false)
    }

    private fun applyMutation(mutation: CartMutation) {
        updateItems(mutation.items, operationNotApplied = !mutation.applied)
    }

    private fun updateItems(items: List<CartItem>, operationNotApplied: Boolean = false) {
        _uiState.value = _uiState.value.copy(
            items = items,
            totalUnits = cartUseCase.totalUnits(items),
            totalAmount = cartUseCase.totalAmount(items),
            operationNotApplied = operationNotApplied
        )
    }

    private fun CatalogItem.toCartItem(quantity: Int): CartItem = CartItem(
        planogramCellId = planogramCellId,
        productId = productId,
        cellCode = cellCode,
        name = name,
        unitPrice = unitPrice,
        availableStock = availableStock,
        isVendible = isVendible,
        physicalCell = physicalCell,
        primaryImageUrl = primaryImageUrl,
        secondaryImageUrl = secondaryImageUrl,
        quantity = quantity
    )
}
