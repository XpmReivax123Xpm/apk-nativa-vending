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
        val columns = 3
        val rows = 5
        val itemsPerPage = columns * rows
        val pageWidth = contentContainer.resources.displayMetrics.widthPixels - dp(24)
        val layoutInflater = LayoutInflater.from(contentContainer.context)

        items.chunked(itemsPerPage).forEachIndexed { pageIndex, pageItems ->
            val page = LinearLayout(contentContainer.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    pageWidth,
                    LinearLayout.LayoutParams.MATCH_PARENT
                ).also {
                    if (pageIndex > 0) it.leftMargin = dp(10)
                }
            }

            for (rowIndex in 0 until rows) {
                val row = LinearLayout(contentContainer.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                    ).also { it.bottomMargin = if (rowIndex == rows - 1) 0 else dp(8) }
                }

                for (columnIndex in 0 until columns) {
                    val cellIndex = rowIndex * columns + columnIndex
                    if (cellIndex < pageItems.size) {
                        val item = pageItems[cellIndex]
                        val card = layoutInflater.inflate(R.layout.item_catalog_cell, row, false)
                        card.findViewById<TextView>(R.id.tvCellCode).text = item.cellCode
                        card.findViewById<TextView>(R.id.tvCellProduct).text = item.productName
                        card.findViewById<TextView>(R.id.tvCellPrice).text = item.priceText
                        loadProductImage(item.imageUrl, card.findViewById(R.id.ivCellProductImage))

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

                        val margin = dp(4)
                        card.layoutParams = LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            1f
                        ).apply {
                            if (columnIndex == 0) {
                                setMargins(0, 0, margin, 0)
                            } else if (columnIndex == columns - 1) {
                                setMargins(margin, 0, 0, 0)
                            } else {
                                setMargins(margin, 0, margin, 0)
                            }
                        }
                        row.addView(card)
                    } else {
                        val spacer = View(contentContainer.context).apply {
                            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
                        }
                        row.addView(spacer)
                    }
                }
                page.addView(row)
            }
            contentContainer.addView(page)
        }
    }

    private fun dp(value: Int): Int {
        return (value * contentContainer.resources.displayMetrics.density).toInt()
    }
}
