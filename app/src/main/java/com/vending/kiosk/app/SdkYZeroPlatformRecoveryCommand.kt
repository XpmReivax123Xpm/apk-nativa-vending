package com.vending.kiosk.app

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import cc.uling.usdk.USDK
import cc.uling.usdk.board.UBoard
import cc.uling.usdk.board.wz.para.TYReplyPara
import com.vending.kiosk.integration.serial.runtime.SerialManager
import com.vending.kiosk.integration.serial.runtime.VendingFlowController
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

class SdkYZeroPlatformRecoveryCommand(
    private val context: Context,
    private val serial: SerialManager,
    private val serialListener: SerialManager.Listener,
    private val portProvider: () -> String,
    private val baudProvider: () -> Int,
    private val beforeSdkOpen: () -> Unit = {},
    private val afterRawReopen: () -> Unit = {},
) : VendingFlowController.PlatformRecoveryCommand {

    override fun moveToBase(): VendingFlowController.PlatformRecoveryCommandResult {
        val port = portProvider().ifBlank { DEFAULT_PORT }
        val baud = baudProvider().takeIf { it > 0 } ?: DEFAULT_BAUD
        var board: UBoard? = null

        return try {
            runCatching { beforeSdkOpen() }
            runCatching { serialListener.onStatus("RECOVERY_SDK: cerrando serial raw para liberar puerto.") }
            serial.close()
            sleepQuietly(RAW_RELEASE_DELAY_MS)
            val app = context.applicationContext as? Application
                ?: return errorResult("Contexto de aplicacion invalido")

            runCatching { serialListener.onStatus("RECOVERY_SDK: abriendo SDK en $port @ $baud.") }
            val openResult = runOnMainBlocking {
                USDK.getInstance().init(app)
                board = USDK.getInstance().create(port)
                board?.EF_OpenDev(port, baud) ?: -1
            }
            val openError = openResult.error
            if (openError != null) {
                return errorResult("SDK no pudo abrir $port @ $baud (${openError.message ?: openError.javaClass.simpleName})")
            }
            val openRet = openResult.value ?: -1
            if (openRet != 0) {
                return errorResult("SDK no pudo abrir $port @ $baud (ret=$openRet)")
            }
            runCatching { serialListener.onStatus("RECOVERY_SDK: SDK conectado, enviando ToY(0).") }

            val para = TYReplyPara(BOARD_ADDR, 0.toShort())
            val toYResult = runOnMainBlocking {
                board?.ToY(para)
                para.isOK
            }
            val toYError = toYResult.error
            if (toYError != null) {
                return errorResult("ToY(0) fallo (${toYError.message ?: toYError.javaClass.simpleName})")
            }
            val toYResultCode = para.resultCode
            val toYAcceptedWithWarning = !para.isOK && isRecoverableToYResultCode(toYResultCode)
            if (!para.isOK && !toYAcceptedWithWarning) {
                return errorResult("ToY(0) rechazado por placa (codigo $toYResultCode)")
            }
            if (toYAcceptedWithWarning) {
                runCatching {
                    serialListener.onStatus("RECOVERY_SDK: ToY(0) devolvio codigo $toYResultCode; se continua como advertencia porque en campo puede mover la plataforma.")
                }
            }
            runCatching { serialListener.onStatus("RECOVERY_SDK: ToY(0) aceptado, manteniendo SDK abierto ${SDK_HOLD_AFTER_TOY_MS / 1000}s.") }
            sleepQuietly(SDK_HOLD_AFTER_TOY_MS)

            runCatching { serialListener.onStatus("RECOVERY_SDK: cerrando SDK.") }
            runOnMainBlocking {
                if (board?.EF_Opened() == true) board?.EF_CloseDev()
            }
            board = null
            sleepQuietly(RAW_REOPEN_DELAY_MS)

            val rawSerialReopened = reopenRawSerial(port, baud)
            if (!rawSerialReopened) {
                return errorResult("ToY(0) OK, pero no se pudo reabrir serial raw para polling IO")
            }
            runCatching { afterRawReopen() }

            VendingFlowController.PlatformRecoveryCommandResult(
                ok = true,
                commandName = COMMAND_NAME,
                detail = "posicion=0 puntos Y; puerto=$port; sdkCode=$toYResultCode"
            )
        } catch (ex: Throwable) {
            errorResult(ex.message ?: ex.javaClass.simpleName)
        } finally {
            runOnMainBlocking {
                if (board?.EF_Opened() == true) board?.EF_CloseDev()
            }
            if (!serial.isOpen()) {
                runCatching {
                    sleepQuietly(RAW_REOPEN_DELAY_MS)
                    serial.open(port, baud, serialListener)
                    afterRawReopen()
                }
            }
        }
    }

    /**
     * Manual retry sequence for the prolonged small-door wait flow.
     *
     * Keeps moveToBase() unchanged while running the field-tested sequence: raw cleanup, SDK open
     * once, ToY(2000), wait 8s, ToY(0), wait 8s, SDK close, raw serial reopen.
     */
    override fun runManualDoorRetrySequence(
        shouldContinue: () -> Boolean,
        runIfActive: (() -> Unit) -> Boolean,
    ): VendingFlowController.PlatformRecoveryCommandResult {
        val port = portProvider().ifBlank { DEFAULT_PORT }
        val baud = baudProvider().takeIf { it > 0 } ?: DEFAULT_BAUD
        var board: UBoard? = null
        var rawSerialOwnedByActiveRetry = false

        return try {
            if (!shouldContinue()) return manualRetryCanceledResult()
            runCatching { beforeSdkOpen() }
            runCatching { serialListener.onStatus("MANUAL_RETRY_SDK: cerrando serial raw para liberar puerto.") }
            serial.close()
            rawSerialOwnedByActiveRetry = true
            sleepQuietly(RAW_RELEASE_DELAY_MS)
            if (!shouldContinue()) return manualRetryCanceledResult()
            val app = context.applicationContext as? Application
                ?: return manualRetryErrorResult("Contexto de aplicacion invalido")

            runCatching { serialListener.onStatus("MANUAL_RETRY_SDK: abriendo SDK en $port @ $baud.") }
            if (!shouldContinue()) return manualRetryCanceledResult()
            val openResult = runOnMainBlocking {
                if (!shouldContinue()) return@runOnMainBlocking -1
                USDK.getInstance().init(app)
                board = USDK.getInstance().create(port)
                board?.EF_OpenDev(port, baud) ?: -1
            }
            val openError = openResult.error
            if (openError != null) {
                return manualRetryErrorResult("SDK no pudo abrir $port @ $baud (${openError.message ?: openError.javaClass.simpleName})")
            }
            val openRet = openResult.value ?: -1
            if (openRet != 0) {
                return manualRetryErrorResult("SDK no pudo abrir $port @ $baud (ret=$openRet)")
            }

            if (!shouldContinue()) return manualRetryCanceledResult()
            val toY2000 = sendToY(board, 2000)
            if (!toY2000.ok) return manualRetryErrorResult(toY2000.detail)
            runCatching { serialListener.onStatus("MANUAL_RETRY_SDK: ToY(2000) enviado, esperando ${MANUAL_RETRY_HOLD_MS / 1000}s.") }
            sleepQuietly(MANUAL_RETRY_HOLD_MS)

            if (!shouldContinue()) return manualRetryCanceledResult()
            val toYZero = sendToY(board, 0)
            if (!toYZero.ok) return manualRetryErrorResult(toYZero.detail)
            runCatching { serialListener.onStatus("MANUAL_RETRY_SDK: ToY(0) enviado, esperando ${MANUAL_RETRY_HOLD_MS / 1000}s.") }
            sleepQuietly(MANUAL_RETRY_HOLD_MS)

            if (!shouldContinue()) return manualRetryCanceledResult()
            runCatching { serialListener.onStatus("MANUAL_RETRY_SDK: cerrando SDK.") }
            runOnMainBlocking {
                if (board?.EF_Opened() == true) board?.EF_CloseDev()
            }
            board = null
            sleepQuietly(RAW_REOPEN_DELAY_MS)

            if (!shouldContinue()) return manualRetryCanceledResult()
            val rawSerialReopened = runIfActive {
                if (serial.isOpen()) serial.close()
                serial.open(port, baud, serialListener)
            } && serial.isOpen()
            if (!rawSerialReopened) {
                return manualRetryErrorResult("ToY(2000) y ToY(0) OK, pero no se pudo reabrir serial raw")
            }
            rawSerialOwnedByActiveRetry = false
            if (!runIfActive { afterRawReopen() }) return manualRetryCanceledResult()

            VendingFlowController.PlatformRecoveryCommandResult(
                ok = true,
                commandName = MANUAL_RETRY_COMMAND_NAME,
                detail = "ToY(2000) codigo=${toY2000.resultCode}; ToY(0) codigo=${toYZero.resultCode}; puerto=$port"
            )
        } catch (ex: Throwable) {
            manualRetryErrorResult(ex.message ?: ex.javaClass.simpleName)
        } finally {
            runOnMainBlocking {
                if (board?.EF_Opened() == true) board?.EF_CloseDev()
            }
            if (rawSerialOwnedByActiveRetry && shouldContinue() && !serial.isOpen()) {
                runCatching {
                    sleepQuietly(RAW_REOPEN_DELAY_MS)
                    runIfActive {
                        serial.open(port, baud, serialListener)
                    }
                    if (serial.isOpen()) runIfActive {
                        afterRawReopen()
                    }
                }
            }
        }
    }

    private fun sendToY(board: UBoard?, position: Int): ToYSendResult {
        runCatching { serialListener.onStatus("MANUAL_RETRY_SDK: enviando ToY($position).") }
        val para = TYReplyPara(BOARD_ADDR, position.toShort())
        val result = runOnMainBlocking {
            board?.ToY(para)
            para.isOK
        }
        val error = result.error
        if (error != null) {
            return ToYSendResult(
                ok = false,
                resultCode = para.resultCode,
                detail = "ToY($position) fallo (${error.message ?: error.javaClass.simpleName})"
            )
        }

        val resultCode = para.resultCode
        val acceptedWithWarning = !para.isOK && isRecoverableToYResultCode(resultCode)
        if (!para.isOK && !acceptedWithWarning) {
            return ToYSendResult(
                ok = false,
                resultCode = resultCode,
                detail = "ToY($position) rechazado por placa (codigo $resultCode)"
            )
        }
        if (acceptedWithWarning) {
            runCatching {
                serialListener.onStatus("MANUAL_RETRY_SDK: ToY($position) devolvio codigo $resultCode; se continua como advertencia.")
            }
        }
        return ToYSendResult(ok = true, resultCode = resultCode, detail = "ToY($position) aceptado")
    }

    private fun isRecoverableToYResultCode(resultCode: Int): Boolean {
        return resultCode == TOY_RECOVERY_WARNING_CODE
    }

    private fun reopenRawSerial(port: String, baud: Int): Boolean {
        runCatching {
            if (serial.isOpen()) serial.close()
            serial.open(port, baud, serialListener)
        }
        return serial.isOpen()
    }

    private fun <T> runOnMainBlocking(block: () -> T): MainThreadResult<T> {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return try {
                MainThreadResult(value = block())
            } catch (ex: Throwable) {
                MainThreadResult(error = ex)
            }
        }

        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<MainThreadResult<T>>()
        Handler(Looper.getMainLooper()).post {
            resultRef.set(
                try {
                    MainThreadResult(value = block())
                } catch (ex: Throwable) {
                    MainThreadResult(error = ex)
                }
            )
            latch.countDown()
        }
        latch.await()
        return resultRef.get() ?: MainThreadResult(error = IllegalStateException("SDK main thread sin resultado"))
    }

    private data class MainThreadResult<T>(
        val value: T? = null,
        val error: Throwable? = null
    )

    private data class ToYSendResult(
        val ok: Boolean,
        val resultCode: Int,
        val detail: String
    )

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun errorResult(detail: String): VendingFlowController.PlatformRecoveryCommandResult {
        return VendingFlowController.PlatformRecoveryCommandResult(
            ok = false,
            commandName = COMMAND_NAME,
            detail = detail
        )
    }

    private fun manualRetryErrorResult(detail: String): VendingFlowController.PlatformRecoveryCommandResult {
        return VendingFlowController.PlatformRecoveryCommandResult(
            ok = false,
            commandName = MANUAL_RETRY_COMMAND_NAME,
            detail = detail
        )
    }

    private fun manualRetryCanceledResult(): VendingFlowController.PlatformRecoveryCommandResult {
        return VendingFlowController.PlatformRecoveryCommandResult(
            ok = false,
            commandName = MANUAL_RETRY_COMMAND_NAME,
            detail = "cancelado por ciclo de dispensacion inactivo"
        )
    }

    private companion object {
        private const val COMMAND_NAME = "ToY(0)"
        private const val MANUAL_RETRY_COMMAND_NAME = "Manual ToY(2000)->ToY(0)"
        private const val BOARD_ADDR = 1
        private const val DEFAULT_PORT = "/dev/ttyS1"
        private const val DEFAULT_BAUD = 9600
        private const val RAW_RELEASE_DELAY_MS = 3_000L
        private const val SDK_HOLD_AFTER_TOY_MS = 7_000L
        private const val MANUAL_RETRY_HOLD_MS = 8_000L
        private const val RAW_REOPEN_DELAY_MS = 2_000L
        private const val TOY_RECOVERY_WARNING_CODE = 204
    }
}
