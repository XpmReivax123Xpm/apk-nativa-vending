package com.vending.kiosk.app.ui.catalog

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import com.vending.kiosk.R

class ProductDialogView(
    context: android.content.Context,
    private val loadProductImage: (String, ImageView) -> Unit,
    private val onDecreaseRequested: () -> Unit,
    private val onIncreaseRequested: () -> Unit,
    private val onAddToCartRequested: () -> Unit,
    private val onBuyNowRequested: () -> Unit,
    private val onCloseRequested: () -> Unit,
    private val onUserInteraction: () -> Unit
) {
    val root: View = LayoutInflater.from(context).inflate(R.layout.dialog_product_detail, null)

    private val productCode = root.findViewById<TextView>(R.id.tvDialogProductCode)
    private val productName = root.findViewById<TextView>(R.id.tvDialogProductName)
    private val productPrice = root.findViewById<TextView>(R.id.tvDialogProductPrice)
    private val productStock = root.findViewById<TextView>(R.id.tvDialogProductStock)
    private val productTotal = root.findViewById<TextView>(R.id.tvDialogProductTotal)
    private val quantity = root.findViewById<TextView>(R.id.tvDialogQty)
    private val timer = root.findViewById<TextView>(R.id.tvProductDialogTimer)
    private val productImage = root.findViewById<ImageView>(R.id.ivProductPreview)

    init {
        timer.setBackgroundColor(Color.TRANSPARENT)
        timer.setTextColor(Color.WHITE)

        root.findViewById<TextView>(R.id.btnProductDialogClose).apply {
            text = "X"
            setTextColor(Color.WHITE)
            setOnClickListener { onCloseRequested() }
        }
        root.findViewById<ImageButton>(R.id.btnQtyMinus).setOnClickListener {
            onUserInteraction()
            onDecreaseRequested()
        }
        root.findViewById<ImageButton>(R.id.btnQtyPlus).setOnClickListener {
            onUserInteraction()
            onIncreaseRequested()
        }
        root.findViewById<Button>(R.id.btnAddCart).setOnClickListener {
            onUserInteraction()
            onAddToCartRequested()
        }
        root.findViewById<Button>(R.id.btnBuyNow).setOnClickListener {
            onUserInteraction()
            onBuyNowRequested()
        }
        root.setOnTouchListener { _, _ ->
            onUserInteraction()
            false
        }
    }

    fun renderProduct(
        codeText: CharSequence,
        nameText: CharSequence,
        priceText: CharSequence,
        stockText: CharSequence,
        imageUrl: String
    ) {
        productCode.text = codeText
        productName.text = nameText
        productName.isSelected = true
        productPrice.text = priceText
        productStock.text = stockText
        loadProductImage(imageUrl, productImage)
    }

    fun renderQuantityAndTotal(quantityText: CharSequence, totalText: CharSequence) {
        quantity.text = quantityText
        productTotal.text = totalText
    }

    fun renderTimer(timerText: CharSequence) {
        timer.text = timerText
    }
}
