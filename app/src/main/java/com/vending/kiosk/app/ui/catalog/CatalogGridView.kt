package com.vending.kiosk.app.ui.catalog

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
    private val pagesStrip: LinearLayout,
    private val loadProductImage: (String, ImageView) -> Unit,
    private val onProductTapped: (T) -> Unit
) {
    fun render(items: List<CatalogGridItem<T>>, pageWidth: Int) {
        pagesStrip.removeAllViews()
        items.chunked(ITEMS_PER_PAGE).forEach { pageItems ->
            pagesStrip.addView(createPage(pageItems).apply {
                layoutParams = LinearLayout.LayoutParams(
                    pageWidth,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
            })
        }
    }

    fun createPage(items: List<CatalogGridItem<T>>): View {
        val page = LinearLayout(pagesStrip.context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val layoutInflater = LayoutInflater.from(pagesStrip.context)
        val visibleItems = items.take(ITEMS_PER_PAGE)

        for (rowIndex in 0 until GRID_ROWS) {
            val row = LinearLayout(pagesStrip.context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                ).also {
                    it.bottomMargin = if (rowIndex == GRID_ROWS - 1) 0 else dp(8)
                }
            }

            for (columnIndex in 0 until GRID_COLUMNS) {
                val cellIndex = rowIndex * GRID_COLUMNS + columnIndex
                val cell = if (cellIndex < visibleItems.size) {
                    createCard(layoutInflater, row, visibleItems[cellIndex])
                } else {
                    View(pagesStrip.context)
                }
                cell.layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    1f
                ).apply {
                    val horizontalMargin = dp(5)
                    if (columnIndex == 0) setMargins(0, 0, horizontalMargin, 0)
                    else setMargins(horizontalMargin, 0, 0, 0)
                }
                row.addView(cell)
            }
            page.addView(row)
        }
        return page
    }

    private fun createCard(
        layoutInflater: LayoutInflater,
        row: ViewGroup,
        item: CatalogGridItem<T>
    ): View {
        return layoutInflater.inflate(R.layout.item_catalog_cell, row, false).apply {
            findViewById<TextView>(R.id.tvCellCode).text = item.cellCode
            findViewById<TextView>(R.id.tvCellProduct).text = item.productName
            findViewById<TextView>(R.id.tvCellPrice).text = item.priceText
            loadProductImage(item.imageUrl, findViewById(R.id.ivCellProductImage))
            findViewById<TextView>(R.id.tvCellState).apply {
                if (item.isAvailable) visibility = View.GONE
                else {
                    visibility = View.VISIBLE
                    text = "No disponible"
                    setBackgroundResource(R.drawable.bg_catalog_unavailable_badge)
                    setTextColor(Color.parseColor("#F28E1B"))
                }
            }
            alpha = if (item.isAvailable) 1f else 0.78f
            setOnClickListener { onProductTapped(item.source) }
        }
    }

    private fun dp(value: Int): Int =
        (value * pagesStrip.resources.displayMetrics.density).toInt()

    companion object {
        const val GRID_COLUMNS = 3
        const val GRID_ROWS = 3
        const val ITEMS_PER_PAGE = GRID_COLUMNS * GRID_ROWS
    }
}
