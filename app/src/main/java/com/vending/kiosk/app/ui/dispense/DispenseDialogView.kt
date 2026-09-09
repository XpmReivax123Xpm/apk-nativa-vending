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

    class RetrieveContent(
        val root: View
    ) {
        private val title = root.findViewById<TextView>(R.id.tvRetrieveTitle)
        private val message = root.findViewById<TextView>(R.id.tvRetrieveMessage)

        fun render(titleText: CharSequence, messageText: CharSequence) {
            title.text = titleText
            message.text = messageText
        }
    }

    class SuccessContent(
        val root: View
    ) {
        private val timer = root.findViewById<TextView>(R.id.tvDispenseSuccessTimer)
        private val closeButton = root.findViewById<Button>(R.id.btnDispenseSuccessClose)

        fun renderTimer(text: CharSequence) {
            timer.text = text
        }

        fun setOnSuccessCloseRequested(onSuccessCloseRequested: () -> Unit) {
            closeButton.setOnClickListener { onSuccessCloseRequested() }
        }
    }

    class IoTimeoutContent(
        val root: View
    ) {
        private val message = root.findViewById<TextView>(R.id.tvIoTimeoutMessage)

        fun renderMessage(text: CharSequence) {
            message.text = text
        }
    }

    class IoProlongedWaitContent(
        val root: View,
        onManualRetryRequested: () -> Unit
    ) {
        private val status = root.findViewById<TextView>(R.id.tvManualRetryStatus)
        private val retryButton = root.findViewById<Button>(R.id.btnManualDoorRetry)
        private val retryProgress = root.findViewById<View>(R.id.progressManualDoorRetry)

        init {
            retryButton.setOnClickListener { onManualRetryRequested() }
        }

        fun renderAvailable() {
            retryButton.isEnabled = true
            retryButton.text = "Reintento manual"
            retryProgress.visibility = View.GONE
            status.text = ""
        }

        fun renderRetrying() {
            retryButton.isEnabled = false
            retryButton.text = "Reintentando..."
            retryProgress.visibility = View.VISIBLE
            status.text = "Reintentando... Por favor espere."
        }

        fun renderAlreadyRetrying() {
            status.text = "Ya estamos reintentando. Por favor espere."
        }

        fun renderUnableToStart() {
            status.text = "No se pudo iniciar el reintento manual."
        }
    }

    class PlatformStuckContent(
        val root: View,
        onPlatformRecoveryRequested: () -> Unit
    ) {
        private val message = root.findViewById<TextView>(R.id.tvPlatformStuckMessage)
        private val fixButton = root.findViewById<Button>(R.id.btnFixPlatformStuck)

        init {
            fixButton.setOnClickListener { onPlatformRecoveryRequested() }
        }

        fun renderMessage(text: CharSequence) {
            message.text = text
        }
    }

    class PlatformRecoveringContent(
        val root: View
    )

    companion object {
        fun createRetrieveContent(context: Context): RetrieveContent {
            val root = LayoutInflater.from(context).inflate(R.layout.dialog_dispense_retrieve, null)
            return RetrieveContent(root)
        }

        fun createSuccessContent(context: Context): SuccessContent {
            val root = LayoutInflater.from(context).inflate(R.layout.dialog_dispense_success, null)
            return SuccessContent(root)
        }

        fun createIoTimeoutContent(context: Context): IoTimeoutContent {
            val root = LayoutInflater.from(context).inflate(R.layout.dialog_dispense_io_timeout, null)
            return IoTimeoutContent(root)
        }

        fun createIoProlongedWaitContent(
            context: Context,
            onManualRetryRequested: () -> Unit
        ): IoProlongedWaitContent {
            val root = LayoutInflater.from(context).inflate(R.layout.dialog_dispense_io_prolonged_wait, null)
            return IoProlongedWaitContent(root, onManualRetryRequested)
        }

        fun createPlatformStuckContent(
            context: Context,
            onPlatformRecoveryRequested: () -> Unit
        ): PlatformStuckContent {
            val root = LayoutInflater.from(context).inflate(R.layout.dialog_platform_stuck, null)
            return PlatformStuckContent(root, onPlatformRecoveryRequested)
        }

        fun createPlatformRecoveringContent(context: Context): PlatformRecoveringContent {
            val root = LayoutInflater.from(context).inflate(R.layout.dialog_platform_recovering, null)
            return PlatformRecoveringContent(root)
        }
    }
}
