package com.vending.kiosk.app.ui.catalog

import android.graphics.Color
import android.view.LayoutInflater
import android.view.MotionEvent
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
    private val contentContainer: LinearLayout,
    private val loadProductImage: (String, ImageView) -> Unit,
    private val onProductTapped: (T) -> Unit,
    private val onSwipePreviousPage: () -> Unit,
    private val onSwipeNextPage: () -> Unit
) {
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var swipeHandled = false

    private val catalogTouchListener = View.OnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x
                touchDownY = event.y
                swipeHandled = false
                false
            }

            MotionEvent.ACTION_UP -> {
                val horizontalDistance = event.x - touchDownX
                val verticalDistance = event.y - touchDownY
                val isHorizontalSwipe = !swipeHandled &&
                    kotlin.math.abs(horizontalDistance) >= SWIPE_THRESHOLD_DP * contentContainer.resources.displayMetrics.density &&
                    kotlin.math.abs(horizontalDistance) > kotlin.math.abs(verticalDistance)

                if (isHorizontalSwipe) {
                    swipeHandled = true
                    contentContainer.post {
                        if (horizontalDistance > 0) {
                            onSwipePreviousPage()
                        } else {
                            onSwipeNextPage()
                        }
                    }
                    true
                } else {
                    false
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                swipeHandled = false
                false
            }

            else -> false
        }
    }

    fun render(items: List<CatalogGridItem<T>>) {
        contentContainer.removeAllViews()
        contentContainer.setOnTouchListener(catalogTouchListener)

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
                setOnTouchListener(catalogTouchListener)
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
                    applyCatalogTouchListener(card)

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
                        setOnTouchListener(catalogTouchListener)
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

    private fun applyCatalogTouchListener(view: View) {
        view.setOnTouchListener(catalogTouchListener)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                applyCatalogTouchListener(view.getChildAt(index))
            }
        }
    }

    companion object {
        const val COLUMNS = 2
        const val ROWS = 3
        const val ITEMS_PER_PAGE = COLUMNS * ROWS
        private const val SWIPE_THRESHOLD_DP = 48
    }
}
