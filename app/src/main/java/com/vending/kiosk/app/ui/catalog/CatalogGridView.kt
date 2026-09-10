package com.vending.kiosk.app.ui.catalog

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.vending.kiosk.R

data class CatalogGridItem<T>(
    val source: T,
    val cellCode: String,
    val productName: String,
    val priceText: String,
    val imageUrl: String,
    val isAvailable: Boolean
)

class CatalogGridView<T>(
    private val contentContainer: LinearLayout,
    private val loadProductImage: (String, ImageView) -> Unit,
    private val onProductTapped: (T) -> Unit
) {
    fun render(items: List<CatalogGridItem<T>>) {
        contentContainer.removeAllViews()

        val layoutInflater = LayoutInflater.from(contentContainer.context)
        val visibleItems = items.take(ITEMS_PER_PAGE)

        for (rowIndex in 0 until ROWS) {
            val row = LinearLayout(contentContainer.context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                ).also {
                    it.bottomMargin = if (rowIndex == ROWS - 1) 0 else dp(8)
                }
            }

            for (columnIndex in 0 until COLUMNS) {
                val cellIndex = rowIndex * COLUMNS + columnIndex

                if (cellIndex < visibleItems.size) {
                    val item = visibleItems[cellIndex]
                    val card = layoutInflater.inflate(R.layout.item_catalog_cell, row, false)

                    card.findViewById<TextView>(R.id.tvCellCode).text = item.cellCode
                    card.findViewById<TextView>(R.id.tvCellProduct).text = item.productName
                    card.findViewById<TextView>(R.id.tvCellPrice).text = item.priceText

                    loadProductImage(
                        item.imageUrl,
                        card.findViewById(R.id.ivCellProductImage)
                    )

                    card.findViewById<TextView>(R.id.tvCellState).apply {
                        if (item.isAvailable) {
                            visibility = View.GONE
                        } else {
                            visibility = View.VISIBLE
                            text = "No disponible"
                            setBackgroundResource(R.drawable.bg_catalog_unavailable_badge)
                            setTextColor(Color.parseColor("#F28E1B"))
                        }
                    }

                    card.alpha = if (item.isAvailable) 1f else 0.78f
                    card.setOnClickListener { onProductTapped(item.source) }

                    card.layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        1f
                    ).apply {
                        val horizontalMargin = dp(5)

                        if (columnIndex == 0) {
                            setMargins(0, 0, horizontalMargin, 0)
                        } else {
                            setMargins(horizontalMargin, 0, 0, 0)
                        }
                    }

                    row.addView(card)
                } else {
                    val spacer = View(contentContainer.context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            1f
                        ).apply {
                            val horizontalMargin = dp(5)

                            if (columnIndex == 0) {
                                setMargins(0, 0, horizontalMargin, 0)
                            } else {
                                setMargins(horizontalMargin, 0, 0, 0)
                            }
                        }
                    }

                    row.addView(spacer)
                }
            }

            contentContainer.addView(row)
        }
    }

    private fun dp(value: Int): Int {
        return (value * contentContainer.resources.displayMetrics.density).toInt()
    }

    companion object {
        const val COLUMNS = 2
        const val ROWS = 3
        const val ITEMS_PER_PAGE = COLUMNS * ROWS
    }
}