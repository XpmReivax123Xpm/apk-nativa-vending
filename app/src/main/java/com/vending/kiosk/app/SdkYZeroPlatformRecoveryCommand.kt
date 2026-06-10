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
            if (!para.isOK) {
                return errorResult("ToY(0) rechazado por placa (codigo ${para.resultCode})")
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
                detail = "posicion=0 puntos Y; puerto=$port"
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

    private companion object {
        private const val COMMAND_NAME = "ToY(0)"
        private const val BOARD_ADDR = 1
        private const val DEFAULT_PORT = "/dev/ttyS1"
        private const val DEFAULT_BAUD = 9600
        private const val RAW_RELEASE_DELAY_MS = 3_000L
        private const val SDK_HOLD_AFTER_TOY_MS = 7_000L
        private const val RAW_REOPEN_DELAY_MS = 2_000L
    }
}
