package com.vending.kiosk.app.ui.idle

import android.os.Handler
import android.os.Looper

class IdleController(
    private val onIdleTimeout: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var activeModalCount = 0
    private val idleRunnable = Runnable {
        if (activeModalCount > 0) {
            scheduleTimeout()
        } else {
            onIdleTimeout()
        }
    }

    fun start() {
        scheduleTimeout()
    }

    fun stop() {
        handler.removeCallbacks(idleRunnable)
    }

    fun onUserInteraction() {
        start()
    }

    fun onModalShown() {
        activeModalCount += 1
        stop()
    }

    fun onModalDismissed() {
        activeModalCount = (activeModalCount - 1).coerceAtLeast(0)
        if (activeModalCount == 0) {
            start()
        }
    }

    private fun scheduleTimeout() {
        stop()
        handler.postDelayed(idleRunnable, IDLE_TIMEOUT_MS)
    }

    private companion object {
        private const val IDLE_TIMEOUT_MS = 60_000L
    }
}
