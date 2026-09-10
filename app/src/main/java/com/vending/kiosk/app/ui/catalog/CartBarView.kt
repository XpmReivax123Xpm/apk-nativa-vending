package com.vending.kiosk.app.ui.catalog

import android.view.View
import android.widget.TextView

class CartBarView(
    private val cartBar: View,
    private val badge: TextView,
    private val total: TextView
) {
    fun render(badgeText: CharSequence, totalText: CharSequence, isBadgeVisible: Boolean) {
        badge.text = badgeText
        total.text = totalText
        badge.visibility = if (isBadgeVisible) View.VISIBLE else View.GONE
    }

    fun setOnCartClick(onCartClick: () -> Unit) {
        cartBar.setOnClickListener { onCartClick() }
    }
}
