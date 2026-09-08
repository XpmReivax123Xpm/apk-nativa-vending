package com.vending.kiosk.app.ui.dispense

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import com.vending.kiosk.R

class DispenseDialogView(
    context: Context,
    private val loadProductImage: (String, ImageView) -> Unit,
    onCloseRequested: () -> Unit
) {
    val root: View = LayoutInflater.from(context).inflate(R.layout.dialog_dispense_progress, null)

    private val title = root.findViewById<TextView>(R.id.tvDispenseTitle)
    private val progress = root.findViewById<TextView>(R.id.tvDispenseProgress)
    private val productName = root.findViewById<TextView>(R.id.tvDispenseProductName)
    private val productImage = root.findViewById<ImageView>(R.id.ivDispenseProduct)
    private val status = root.findViewById<TextView>(R.id.tvDispenseStatus)
    private val timer = root.findViewById<TextView>(R.id.tvDispenseTimer)
    private val closeButton = root.findViewById<Button>(R.id.btnDispenseClose)

    init {
        closeButton.setOnClickListener { onCloseRequested() }
    }

    fun renderTitle(text: CharSequence) {
        title.text = text
    }

    fun renderProgress(text: CharSequence) {
        progress.text = text
    }

    fun renderProduct(name: CharSequence, imageUrl: String) {
        productName.text = name
        loadProductImage(imageUrl, productImage)
    }

    fun renderProductName(name: CharSequence) {
        productName.text = name
    }

    fun renderStatus(text: CharSequence) {
        status.text = text
    }

    fun postRenderStatus(text: CharSequence) {
        status.post { status.text = text }
    }

    fun renderTimer(visible: Boolean) {
        timer.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun renderCloseButton(text: CharSequence, visible: Boolean) {
        closeButton.text = text
        closeButton.visibility = if (visible) View.VISIBLE else View.GONE
    }
}
