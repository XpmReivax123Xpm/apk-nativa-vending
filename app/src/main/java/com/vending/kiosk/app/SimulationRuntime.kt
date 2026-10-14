package com.vending.kiosk.app

import com.vending.kiosk.BuildConfig
import com.vending.kiosk.integration.serial.runtime.CommandSet
import com.vending.kiosk.integration.serial.runtime.SerialManager
import com.vending.kiosk.integration.serial.runtime.SerialTransportConfig
import com.vending.kiosk.integration.serial.runtime.VendingFlowController

/**
 * Applies the serial transport chosen at build time.
 *
 * Production builds keep the native /dev/ttyS1 path untouched; a build made with
 * `-PvendingSerial=tcp` routes the same Modbus traffic to a board simulator
 * running on the PC that hosts the emulator.
 */
object SimulationRuntime {
    @Volatile private var applied = false

    fun apply() {
        if (applied) return
        applied = true
        // Cerrojo 2: el modo simulador solo existe en builds de depuracion. Aunque
        // un APK de despliegue trajera el valor "tcp" horneado, la app abriria el
        // puerto serie nativo igual.
        val transport = if (BuildConfig.DEBUG) BuildConfig.SERIAL_TRANSPORT else SerialTransportConfig.MODE_NATIVE
        SerialTransportConfig.configure(transport, BuildConfig.SERIAL_SIM_ENDPOINT)
    }

    fun isSimulator(): Boolean = SerialTransportConfig.isSimulator()
}

/**
 * Platform recovery for the simulator build.
 *
 * The real recovery runs inside the ZhongDa SDK, which only ships ARM libraries
 * and cannot load on the x86_64 emulator. This implementation keeps the same
 * operational sequence (release the raw transport, move the platform to Y, wait,
 * reopen) so the whole "platform stuck" flow stays exercisable on the PC.
 */
class SimulatorPlatformRecoveryCommand(
    private val serial: SerialManager,
    private val serialListener: SerialManager.Listener,
    private val portProvider: () -> String,
    private val baudProvider: () -> Int,
) : VendingFlowController.PlatformRecoveryCommand {

    override fun moveToBase(): VendingFlowController.PlatformRecoveryCommandResult =
        sendToY(position = 0, commandName = "ToY(0)")

    override fun runManualDoorRetrySequence(
        shouldContinue: () -> Boolean,
        runIfActive: (() -> Unit) -> Boolean,
    ): VendingFlowController.PlatformRecoveryCommandResult {
        val first = sendToY(position = 2000, commandName = MANUAL_COMMAND_NAME)
        if (!first.ok) return first
        if (!shouldContinue()) return cancelledResult()
        return sendToY(position = 0, commandName = MANUAL_COMMAND_NAME)
    }

    private fun sendToY(position: Int, commandName: String): VendingFlowController.PlatformRecoveryCommandResult {
        val port = portProvider().ifBlank { DEFAULT_PORT }
        val baud = baudProvider().takeIf { it > 0 } ?: DEFAULT_BAUD
        return try {
            serialListener.onStatus("RECOVERY_SIM: liberando $port para enviar ToY($position).")
            serial.close()
            sleepQuietly(RAW_RELEASE_DELAY_MS)
            serial.open(port, baud, serialListener)
            if (!serial.isOpen()) {
                return VendingFlowController.PlatformRecoveryCommandResult(
                    ok = false,
                    commandName = commandName,
                    detail = "no se pudo reabrir el transporte simulado"
                )
            }
            val frame = CommandSet.buildToYPosition(position)
            serialListener.onStatus("TX RECOVERY_SIM: $frame")
            serial.sendHex(frame, serialListener)
            sleepQuietly(SIM_HOLD_MS)
            VendingFlowController.PlatformRecoveryCommandResult(
                ok = true,
                commandName = commandName,
                detail = "posicion=$position puntos Y; transporte=$port"
            )
        } catch (ex: Throwable) {
            VendingFlowController.PlatformRecoveryCommandResult(
                ok = false,
                commandName = commandName,
                detail = ex.message ?: ex.javaClass.simpleName
            )
        }
    }

    private fun cancelledResult(): VendingFlowController.PlatformRecoveryCommandResult =
        VendingFlowController.PlatformRecoveryCommandResult(
            ok = false,
            commandName = MANUAL_COMMAND_NAME,
            detail = "cancelado por ciclo de dispensacion inactivo"
        )

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private companion object {
        private const val MANUAL_COMMAND_NAME = "Manual ToY(2000)->ToY(0)"
        private const val DEFAULT_PORT = "/dev/ttyS1"
        private const val DEFAULT_BAUD = 9600
        private const val RAW_RELEASE_DELAY_MS = 500L
        private const val SIM_HOLD_MS = 2_000L
    }
}
