package com.vending.kiosk.app.ui.catalog

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.vending.kiosk.R

data class CartDialogLine(
    val cellId: Int,
    val nameText: CharSequence,
    val unitPriceText: CharSequence,
    val subtotalText: CharSequence,
    val quantityText: CharSequence,
    val imageUrl: String
)

class CartDialogView(
    context: Context,
    private val loadProductImage: (String, ImageView) -> Unit,
    private val onLineDecreaseRequested: (Int) -> Unit,
    private val onLineIncreaseRequested: (Int) -> Unit,
    private val onLineRemoveRequested: (Int) -> Unit,
    private val onClearRequested: () -> Unit,
    private val onBuyRequested: () -> Unit,
    private val onCloseRequested: () -> Unit,
    private val onUserInteraction: () -> Unit
) {
    val root: View = LayoutInflater.from(context).inflate(R.layout.dialog_cart, null)

    private val itemsContainer = root.findViewById<LinearLayout>(R.id.llCartItemsContainer)
    private val total = root.findViewById<TextView>(R.id.tvCartDialogTotal)
    private val timer = root.findViewById<TextView>(R.id.tvCartDialogTimer)

    init {
        timer.setBackgroundColor(Color.TRANSPARENT)
        timer.setTextColor(Color.WHITE)
        timer.textSize = 20f
        timer.setShadowLayer(2f, 0f, 1f, Color.parseColor("#80000000"))

        root.findViewById<Button>(R.id.btnCartClear).setOnClickListener {
            onUserInteraction()
            onClearRequested()
        }
        root.findViewById<Button>(R.id.btnCartClose).setOnClickListener {
            onUserInteraction()
            onCloseRequested()
        }
        root.findViewById<Button>(R.id.btnCartBuy).setOnClickListener {
            onUserInteraction()
            onBuyRequested()
        }
        root.setOnTouchListener { _, _ ->
            onUserInteraction()
            false
        }
    }

    fun render(lines: List<CartDialogLine>, totalText: CharSequence) {
        itemsContainer.removeAllViews()
        val inflater = LayoutInflater.from(root.context)
        lines.forEachIndexed { index, line ->
            val itemView = inflater.inflate(R.layout.item_cart_line, itemsContainer, false)
            itemView.findViewById<TextView>(R.id.tvCartItemName).text = line.nameText
            itemView.findViewById<TextView>(R.id.tvCartItemPrice).text = line.unitPriceText
            itemView.findViewById<TextView>(R.id.tvCartItemSubtotal).text = line.subtotalText
            itemView.findViewById<TextView>(R.id.tvCartQty).text = line.quantityText
            loadProductImage(line.imageUrl, itemView.findViewById(R.id.ivCartItemPreview))
            itemView.findViewById<View>(R.id.vCartItemDivider).visibility =
                if (index == lines.lastIndex) View.GONE else View.VISIBLE

            itemView.findViewById<ImageButton>(R.id.btnCartQtyMinus).setOnClickListener {
                onUserInteraction()
                onLineDecreaseRequested(line.cellId)
            }
            itemView.findViewById<ImageButton>(R.id.btnCartQtyPlus).setOnClickListener {
                onUserInteraction()
                onLineIncreaseRequested(line.cellId)
            }
            itemView.findViewById<ImageButton>(R.id.btnCartRemove).setOnClickListener {
                onUserInteraction()
                onLineRemoveRequested(line.cellId)
            }
            itemsContainer.addView(itemView)
        }
        total.text = totalText
    }

    fun renderTimer(timerText: CharSequence) {
        timer.text = timerText
    }
}
