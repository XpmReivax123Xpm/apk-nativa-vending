package com.vending.kiosk.integration.serial.runtime

import android.os.Handler
import android.os.Looper

class VendingFlowController(
    private val serial: SerialManager,
    private val serialListener: SerialManager.Listener,
    private val ui: Ui,
) {
    interface Ui {
        fun onLog(msg: String)
        fun onNeedRetrieve(msg: String)
        fun onDone()
        fun onError(msg: String)
        fun onStep(stepMsg: String)
    }

    private val h = Handler(Looper.getMainLooper())
    private var running = false
    private var waitingPickup = false
    private var selectedCell = 37
    private var startTimeMs = 0L
    private var ioStartMs = 0L
    private var ioStableValue: Int? = null
    private var ioStableSinceMs = 0L
    private var seenClosedNoProduct = false
    private var seenFirstClick = false
    @Volatile private var expectDriverRx = false
    @Volatile private var expectIoVendRx = false
    @Volatile private var expectIoPickupRx = false
    private var driverZeroCount = 0
    private var lastVendIoValue: Int? = null
    private var vendStage = 0

    fun isRunning(): Boolean = running
    fun isWaitingPickup(): Boolean = waitingPickup

    fun stop() {
        running = false
        waitingPickup = false
        expectDriverRx = false
        expectIoVendRx = false
        expectIoPickupRx = false
        h.removeCallbacksAndMessages(null)
        ui.onLog("STOP: vendtest detenido.")
    }

    fun start(cell: Int) {
        if (running || waitingPickup) {
            ui.onLog("Ya hay un proceso corriendo/esperando.")
            return
        }
        if (!serial.isOpen()) {
            ui.onError("Abre el puerto primero.")
            return
        }
        selectedCell = cell
        try {
            val select = CommandSet.buildSelectCellFull(selectedCell)
            running = true
            waitingPickup = false
            startTimeMs = System.currentTimeMillis()
            ioStableValue = null
            ioStableSinceMs = 0L
            ioStartMs = 0L
            seenClosedNoProduct = false
            seenFirstClick = false
            lastVendIoValue = null
            vendStage = 0
            driverZeroCount = 0
            ui.onLog("VEND iniciado para celda: $selectedCell")
            serial.sendHex(select, serialListener)
            ui.onLog("TX SELECT celda $selectedCell: $select")
            h.postDelayed({
                if (running && !waitingPickup) {
                    schedulePollDriver()
                    schedulePollIoVend()
                }
            }, VEND_START_DELAY_MS)
        } catch (ex: Exception) {
            ui.onError(ex.message ?: "Error desconocido")
        }
    }

    fun onRx(data: ByteArray, size: Int) {
        val rx = HexUtil.bytesToHex(data, size).replace(" ", "").uppercase()
        if (expectDriverRx) {
            expectDriverRx = false
            val drvVal = parseFirstRegisterFrom0103(rx)
            if (running && !waitingPickup && drvVal != null) {
                if (drvVal == 0) {
                    driverZeroCount++
                    ui.onLog("Driver status=0000 ($driverZeroCount/3)")
                    if (driverZeroCount >= DRIVER_ZERO_MAX) {
                        running = false
                        waitingPickup = false
                        h.removeCallbacksAndMessages(null)
                        ui.onError("Driver abortado (status 0000)")
                        return
                    }
                } else {
                    driverZeroCount = 0
                }
            }
            if (running && !waitingPickup && isDriverDone(rx)) {
                h.removeCallbacks(pollDriverRunnable)
                h.removeCallbacks(pollIoVendRunnable)
                ui.onLog("Driver DONE (sysctrl_stat=2).")
                if (vendStage < 4) {
                    vendStage = 4
                    ui.onLog("Puerta blanca: cerrando/cerrada (inferido por DONE)")
                }
                running = false
                waitingPickup = true
                ioStartMs = System.currentTimeMillis()
                ioStableValue = null
                ioStableSinceMs = 0L
                seenClosedNoProduct = false
                seenFirstClick = false
                ui.onNeedRetrieve("Retire su producto. Esperando cierre sin producto y segundo click.")
                schedulePollIoPickup()
                return
            }
        }
        if (expectIoVendRx) {
            expectIoVendRx = false
            val ioValue = parseFirstRegisterFrom0103(rx)
            if (running && !waitingPickup && ioValue != null) handleVendIo(ioValue)
        }
        if (expectIoPickupRx) {
            expectIoPickupRx = false
            val ioValue = parseFirstRegisterFrom0103(rx)
            if (waitingPickup && ioValue != null) handlePickupIoValue(ioValue)
        }
    }

    private fun handleVendIo(value: Int) {
        if (lastVendIoValue == value) return
        lastVendIoValue = value
        when (value) {
            IO_WHITE_DOOR_OPENING -> advanceVendStage(1, "Puerta blanca: ABRIENDO")
            IO_PLATFORM_UP -> advanceVendStage(2, "Plataforma: SUBIENDO")
            IO_PLATFORM_DOWN -> advanceVendStage(3, "Plataforma: BAJANDO")
            IO_WHITE_DOOR_CLOSING -> advanceVendStage(4, "Puerta blanca: CERRANDO")
        }
    }

    private fun handlePickupIoValue(value: Int) {
        val now = System.currentTimeMillis()
        if (ioStableValue != value) {
            ioStableValue = value
            ioStableSinceMs = now
            return
        }
        if (now - ioStableSinceMs < IO_STABLE_MS) return

        if (value == IO_AFTER_FIRST_CLICK && !seenFirstClick) {
            seenFirstClick = true
            ui.onLog("Puerta chica: 1er click confirmado (0082)")
        }
        if (value == IO_DOOR_CLOSED_NO_PROD && !seenClosedNoProduct) {
            seenClosedNoProduct = true
            ui.onLog("Puerta chica: cerrada SIN producto (0092)")
        }
        if (seenClosedNoProduct && value == IO_SECOND_CLICK) {
            ui.onLog("Puerta chica: 2do click confirmado (00D2) -> FIN")
            waitingPickup = false
            h.removeCallbacksAndMessages(null)
            ui.onDone()
        }
    }

    private fun advanceVendStage(newStage: Int, logMsg: String) {
        if (newStage <= vendStage) return
        vendStage = newStage
        ui.onLog(logMsg)
    }

    private fun schedulePollDriver() = h.postDelayed(pollDriverRunnable, 120L)
    private fun schedulePollIoVend() = h.postDelayed(pollIoVendRunnable, 250L)
    private fun schedulePollIoPickup() = h.postDelayed(pollIoPickupRunnable, 80L)

    private fun isDriverDone(rxNoSpacesUpper: String): Boolean = rxNoSpacesUpper.contains("0103020200")

    private fun parseFirstRegisterFrom0103(rxNoSpacesUpper: String): Int? {
        if (!rxNoSpacesUpper.startsWith("0103") || rxNoSpacesUpper.length < 10) return null
        return try {
            rxNoSpacesUpper.substring(6, 10).toInt(16)
        } catch (_: Exception) {
            null
        }
    }

    private val pollDriverRunnable = object : Runnable {
        override fun run() {
            if (!running || waitingPickup) return
            val elapsed = System.currentTimeMillis() - startTimeMs
            if (elapsed > DRIVER_TIMEOUT_MS) {
                running = false
                h.removeCallbacksAndMessages(null)
                ui.onError("Timeout driver: no termino en 45s")
                return
            }
            if (expectDriverRx || expectIoVendRx || expectIoPickupRx) {
                h.postDelayed(this, 140L)
                return
            }
            expectDriverRx = true
            serial.sendHex(CommandSet.POLL_DRIVER_STATUS, serialListener)
            h.postDelayed(this, POLL_DRIVER_MS)
        }
    }

    private val pollIoVendRunnable = object : Runnable {
        override fun run() {
            if (!running || waitingPickup) return
            if (expectDriverRx || expectIoVendRx || expectIoPickupRx) {
                h.postDelayed(this, 200L)
                return
            }
            expectIoVendRx = true
            serial.sendHex(CommandSet.POLL_IO_STATUS, serialListener)
            h.postDelayed(this, POLL_IO_VEND_MS)
        }
    }

    private val pollIoPickupRunnable = object : Runnable {
        override fun run() {
            if (!waitingPickup) return
            val elapsed = System.currentTimeMillis() - ioStartMs
            if (elapsed > IO_WAIT_TIMEOUT_MS) {
                waitingPickup = false
                h.removeCallbacksAndMessages(null)
                ui.onError("Timeout: no llego el 2do click en 180s")
                return
            }
            if (expectDriverRx || expectIoVendRx || expectIoPickupRx) {
                h.postDelayed(this, 220L)
                return
            }
            expectIoPickupRx = true
            serial.sendHex(CommandSet.POLL_IO_STATUS, serialListener)
            h.postDelayed(this, POLL_IO_PICKUP_MS)
        }
    }

    companion object {
        private const val DRIVER_TIMEOUT_MS = 45_000L
        private const val DRIVER_ZERO_MAX = 3
        private const val POLL_DRIVER_MS = 950L
        private const val POLL_IO_VEND_MS = 1_200L
        private const val POLL_IO_PICKUP_MS = 420L
        private const val IO_WAIT_TIMEOUT_MS = 180_000L
        private const val IO_STABLE_MS = 600L
        private const val VEND_START_DELAY_MS = 350L

        private const val IO_AFTER_FIRST_CLICK = 130
        private const val IO_DOOR_CLOSED_NO_PROD = 146
        private const val IO_PLATFORM_UP = 216
        private const val IO_PLATFORM_DOWN = 200
        private const val IO_WHITE_DOOR_OPENING = 210
        private const val IO_WHITE_DOOR_CLOSING = 194
        private const val IO_SECOND_CLICK = 210
    }
}
