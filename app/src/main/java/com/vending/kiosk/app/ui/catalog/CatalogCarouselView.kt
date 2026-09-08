package com.vending.kiosk.app.ui.catalog

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ViewFlipper
import com.vending.kiosk.R

class CatalogCarouselView(
    private val promoCarousel: View,
    private val legacyCarousel: LegacyCarousel,
    private val carouselHandler: Handler,
    private val carouselTicker: Runnable,
    private val carouselIntervalMs: Long
) {
    interface LegacyCarousel {
        val isEnabled: Boolean
        var currentIndex: Int

        fun getSlideCount(): Int
        fun showSlide(index: Int)
    }

    private var adaptiveHeightApplied = false

    fun resetAdaptiveHeight() {
        adaptiveHeightApplied = false
    }

    fun inflateDefaultViewFlipperSlides(flipper: ViewFlipper) {
        val defaults = listOf(
            Triple(R.drawable.bg_catalog_promo_1, "Promo del dia", "Espacio para ofertas y anuncios"),
            Triple(R.drawable.bg_catalog_promo_2, "Nuevos productos", "Carrusel preparado para imagenes"),
            Triple(R.drawable.bg_catalog_promo_3, "Avisos", "Descuentos, mantenimiento y novedades")
        )
        defaults.forEach { (backgroundRes, title, subtitle) ->
            val slide = LinearLayout(promoCarousel.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundResource(backgroundRes)
                gravity = Gravity.BOTTOM or Gravity.START
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(18), dp(18), dp(18))
            }
            val titleView = TextView(promoCarousel.context).apply {
                text = title
                setTextColor(Color.parseColor("#0965AF"))
                textSize = 28f
                setTypeface(typeface, Typeface.BOLD)
            }
            val subtitleView = TextView(promoCarousel.context).apply {
                text = subtitle
                setTextColor(Color.parseColor("#0965AF"))
                textSize = 18f
                setPadding(0, dp(4), 0, 0)
            }
            slide.addView(titleView)
            slide.addView(subtitleView)
            flipper.addView(slide)
        }
    }

    fun applyAdaptiveCarouselHeight(bitmap: Bitmap) {
        if (adaptiveHeightApplied) return
        if (bitmap.width <= 0 || bitmap.height <= 0) return

        val containerWidth = promoCarousel.width.takeIf { it > 0 }
            ?: (promoCarousel.resources.displayMetrics.widthPixels - dp(24))
        if (containerWidth <= 0) return

        val desiredHeight = (containerWidth * (bitmap.height.toFloat() / bitmap.width.toFloat())).toInt()
        val screenHeight = promoCarousel.resources.displayMetrics.heightPixels
        val minHeight = dp(220)
        val maxHeight = (screenHeight * 0.52f).toInt().coerceAtLeast(dp(320))
        val targetHeight = desiredHeight.coerceIn(minHeight, maxHeight)

        val params = promoCarousel.layoutParams ?: return
        if (params.height != targetHeight) {
            params.height = targetHeight
            promoCarousel.layoutParams = params
        }
        adaptiveHeightApplied = true
    }

    fun applyCarouselHeight() {
        val screenHeight = promoCarousel.resources.displayMetrics.heightPixels
        val screenWidth = promoCarousel.resources.displayMetrics.widthPixels
        val isLandscape = screenWidth > screenHeight
        val factor = if (isLandscape) 0.22f else 0.38f
        val desired = (screenHeight * factor).toInt()
        val minHeight = if (isLandscape) dp(150) else dp(180)
        val maxHeight = if (isLandscape) dp(320) else dp(1000)
        val target = desired.coerceIn(minHeight, maxHeight)
        promoCarousel.layoutParams = promoCarousel.layoutParams.apply {
            height = target
        }
    }

    fun showNextPromoSlide() {
        if (legacyCarousel.isEnabled) {
            val slideCount = legacyCarousel.getSlideCount()
            if (slideCount <= 1) return
            legacyCarousel.currentIndex = (legacyCarousel.currentIndex + 1) % slideCount
            legacyCarousel.showSlide(legacyCarousel.currentIndex)
            carouselHandler.removeCallbacks(carouselTicker)
            carouselHandler.postDelayed(carouselTicker, carouselIntervalMs)
            return
        }

        (promoCarousel as? ViewFlipper)?.let { flipper ->
            flipper.setInAnimation(promoCarousel.context, R.anim.carousel_in_right)
            flipper.setOutAnimation(promoCarousel.context, R.anim.carousel_out_left)
            flipper.showNext()
        }
    }

    fun showPreviousPromoSlide() {
        if (legacyCarousel.isEnabled) {
            val slideCount = legacyCarousel.getSlideCount()
            if (slideCount <= 1) return
            legacyCarousel.currentIndex = if (legacyCarousel.currentIndex - 1 < 0) {
                slideCount - 1
            } else {
                legacyCarousel.currentIndex - 1
            }
            legacyCarousel.showSlide(legacyCarousel.currentIndex)
            carouselHandler.removeCallbacks(carouselTicker)
            carouselHandler.postDelayed(carouselTicker, carouselIntervalMs)
            return
        }

        (promoCarousel as? ViewFlipper)?.let { flipper ->
            flipper.setInAnimation(promoCarousel.context, R.anim.carousel_in_left)
            flipper.setOutAnimation(promoCarousel.context, R.anim.carousel_out_right)
            flipper.showPrevious()
        }
    }

    fun pauseAutoCarousel() {
        carouselHandler.removeCallbacks(carouselTicker)
        (promoCarousel as? ViewFlipper)?.stopFlipping()
    }

    fun resumeAutoCarousel() {
        if (legacyCarousel.isEnabled) {
            if (legacyCarousel.getSlideCount() > 1) {
                carouselHandler.removeCallbacks(carouselTicker)
                carouselHandler.postDelayed(carouselTicker, carouselIntervalMs)
            }
        } else {
            (promoCarousel as? ViewFlipper)?.let { flipper ->
                if (flipper.childCount > 1) flipper.startFlipping()
            }
        }
    }

    private fun dp(value: Int): Int {
        return (value * promoCarousel.resources.displayMetrics.density).toInt()
    }
}
