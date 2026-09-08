package com.vending.kiosk.app.ui.catalog

import android.view.View
import android.widget.TextView

class CartBarView(
    private val cartBar: View,
    private val badge: TextView
) {
    fun render(badgeText: CharSequence, isBadgeVisible: Boolean) {
        badge.text = badgeText
        badge.visibility = if (isBadgeVisible) View.VISIBLE else View.GONE
    }

    fun setOnCartClick(onCartClick: () -> Unit) {
        cartBar.setOnClickListener { onCartClick() }
    }
}
