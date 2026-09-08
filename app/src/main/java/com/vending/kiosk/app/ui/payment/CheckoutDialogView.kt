package com.vending.kiosk.app.ui.payment

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import com.vending.kiosk.R

class CheckoutDialogView(
    context: Context,
    onCancelRequested: () -> Unit,
    onGenerateQrRequested: () -> Unit,
    onUserInteraction: () -> Unit
) {
    val root: View = LayoutInflater.from(context).inflate(R.layout.dialog_checkout_qr_quick, null)

    fun createGeneratingQrContent(): View =
        LayoutInflater.from(root.context).inflate(R.layout.dialog_generating_qr, null)

    private val tvSummary = root.findViewById<TextView>(R.id.tvCheckoutSummary)
    private val tvMethod = root.findViewById<TextView>(R.id.tvCheckoutMethod)
    private val tvError = root.findViewById<TextView>(R.id.tvCheckoutError)
    private val tvTimer = root.findViewById<TextView>(R.id.tvCheckoutDialogTimer)
    private val progress = root.findViewById<ProgressBar>(R.id.progressCheckout)
    private val btnCancel = root.findViewById<Button>(R.id.btnCheckoutCancel)
    private val btnGenerate = root.findViewById<Button>(R.id.btnCheckoutGenerate)

    init {
        tvTimer.setBackgroundColor(Color.TRANSPARENT)
        tvTimer.setTextColor(Color.WHITE)
        tvTimer.textSize = 20f
        tvTimer.setShadowLayer(2f, 0f, 1f, Color.parseColor("#80000000"))

        root.setOnTouchListener { _, _ ->
            onUserInteraction()
            false
        }
        btnCancel.setOnClickListener { onCancelRequested() }
        btnGenerate.setOnClickListener { onGenerateQrRequested() }
    }

    fun renderCheckout(summaryText: String, methodText: String) {
        tvSummary.text = summaryText
        tvMethod.text = methodText
    }

    fun renderTimer(timerText: String) {
        tvTimer.text = timerText
    }

    fun setLoading(loading: Boolean) {
        progress.visibility = if (loading) View.VISIBLE else View.GONE
        btnGenerate.isEnabled = !loading
        btnCancel.isEnabled = !loading
    }

    fun renderError(message: String) {
        tvError.visibility = View.VISIBLE
        tvError.text = message
    }

    fun hideError() {
        tvError.visibility = View.GONE
    }
}
