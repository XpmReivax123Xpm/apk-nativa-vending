package com.vending.kiosk.app.ui.monitoring

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

class MonitoringViewerController(
    private val activity: Activity
) {
    private val handler = Handler(Looper.getMainLooper())
    private var dialog: AlertDialog? = null
    private var refreshRunnable: Runnable? = null

    fun show(
        title: String,
        live: Boolean,
        contentProvider: () -> String,
        onShown: () -> Unit,
        onDismissed: () -> Unit
    ) {
        dismiss()

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val titleView = TextView(activity).apply {
            text = title
            setTextColor(Color.parseColor("#0B456F"))
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        }
        val scrollView = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(420)
            ).apply { topMargin = dp(10) }
        }
        val contentView = TextView(activity).apply {
            setTextColor(Color.parseColor("#0A2239"))
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(Color.parseColor("#F3F7FB"))
        }
        scrollView.addView(contentView)
        container.addView(titleView)
        container.addView(scrollView)

        val viewerDialog = AlertDialog.Builder(activity)
            .setView(container)
            .setPositiveButton("Cerrar", null)
            .create()
        var modalRegistered = false
        var viewerRefreshRunnable: Runnable? = null

        fun updateContent() {
            contentView.text = contentProvider().ifBlank { "Sin datos para mostrar." }
            scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
        }

        viewerDialog.setOnDismissListener {
            viewerRefreshRunnable?.let(handler::removeCallbacks)
            if (dialog === viewerDialog) {
                dialog = null
                refreshRunnable = null
            }
            if (modalRegistered) {
                modalRegistered = false
                onDismissed()
            }
        }

        dialog = viewerDialog
        viewerDialog.show()
        modalRegistered = true
        onShown()
        updateContent()

        if (live) {
            val runnable = object : Runnable {
                override fun run() {
                    if (dialog !== viewerDialog || !viewerDialog.isShowing) return
                    updateContent()
                    handler.postDelayed(this, REFRESH_INTERVAL_MS)
                }
            }
            viewerRefreshRunnable = runnable
            refreshRunnable = runnable
            handler.postDelayed(runnable, REFRESH_INTERVAL_MS)
        }

        viewerDialog.window?.setBackgroundDrawable(ColorDrawable(Color.WHITE))
    }

    fun cancelRefresh() {
        refreshRunnable?.let(handler::removeCallbacks)
        refreshRunnable = null
    }

    fun dismiss() {
        cancelRefresh()
        dialog?.dismiss()
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    private companion object {
        private const val REFRESH_INTERVAL_MS = 350L
    }
}
