package com.vending.kiosk.app.ui.catalog

import androidx.annotation.DrawableRes
import com.vending.kiosk.R

data class LocalPromotion(
    @DrawableRes val imageRes: Int
)

object LocalPromotions {
    val items: List<LocalPromotion> = listOf(
        LocalPromotion(R.drawable.promo_1)
    )
}