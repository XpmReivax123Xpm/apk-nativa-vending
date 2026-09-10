package com.vending.kiosk.app.ui.catalog

import android.graphics.Color
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
    private val contentHost: FrameLayout,
    private val loadProductImage: (String, ImageView) -> Unit,
    private val onProductTapped: (T) -> Unit,
    private val onPageTouch: (MotionEvent) -> Boolean
) {
    fun render(items: List<CatalogGridItem<T>>) {
        contentHost.removeAllViews()
        contentHost.addView(createPage(items))
    }

    fun stagePages(
        firstItems: List<CatalogGridItem<T>>,
        secondItems: List<CatalogGridItem<T>>,
        pageWidth: Int
    ): View {
        return LinearLayout(contentHost.context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                pageWidth * 2,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            addView(createPage(firstItems).apply {
                layoutParams = LinearLayout.LayoutParams(
                    pageWidth,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
            })
            addView(createPage(secondItems).apply {
                layoutParams = LinearLayout.LayoutParams(
                    pageWidth,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
            })
        }
    }

    fun createPage(items: List<CatalogGridItem<T>>): View {
        val page = LinearLayout(contentHost.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setOnTouchListener(pageTouchListener)
        }
        val layoutInflater = LayoutInflater.from(contentHost.context)
        val visibleItems = items.take(ITEMS_PER_PAGE)

        for (rowIndex in 0 until ROWS) {
            val row = LinearLayout(contentHost.context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                ).also {
                    it.bottomMargin = if (rowIndex == ROWS - 1) 0 else dp(8)
                }
                setOnTouchListener(pageTouchListener)
            }

            for (columnIndex in 0 until COLUMNS) {
                val cellIndex = rowIndex * COLUMNS + columnIndex
                val cell = if (cellIndex < visibleItems.size) {
                    createCard(layoutInflater, row, visibleItems[cellIndex])
                } else {
                    View(contentHost.context).apply {
                        isClickable = true
                        setOnTouchListener(pageTouchListener)
                    }
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
            applyPageTouchListener(this)
        }
    }

    private val pageTouchListener = View.OnTouchListener { _, event -> onPageTouch(event) }

    private fun applyPageTouchListener(view: View) {
        view.setOnTouchListener(pageTouchListener)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) applyPageTouchListener(view.getChildAt(index))
        }
    }

    private fun dp(value: Int): Int =
        (value * contentHost.resources.displayMetrics.density).toInt()

    companion object {
        const val COLUMNS = 2
        const val ROWS = 3
        const val ITEMS_PER_PAGE = COLUMNS * ROWS
    }
}
