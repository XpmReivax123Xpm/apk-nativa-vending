package com.vending.kiosk.app.ui.payment

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import com.vending.kiosk.R

data class PaymentMethodDialogOption(
    val id: Int,
    val label: String
)

class PaymentMethodDialogView(
    context: Context,
    private val onMethodSelected: (Int) -> Unit,
    private val onContinueRequested: (Int?) -> Unit,
    private val onCancelRequested: () -> Unit,
    private val onUserInteraction: () -> Unit
) {
    val root: View = LayoutInflater.from(context).inflate(R.layout.dialog_payment_method, null)

    private val methods = root.findViewById<RadioGroup>(R.id.rgPaymentMethods)
    private val error = root.findViewById<TextView>(R.id.tvPaymentMethodError)
    private val timer = root.findViewById<TextView>(R.id.tvPaymentMethodTimer)
    private val methodsByViewId = mutableMapOf<Int, Int>()

    init {
        timer.setBackgroundColor(Color.TRANSPARENT)
        timer.setTextColor(Color.WHITE)
        timer.textSize = 20f
        timer.setShadowLayer(2f, 0f, 1f, Color.parseColor("#80000000"))

        methods.setOnCheckedChangeListener { _, checkedId ->
            methodsByViewId[checkedId]?.let(onMethodSelected)
        }
        root.findViewById<Button>(R.id.btnPaymentMethodCancel).setOnClickListener {
            onUserInteraction()
            onCancelRequested()
        }
        root.findViewById<Button>(R.id.btnPaymentMethodContinue).setOnClickListener {
            onUserInteraction()
            onContinueRequested(methodsByViewId[methods.checkedRadioButtonId])
        }
        root.setOnTouchListener { _, _ ->
            onUserInteraction()
            false
        }
    }

    fun renderMethods(options: List<PaymentMethodDialogOption>, selectedMethodId: Int?) {
        methods.removeAllViews()
        methodsByViewId.clear()
        options.forEach { option ->
            val radio = RadioButton(root.context).apply {
                id = View.generateViewId()
                layoutParams = RadioGroup.LayoutParams(
                    RadioGroup.LayoutParams.MATCH_PARENT,
                    RadioGroup.LayoutParams.WRAP_CONTENT
                )
                buttonTintList = ColorStateList.valueOf(Color.parseColor("#F28E1B"))
                text = option.label
                textSize = 16f
                setTextColor(Color.parseColor("#20344D"))
                setPadding(0, dp(8), 0, dp(8))
                setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_qr_method, 0, 0, 0)
                compoundDrawablePadding = dp(10)
            }
            methods.addView(radio)
            methodsByViewId[radio.id] = option.id
            if (option.id == selectedMethodId) {
                methods.check(radio.id)
            }
        }
    }

    fun renderTimer(timerText: CharSequence) {
        timer.text = timerText
    }

    fun renderError(message: CharSequence, textColor: Int) {
        error.visibility = View.VISIBLE
        error.setTextColor(textColor)
        error.text = message
    }

    fun hideError() {
        error.visibility = View.GONE
    }

    fun setContinueEnabled(isEnabled: Boolean) {
        root.findViewById<Button>(R.id.btnPaymentMethodContinue).isEnabled = isEnabled
    }

    private fun dp(value: Int): Int {
        return (value * root.resources.displayMetrics.density).toInt()
    }
}
