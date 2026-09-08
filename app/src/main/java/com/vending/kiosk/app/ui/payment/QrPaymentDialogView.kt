package com.vending.kiosk.app.ui.payment

import android.content.Context
import android.graphics.Bitmap
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.vending.kiosk.R

class QrPaymentDialogView(
    context: Context,
    bitmap: Bitmap,
    expiration: String,
    onCancelRequested: () -> Unit
) {
    val root: View = LayoutInflater.from(context).inflate(R.layout.dialog_qr_payment, null)
    val statusText: TextView = root.findViewById(R.id.tvQrStatus)
    val progress: ProgressBar = root.findViewById(R.id.progressQrPolling)
    val mainButton: Button = root.findViewById(R.id.btnCloseQrDialog)

    init {
        val qrImage = root.findViewById<ImageView>(R.id.ivPaymentQr)
        val expirationText = root.findViewById<TextView>(R.id.tvQrExpiration)
        val minSide = root.resources.displayMetrics.widthPixels.coerceAtMost(root.resources.displayMetrics.heightPixels)
        val targetPx = (minSide * 0.72f).toInt().coerceIn(dp(260), dp(520))
        qrImage.layoutParams = qrImage.layoutParams.apply {
            width = targetPx
            height = targetPx
        }
        qrImage.scaleType = ImageView.ScaleType.FIT_CENTER
        qrImage.setImageBitmap(bitmap)
        expirationText.text = if (expiration.isBlank()) "Expira: -" else "Expira: $expiration"
        statusText.text = "Esperando pago..."
        progress.visibility = View.VISIBLE
        mainButton.visibility = View.VISIBLE
        mainButton.isEnabled = true
        mainButton.text = "Cancelar"
        mainButton.setOnClickListener { onCancelRequested() }
    }

    internal fun makeDialogDraggable(dialog: AlertDialog) {
        val window = dialog.window ?: return
        val displayMetrics = root.resources.displayMetrics
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        val touchSlop = android.view.ViewConfiguration.get(root.context).scaledTouchSlop

        root.post {
            val maxY = (displayMetrics.heightPixels - root.height).coerceAtLeast(0)
            window.attributes = window.attributes.apply {
                gravity = Gravity.TOP or Gravity.START
                x = ((displayMetrics.widthPixels - root.width) / 2).coerceAtLeast(0)
                y = maxY
            }
        }

        root.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val attrs = window.attributes
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = attrs.x
                    startY = attrs.y
                    dragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && kotlin.math.abs(dx) < touchSlop && kotlin.math.abs(dy) < touchSlop) {
                        true
                    } else {
                        dragging = true
                        val maxX = (displayMetrics.widthPixels - root.width).coerceAtLeast(0)
                        val maxY = (displayMetrics.heightPixels - root.height).coerceAtLeast(0)
                        window.attributes = window.attributes.apply {
                            x = (startX + dx.toInt()).coerceIn(0, maxX)
                            y = (startY + dy.toInt()).coerceIn(0, maxY)
                        }
                        true
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    true
                }

                else -> false
            }
        }
    }

    private fun dp(value: Int): Int {
        return (value * root.resources.displayMetrics.density).toInt()
    }
}
