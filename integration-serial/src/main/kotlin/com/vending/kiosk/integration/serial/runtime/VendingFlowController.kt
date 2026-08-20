package com.vending.kiosk.integration.serial.runtime

import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicInteger

class VendingFlowController(
    private val serial: SerialManager,
    private val serialListener: SerialManager.Listener,
    private val ui: Ui,
    private val platformRecoveryCommand: PlatformRecoveryCommand? = null,
) {
    interface Ui {
        fun onLog(msg: String)
        fun onNeedRetrieve(msg: String)
        fun onPlatformStuck(msg: String)
        fun onDone()
        fun onError(msg: String)
        fun onStep(stepMsg: String)
    }

    interface PlatformRecoveryCommand {
        fun moveToBase(): PlatformRecoveryCommandResult

        fun runManualDoorRetrySequence(
            shouldContinue: () -> Boolean = { true },
            runIfActive: (() -> Unit) -> Boolean = { action ->
                if (shouldContinue()) {
                    action()
                    true
                } else {
                    false
                }
            },
        ): PlatformRecoveryCommandResult {
            return PlatformRecoveryCommandResult(
                ok = false,
                commandName = "Manual ToY(2000)->ToY(0)",
                detail = "Manual retry sequence is not available"
            )
        }
    }

    data class PlatformRecoveryCommandResult(
        val ok: Boolean,
        val commandName: String,
        val detail: String = ""
    )

    private val h = Handler(Looper.getMainLooper())
    private var running = false
    private var waitingPickup = false
    private var selectedCell = 37
    private var startTimeMs = 0L
    private var ioStartMs = 0L
    private var ioStableValue: Int? = null
    private var ioStableSinceMs = 0L
    private var seenClosedNoProduct = false
    private var seenPickupProgress = false
    private var seenDoorOpenedFirstTime = false
    private var seenProductRemovedDoorOpen = false
    @Volatile private var expectDriverRx = false
    @Volatile private var expectIoVendRx = false
    @Volatile private var expectIoPickupRx = false
    @Volatile private var expectIoRecoveryRx = false
    private var driverZeroCount = 0
    private var lastVendIoValue: Int? = null
    private var vendStage = 0
    private var seenC2InCurrentVend = false
    private var platformDownStartedAtMs = 0L
    private var seenC2AfterPlatformDown = false
    private var forcedPickupByDriverZero = false
    private var waitingPlatformRecovery = false
    private var recoveryStartedAtMs = 0L
    @Volatile private var recoveryCommandRunning = false
    @Volatile private var manualDoorRetryRunning = false
    private val lifecycleLock = Any()
    private val manualDoorRetryGeneration = AtomicInteger(0)
    private var ioTimeoutWarningEmitted = false
    private var ioTimeoutProlongedEmitted = false
    private var ioCancelStartMs = 0L

    fun isRunning(): Boolean = running
    fun isWaitingPickup(): Boolean = waitingPickup
    fun isWaitingPlatformRecovery(): Boolean = waitingPlatformRecovery
    fun isManualDoorRetryRunning(): Boolean = manualDoorRetryRunning

    fun stop() {
        synchronized(lifecycleLock) {
            running = false
            waitingPickup = false
            waitingPlatformRecovery = false
            expectDriverRx = false
            expectIoVendRx = false
            expectIoPickupRx = false
            expectIoRecoveryRx = false
            recoveryCommandRunning = false
            manualDoorRetryRunning = false
            manualDoorRetryGeneration.incrementAndGet()
        }
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
            waitingPlatformRecovery = false
            startTimeMs = System.currentTimeMillis()
            ioStableValue = null
            ioStableSinceMs = 0L
            ioStartMs = 0L
            seenClosedNoProduct = false
            seenPickupProgress = false
            seenDoorOpenedFirstTime = false
            seenProductRemovedDoorOpen = false
            lastVendIoValue = null
            vendStage = 0
            driverZeroCount = 0
            seenC2InCurrentVend = false
            platformDownStartedAtMs = 0L
            seenC2AfterPlatformDown = false
            forcedPickupByDriverZero = false
            recoveryStartedAtMs = 0L
            ioTimeoutWarningEmitted = false
            ioTimeoutProlongedEmitted = false
            ioCancelStartMs = 0L
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
                    ui.onLog("Driver status=0000 ($driverZeroCount/$DRIVER_ZERO_MAX)")
                    val hasVendIoProgress = vendStage > 0 || seenC2InCurrentVend
                    if (hasVendIoProgress && driverZeroCount >= DRIVER_ZERO_MAX && !forcedPickupByDriverZero) {
                        forcedPickupByDriverZero = true
                        ui.onStep(
                            "DRIVER_ZERO_PICKUP_MODE|count=$driverZeroCount|vendStage=$vendStage|seenC2=$seenC2InCurrentVend|decision=WAIT_IO_PICKUP"
                        )
                        h.removeCallbacks(pollDriverRunnable)
                        h.removeCallbacks(pollIoVendRunnable)
                        running = false
                        waitingPickup = true
                        ioStartMs = System.currentTimeMillis()
                        ioStableValue = null
                        ioStableSinceMs = 0L
                        seenClosedNoProduct = false
                        seenPickupProgress = false
                        seenDoorOpenedFirstTime = false
                        seenProductRemovedDoorOpen = false
                        ioTimeoutWarningEmitted = false
                        ioTimeoutProlongedEmitted = false
                        ioCancelStartMs = 0L
                        ui.onNeedRetrieve("Retire su producto. Esperando cierre sin producto y segundo click.")
                        schedulePollIoPickup()
                        return
                    } else if (!hasVendIoProgress && driverZeroCount >= DRIVER_ZERO_MAX) {
                        running = false
                        waitingPickup = false
                        h.removeCallbacksAndMessages(null)
                        if (seenC2InCurrentVend) {
                            ui.onError("ANOMALO|DISPENSACION FALLIDA")
                        } else {
                            ui.onError("DRIVER_0000|DISPENSACION FALLIDA")
                        }
                        return
                    }
                } else {
                    driverZeroCount = 0
                }
            }
            if (running && !waitingPickup && isDriverDone(rx)) {
                val now = System.currentTimeMillis()
                val elapsedDownMs = if (platformDownStartedAtMs > 0L) now - platformDownStartedAtMs else -1L
                val doneTooFastAfterDown = elapsedDownMs in 0..PLATFORM_DOWN_FAST_DONE_MS
                val possibleCrushedProduct =
                    platformDownStartedAtMs > 0L &&
                        !seenC2AfterPlatformDown &&
                        elapsedDownMs > PLATFORM_DOWN_CRUSHED_TIMEOUT_MS

                if (doneTooFastAfterDown) {
                    enterPlatformRecoveryMode(
                        reason = "PLATFORM_STUCK|La plataforma parece atorada. Presione arreglar plataforma para volver a base."
                    )
                    return
                }
                if (possibleCrushedProduct) {
                    running = false
                    waitingPickup = false
                    h.removeCallbacksAndMessages(null)
                    ui.onError(
                        "PRODUCT_CRUSHED|Producto aplastado interrumpio la dispensacion (DONE sin C2 tras ${elapsedDownMs}ms en C8)"
                    )
                    return
                }

                h.removeCallbacks(pollDriverRunnable)
                h.removeCallbacks(pollIoVendRunnable)
                ui.onLog("DISPENSACION COMPLETA")
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
                seenPickupProgress = false
                seenDoorOpenedFirstTime = false
                seenProductRemovedDoorOpen = false
                ioTimeoutWarningEmitted = false
                ioTimeoutProlongedEmitted = false
                ioCancelStartMs = 0L
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
        if (expectIoRecoveryRx) {
            expectIoRecoveryRx = false
            val ioValue = parseFirstRegisterFrom0103(rx)
            if (waitingPlatformRecovery && ioValue != null) handlePlatformRecoveryIoValue(ioValue)
        }
    }

    private fun handleVendIo(value: Int) {
        if (lastVendIoValue == value) return
        lastVendIoValue = value
        when (value) {
            IO_WHITE_DOOR_OPENING -> advanceVendStage(1, "Puerta blanca: ABRIENDO")
            IO_PLATFORM_UP -> advanceVendStage(2, "Plataforma: SUBIENDO")
            IO_PLATFORM_DOWN -> {
                if (platformDownStartedAtMs <= 0L) {
                    platformDownStartedAtMs = System.currentTimeMillis()
                }
                advanceVendStage(3, "Plataforma: BAJANDO")
            }
            IO_WHITE_DOOR_CLOSING -> {
                seenC2InCurrentVend = true
                if (platformDownStartedAtMs > 0L) {
                    seenC2AfterPlatformDown = true
                }
                advanceVendStage(4, "Puerta blanca: CERRANDO")
            }
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

        if (value == IO_DOOR_OPEN_FIRST_TIME && !seenDoorOpenedFirstTime) {
            seenDoorOpenedFirstTime = true
            ui.onLog("Puerta chica: abierta por primera vez (0002)")
        }
        if (value == IO_PRODUCT_REMOVED_DOOR_OPEN && !seenProductRemovedDoorOpen) {
            seenProductRemovedDoorOpen = true
            ui.onLog("Puerta chica: producto retirado, puerta abierta (0012)")
        }

        if (value == IO_TRANSITION_42) {
            ui.onLog("Puerta chica: transicion detectada (0042)")
        }
        if (value == IO_TRANSITION_52) {
            ui.onLog("Puerta chica: transicion detectada (0052)")
        }

        if (isValidPickupProgressValue(value) && !seenPickupProgress) {
            seenPickupProgress = true
            if (ioTimeoutWarningEmitted) {
                ui.onStep("IO_TIMEOUT_RECOVERED|Puerta habilitada nuevamente")
            }
            ioTimeoutWarningEmitted = false
            ioTimeoutProlongedEmitted = false
            ioCancelStartMs = 0L
            if (value == IO_AFTER_FIRST_CLICK) {
                ui.onLog("Puerta chica: 1er click confirmado (0082)")
            } else if (value == IO_DOOR_OPEN_FIRST_TIME) {
                ui.onLog("Puerta chica: recuperacion por apertura inicial (0002)")
            } else if (value == IO_PRODUCT_REMOVED_DOOR_OPEN) {
                ui.onLog("Puerta chica: recuperacion por producto retirado (0012)")
            } else if (value == IO_DOOR_CLOSED_NO_PROD) {
                ui.onLog("Puerta chica: recuperacion por cerrada sin producto (0092)")
            } else if (value == IO_SECOND_CLICK) {
                ui.onLog("Puerta chica: recuperacion directa por fin de retiro (00D2)")
            }
        }
        if (value == IO_DOOR_CLOSED_NO_PROD && !seenClosedNoProduct) {
            seenClosedNoProduct = true
            ui.onLog("Puerta chica: cerrada SIN producto (0092)")
        }
        if (value == IO_SECOND_CLICK) {
            if (ioTimeoutWarningEmitted) {
                ui.onStep("IO_TIMEOUT_RECOVERED|Puerta habilitada nuevamente")
                ioTimeoutWarningEmitted = false
                ioTimeoutProlongedEmitted = false
                ioCancelStartMs = 0L
            }
            if (!seenClosedNoProduct) {
                ui.onLog("Puerta chica: D2 recibido sin 92 estable previo (cierre forzado por fin de retiro)")
            }
            ui.onLog("Puerta chica: 2do click confirmado (00D2) -> FIN")
            waitingPickup = false
            h.removeCallbacksAndMessages(null)
            ui.onDone()
        }
    }

    fun requestPlatformRecoveryToBase(): Boolean {
        if (!waitingPlatformRecovery) {
            ui.onLog("Recuperacion ignorada: no hay plataforma atorada en espera.")
            return false
        }
        return startPlatformRecoveryToBase()
    }

    fun requestManualPlatformRecoveryToBase(): Boolean {
        if (waitingPlatformRecovery) return requestPlatformRecoveryToBase()
        waitingPlatformRecovery = true
        recoveryStartedAtMs = 0L
        ui.onStep("PLATFORM_MANUAL_RECOVERY|decision=FORCE_TOY_0")
        return startPlatformRecoveryToBase()
    }

    fun requestManualDoorRetrySequence(): Boolean {
        if (!waitingPickup || !ioTimeoutProlongedEmitted) {
            ui.onLog("MANUAL_RETRY_IGNORED: prolonged pickup wait is not active.")
            return false
        }
        if (manualDoorRetryRunning) {
            ui.onLog("MANUAL_RETRY_IGNORED: retry already running.")
            return false
        }
        val customRecovery = platformRecoveryCommand ?: run {
            ui.onLog("MANUAL_RETRY_IGNORED: retry command is not configured.")
            return false
        }

        manualDoorRetryRunning = true
        val retryGeneration = manualDoorRetryGeneration.incrementAndGet()
        val shouldContinueRetry = {
            synchronized(lifecycleLock) {
                manualDoorRetryGeneration.get() == retryGeneration && waitingPickup && ioTimeoutProlongedEmitted
            }
        }
        val runIfActive = { action: () -> Unit ->
            synchronized(lifecycleLock) {
                if (shouldContinueRetry()) {
                    action()
                    true
                } else {
                    false
                }
            }
        }
        h.removeCallbacks(pollIoPickupRunnable)
        expectIoPickupRx = false
        ui.onLog("MANUAL_RETRY_START: close raw serial, open SDK once, ToY(2000), wait, ToY(0), wait, close SDK, reopen raw serial.")
        ui.onStep("MANUAL_RETRY_START|decision=TOY_2000_THEN_TOY_0")

        Thread({
            val result = try {
                customRecovery.runManualDoorRetrySequence(
                    shouldContinue = shouldContinueRetry,
                    runIfActive = runIfActive,
                )
            } catch (ex: Throwable) {
                PlatformRecoveryCommandResult(
                    ok = false,
                    commandName = "Manual ToY(2000)->ToY(0)",
                    detail = ex.message ?: ex.javaClass.simpleName
                )
            }
            h.post {
                if (manualDoorRetryGeneration.get() != retryGeneration) {
                    return@post
                }
                manualDoorRetryRunning = false
                ui.onLog("MANUAL_RETRY_FINISHED ${result.commandName}: ${if (result.ok) "OK" else "ERROR"} ${result.detail}".trim())
                ui.onStep("MANUAL_RETRY_FINISHED|ok=${result.ok}")
                if (shouldContinueRetry()) {
                    ioStartMs = System.currentTimeMillis() - IO_WAIT_TIMEOUT_MS
                    ioCancelStartMs = System.currentTimeMillis() - IO_CANCEL_TIMEOUT_MS
                    schedulePollIoPickup()
                }
            }
        }, "ManualDoorRetrySdk").start()
        return true
    }

    private fun startPlatformRecoveryToBase(): Boolean {
        if (recoveryCommandRunning) {
            ui.onLog("Recuperacion ignorada: ya hay una recuperacion en curso.")
            return false
        }
        recoveryStartedAtMs = System.currentTimeMillis()

        val customRecovery = platformRecoveryCommand
        if (customRecovery != null) {
            recoveryCommandRunning = true
            h.removeCallbacks(pollDriverRunnable)
            h.removeCallbacks(pollIoVendRunnable)
            h.removeCallbacks(pollIoPickupRunnable)
            h.removeCallbacks(pollIoRecoveryRunnable)
            expectDriverRx = false
            expectIoVendRx = false
            expectIoPickupRx = false
            expectIoRecoveryRx = false
            ui.onLog("Recuperacion de plataforma: enviando posicion Y 0 con flujo calibrador.")
            Thread({
                val result = try {
                    customRecovery.moveToBase()
                } catch (ex: Throwable) {
                    PlatformRecoveryCommandResult(
                        ok = false,
                        commandName = "ToY(0)",
                        detail = ex.message ?: ex.javaClass.simpleName
                    )
                }
                h.post {
                    recoveryCommandRunning = false
                    ui.onLog("RECOVERY_TO_BASE ${result.commandName}: ${if (result.ok) "OK" else "ERROR"} ${result.detail}".trim())
                    if (!result.ok) {
                        waitingPlatformRecovery = false
                        ui.onError("PLATFORM_RECOVERY_COMMAND_FAILED|No se pudo enviar recuperacion a base: ${result.detail.ifBlank { result.commandName }}")
                    } else if (waitingPlatformRecovery) {
                        recoveryStartedAtMs = System.currentTimeMillis()
                        ui.onLog("Recuperacion de plataforma: polling IO activo, esperando base C2/82/02/92.")
                        schedulePollIoRecovery()
                    }
                }
            }, "PlatformRecoverySdk").start()
            return true
        } else {
            if (!serial.isOpen()) {
                ui.onError("Abre el puerto primero.")
                return false
            }
            val resetHex = CommandSet.buildResetLift()
            ui.onLog("Recuperacion de plataforma: enviando ResetLift fallback.")
            ui.onLog("TX RECOVERY_TO_BASE: $resetHex")
            serial.sendHex(resetHex, serialListener)
        }

        schedulePollIoRecovery()
        return true
    }

    private fun enterPlatformRecoveryMode(reason: String) {
        h.removeCallbacks(pollDriverRunnable)
        h.removeCallbacks(pollIoVendRunnable)
        h.removeCallbacks(pollIoPickupRunnable)
        running = false
        waitingPickup = false
        waitingPlatformRecovery = true
        expectDriverRx = false
        expectIoVendRx = false
        expectIoPickupRx = false
        expectIoRecoveryRx = false
        recoveryStartedAtMs = 0L
        ui.onStep("PLATFORM_STUCK_WAITING_ACTION|decision=WAIT_RESET_LIFT")
        ui.onPlatformStuck(reason.substringAfter("|", reason))
    }

    private fun handlePlatformRecoveryIoValue(value: Int) {
        if (!isPlatformRecoveryBaseSignal(value)) return
        ui.onLog("Recuperacion de plataforma confirmada por IO=${formatIoValue(value)}.")
        waitingPlatformRecovery = false
        waitingPickup = true
        ioStartMs = System.currentTimeMillis()
        ioStableValue = null
        ioStableSinceMs = 0L
        seenClosedNoProduct = false
        seenPickupProgress = false
        seenDoorOpenedFirstTime = false
        seenProductRemovedDoorOpen = false
        ioTimeoutWarningEmitted = false
        ioTimeoutProlongedEmitted = false
        ioCancelStartMs = 0L
        ui.onStep("PLATFORM_RECOVERY_DONE|io=00C2|decision=RESUME_PICKUP")
        ui.onNeedRetrieve("Retire su producto. Esperando cierre sin producto y segundo click.")
        schedulePollIoPickup()
    }

    private fun isPlatformRecoveryBaseSignal(value: Int): Boolean {
        return value == IO_WHITE_DOOR_CLOSING ||
            value == IO_AFTER_FIRST_CLICK ||
            value == IO_DOOR_OPEN_FIRST_TIME ||
            value == IO_DOOR_CLOSED_NO_PROD
    }

    private fun formatIoValue(value: Int): String {
        return value.toString(16).uppercase().padStart(2, '0')
    }

    private fun advanceVendStage(newStage: Int, logMsg: String) {
        if (newStage <= vendStage) return
        vendStage = newStage
        ui.onLog(logMsg)
    }

    private fun schedulePollDriver() = h.postDelayed(pollDriverRunnable, 120L)
    private fun schedulePollIoVend() = h.postDelayed(pollIoVendRunnable, 250L)
    private fun schedulePollIoPickup() = h.postDelayed(pollIoPickupRunnable, 80L)
    private fun schedulePollIoRecovery() = h.postDelayed(pollIoRecoveryRunnable, 120L)

    private fun isDriverDone(rxNoSpacesUpper: String): Boolean = rxNoSpacesUpper.contains("0103020200")

    private fun parseFirstRegisterFrom0103(rxNoSpacesUpper: String): Int? {
        if (!rxNoSpacesUpper.startsWith("0103") || rxNoSpacesUpper.length < 10) return null
        return try {
            rxNoSpacesUpper.substring(6, 10).toInt(16)
        } catch (_: Exception) {
            null
        }
    }

    private fun isValidPickupProgressValue(value: Int): Boolean {
        return value == IO_AFTER_FIRST_CLICK ||
            value == IO_DOOR_OPEN_FIRST_TIME ||
            value == IO_PRODUCT_REMOVED_DOOR_OPEN ||
            value == IO_DOOR_CLOSED_NO_PROD ||
            value == IO_SECOND_CLICK
    }

    private val pollDriverRunnable = object : Runnable {
        override fun run() {
            if (!running || waitingPickup) return
            val elapsed = System.currentTimeMillis() - startTimeMs
            if (elapsed > DRIVER_TIMEOUT_MS) {
                running = false
                h.removeCallbacksAndMessages(null)
                ui.onError("DRIVER_TIMEOUT|Timeout driver: no termino en 60s")
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
            val now = System.currentTimeMillis()
            val elapsed = now - ioStartMs
            if (!seenPickupProgress && elapsed > IO_WAIT_TIMEOUT_MS) {
                if (!ioTimeoutWarningEmitted) {
                    ioTimeoutWarningEmitted = true
                    ioCancelStartMs = now
                    ui.onError("IO_TIMEOUT|Timeout: puerta atorada")
                } else if (!ioTimeoutProlongedEmitted && now - ioCancelStartMs > IO_CANCEL_TIMEOUT_MS) {
                    ioTimeoutProlongedEmitted = true
                    ui.onStep("IO_TIMEOUT_PROLONGED|Apertura de puerta tardando mas de lo esperado")
                }
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

    private val pollIoRecoveryRunnable = object : Runnable {
        override fun run() {
            if (!waitingPlatformRecovery) return
            if (recoveryStartedAtMs > 0L && (System.currentTimeMillis() - recoveryStartedAtMs) > PLATFORM_RECOVERY_TIMEOUT_MS) {
                waitingPlatformRecovery = false
                h.removeCallbacksAndMessages(null)
                ui.onError("PLATFORM_RECOVERY_TIMEOUT|No se pudo volver a base a tiempo.")
                return
            }
            if (expectDriverRx || expectIoVendRx || expectIoPickupRx || expectIoRecoveryRx) {
                h.postDelayed(this, 220L)
                return
            }
            expectIoRecoveryRx = true
            serial.sendHex(CommandSet.POLL_IO_STATUS, serialListener)
            h.postDelayed(this, POLL_IO_RECOVERY_MS)
        }
    }

    companion object {
        private const val DRIVER_TIMEOUT_MS = 60_000L
        private const val DRIVER_ZERO_MAX = 3
        private const val POLL_DRIVER_MS = 950L
        private const val POLL_IO_VEND_MS = 1_200L
        private const val POLL_IO_PICKUP_MS = 420L
        private const val IO_WAIT_TIMEOUT_MS = 10_000L
        private const val IO_CANCEL_TIMEOUT_MS = 120_000L
        private const val PLATFORM_DOWN_FAST_DONE_MS = 3_000L
        private const val PLATFORM_DOWN_CRUSHED_TIMEOUT_MS = 12_000L
        private const val PLATFORM_RECOVERY_TIMEOUT_MS = 120_000L
        private const val IO_STABLE_MS = 600L
        private const val VEND_START_DELAY_MS = 350L
        private const val POLL_IO_RECOVERY_MS = 450L

        private const val IO_DOOR_OPEN_FIRST_TIME = 2 // este es el 02
        private const val IO_PRODUCT_REMOVED_DOOR_OPEN = 18 // este es el 12
        private const val IO_AFTER_FIRST_CLICK = 130 // este es el 82
        private const val IO_DOOR_CLOSED_NO_PROD = 146 // este es el 92
        private const val IO_PLATFORM_UP = 216
        private const val IO_PLATFORM_DOWN = 200
        private const val IO_WHITE_DOOR_OPENING = 210
        private const val IO_WHITE_DOOR_CLOSING = 194  // este es el C2
        private const val IO_SECOND_CLICK = 210 // este es el D2
        private const val IO_TRANSITION_42 = 66 // este es el 42
        private const val IO_TRANSITION_52 = 82 // este es el 52
    }
}

