package com.vending.kiosk.app

import android.app.Application
import android.content.Context
import cc.uling.usdk.USDK
import cc.uling.usdk.board.UBoard
import cc.uling.usdk.board.wz.para.TYReplyPara
import com.vending.kiosk.integration.serial.runtime.SerialManager
import com.vending.kiosk.integration.serial.runtime.VendingFlowController

class SdkYZeroPlatformRecoveryCommand(
    private val context: Context,
    private val serial: SerialManager,
    private val serialListener: SerialManager.Listener,
    private val portProvider: () -> String,
    private val baudProvider: () -> Int,
) : VendingFlowController.PlatformRecoveryCommand {

    override fun moveToBase(): VendingFlowController.PlatformRecoveryCommandResult {
        val port = portProvider().ifBlank { DEFAULT_PORT }
        val baud = baudProvider().takeIf { it > 0 } ?: DEFAULT_BAUD
        var board: UBoard? = null

        return try {
            serial.close()
            val app = context.applicationContext as? Application
                ?: return errorResult("Contexto de aplicacion invalido")
            USDK.getInstance().init(app)

            board = USDK.getInstance().create(port)
            val openRet = board?.EF_OpenDev(port, baud) ?: -1
            if (openRet != 0) {
                return errorResult("SDK no pudo abrir $port @ $baud (ret=$openRet)")
            }

            val para = TYReplyPara(BOARD_ADDR, 0.toShort())
            board?.ToY(para)
            if (!para.isOK) {
                return errorResult("ToY(0) rechazado por placa (codigo ${para.resultCode})")
            }

            runCatching {
                if (board?.EF_Opened() == true) board?.EF_CloseDev()
            }
            board = null

            val rawSerialReopened = reopenRawSerial(port, baud)
            if (!rawSerialReopened) {
                return errorResult("ToY(0) OK, pero no se pudo reabrir serial raw para polling IO")
            }

            VendingFlowController.PlatformRecoveryCommandResult(
                ok = true,
                commandName = COMMAND_NAME,
                detail = "posicion=0 puntos Y; puerto=$port"
            )
        } catch (ex: Exception) {
            errorResult(ex.message ?: "sin detalle")
        } finally {
            runCatching {
                if (board?.EF_Opened() == true) board?.EF_CloseDev()
            }
            if (!serial.isOpen()) {
                runCatching { serial.open(port, baud, serialListener) }
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
    }
}
