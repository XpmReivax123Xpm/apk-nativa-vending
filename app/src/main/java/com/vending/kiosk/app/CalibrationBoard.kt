package com.vending.kiosk.app

import cc.uling.usdk.board.UBoard
import cc.uling.usdk.board.wz.para.DSReplyPara
import cc.uling.usdk.board.wz.para.SYPReplyPara
import cc.uling.usdk.board.wz.para.TYReplyPara
import cc.uling.usdk.board.wz.para.YPReplyPara
import cc.uling.usdk.board.wz.para.YSReplyPara
import com.vending.kiosk.integration.serial.runtime.CommandSet
import com.vending.kiosk.integration.serial.runtime.HexUtil
import com.vending.kiosk.integration.serial.runtime.SerialTransportConfig
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * Board operations the Calibrator needs, in the same shape the ZhongDa SDK exposes.
 *
 * Keeping the SDK method names and para objects means the calibrator screen works
 * unchanged on the tablet (SDK implementation) while a simulator build can run the
 * same operations against the PC board simulator, because the SDK native libraries
 * are ARM-only and cannot load on an x86_64 emulator.
 */
interface CalibrationBoard {
    fun EF_OpenDev(port: String, baud: Int): Int
    fun EF_Opened(): Boolean
    fun EF_CloseDev(): Int
    fun GetYStatus(para: YSReplyPara)
    fun GetDropStatus(para: DSReplyPara)
    fun GetYPos(para: YPReplyPara)
    fun SeYPos(para: SYPReplyPara)
    fun ToY(para: TYReplyPara)
}

/** Production implementation: the call goes straight to the vendor SDK. */
class SdkCalibrationBoard(private val board: UBoard) : CalibrationBoard {
    override fun EF_OpenDev(port: String, baud: Int): Int = board.EF_OpenDev(port, baud)
    override fun EF_Opened(): Boolean = board.EF_Opened()
    override fun EF_CloseDev(): Int = board.EF_CloseDev()
    override fun GetYStatus(para: YSReplyPara) = board.GetYStatus(para)
    override fun GetDropStatus(para: DSReplyPara) = board.GetDropStatus(para)
    override fun GetYPos(para: YPReplyPara) = board.GetYPos(para)
    override fun SeYPos(para: SYPReplyPara) = board.SeYPos(para)
    override fun ToY(para: TYReplyPara) = board.ToY(para)
}

/**
 * Simulator implementation.
 *
 * Every request is framed from the para object itself (function, register and length
 * are the ones the SDK would use), so the frames are the real calibration protocol
 * and the board simulator answers them. Responses fill the same para fields the
 * screen reads, so the calibrator shows live values instead of a fake screen.
 */
class SimulatedCalibrationBoard(
    private val logger: (String) -> Unit = {},
) : CalibrationBoard {

    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var opened = false

    override fun EF_OpenDev(port: String, baud: Int): Int {
        closeQuietly()
        return try {
            val link = SerialTransportConfig.current().open(port, baud)
                ?: return -1
            input = link.input
            output = link.output
            opened = true
            logger("Conectado a ${SerialTransportConfig.describe(port, baud)}")
            0
        } catch (ex: Throwable) {
            logger("No se pudo abrir el simulador de placa: ${ex.message}")
            -1
        }
    }

    override fun EF_Opened(): Boolean = opened

    override fun EF_CloseDev(): Int {
        closeQuietly()
        return 0
    }

    override fun GetYStatus(para: YSReplyPara) {
        val values = readHolding(para.reg.toInt(), para.readLen.toInt()) ?: return markFailed(para, "sin respuesta de la placa")
        // La placa devuelve estado y falla empaquetados en un solo registro.
        para.runStatus = (values[0] shr 8) and 0xFF
        para.faultCode = values[0] and 0xFF
        markOk(para)
    }

    override fun GetDropStatus(para: DSReplyPara) {
        val values = readHolding(para.reg.toInt(), para.readLen.toInt()) ?: return markFailed(para, "sin respuesta de la placa")
        para.status = values[0]
        markOk(para)
    }

    override fun GetYPos(para: YPReplyPara) {
        val values = readHolding(para.reg.toInt(), para.readLen.toInt()) ?: return markFailed(para, "sin respuesta de la placa")
        if (values.size < 10) return markFailed(para, "respuesta incompleta de posiciones Y")
        para.y0 = values[0]
        para.y1 = values[1]
        para.y2 = values[2]
        para.y3 = values[3]
        para.y4 = values[4]
        para.y5 = values[5]
        para.y6 = values[6]
        para.y7 = values[7]
        para.y8 = values[8]
        para.y9 = values[9]
        markOk(para)
    }

    override fun SeYPos(para: SYPReplyPara) {
        val payload = para.extData ?: return markFailed(para, "sin puntos Y para enviar")
        if (!writeMultiple(para.reg.toInt(), para.readLen.toInt(), payload)) return markFailed(para, "sin respuesta de la placa")
        logger("Tabla Y enviada (${payload.size / 2} puntos)")
        markOk(para)
    }

    override fun ToY(para: TYReplyPara) {
        // En el SDK el valor esta en readLen: el constructor guarda la posicion ahi.
        val target = para.readLen.toInt()
        if (!writeSingle(para.reg.toInt(), target)) return markFailed(para, "sin respuesta de la placa")
        logger("ToY($target) enviado a la placa")
        markOk(para)
    }

    private fun readHolding(register: Int, count: Int): IntArray? {
        if (count <= 0) return null
        val frame = modbusReadRequest(register, count)
        val expected = 5 + 2 * count
        val response = exchange(frame, expected) ?: return null
        if (response.size < expected || response[1].toInt() != 0x03) return null
        return IntArray(count) { index ->
            val high = response[3 + 2 * index].toInt() and 0xFF
            val low = response[4 + 2 * index].toInt() and 0xFF
            (high shl 8) or low
        }
    }

    private fun writeSingle(register: Int, value: Int): Boolean {
        val frame = HexUtil.hexToBytes(
            CommandSet.appendCrc16Modbus(String.format("%02X06%04X%04X", BOARD_ADDRESS, register, value and 0xFFFF))
        )
        val response = exchange(frame, 8) ?: return false
        return response.size == 8 && response[1].toInt() == 0x06
    }

    private fun writeMultiple(register: Int, count: Int, payload: ByteArray): Boolean {
        val header = String.format("%02X10%04X%04X%02X", BOARD_ADDRESS, register, count, payload.size)
        val frame = HexUtil.hexToBytes(CommandSet.appendCrc16Modbus(header + HexUtil.bytesToHex(payload).replace(" ", "")))
        val response = exchange(frame, 8) ?: return false
        return response.size == 8 && response[1].toInt() == 0x10
    }

    private fun modbusReadRequest(register: Int, count: Int): ByteArray =
        HexUtil.hexToBytes(
            CommandSet.appendCrc16Modbus(String.format("%02X03%04X%04X", BOARD_ADDRESS, register, count))
        )

    /**
     * Request/response exchange. All socket work happens in the transport threads, so
     * waiting here (a bounded join) never performs network I/O on the caller thread.
     */
    private fun exchange(request: ByteArray, expectedLength: Int): ByteArray? {
        val result = AtomicReference<ByteArray?>(null)
        val worker = Thread({
            try {
                val out = output ?: return@Thread
                out.write(request)
                out.flush()
                val buffer = ByteArray(expectedLength)
                var read = 0
                while (read < expectedLength) {
                    val stream = input ?: return@Thread
                    val current = stream.read(buffer, read, expectedLength - read)
                    if (current < 0) return@Thread
                    read += current
                }
                result.set(buffer)
            } catch (_: Throwable) {
            }
        }, "CalibrationSimIo")
        worker.isDaemon = true
        worker.start()
        worker.join(EXCHANGE_TIMEOUT_MS)
        if (worker.isAlive) {
            logger("Sin respuesta de la placa simulada (timeout de ${EXCHANGE_TIMEOUT_MS} ms)")
            return null
        }
        return result.get()
    }

    private fun markOk(para: Any) {
        when (para) {
            is YSReplyPara -> para.resultCode = 0
            is DSReplyPara -> para.resultCode = 0
            is YPReplyPara -> para.resultCode = 0
            is SYPReplyPara -> para.resultCode = 0
        }
    }

    private fun markFailed(para: Any, message: String) {
        when (para) {
            is YSReplyPara -> {
                para.errorMsg = message
                para.resultCode = FAILED_RESULT_CODE
            }
            is DSReplyPara -> {
                para.errorMsg = message
                para.resultCode = FAILED_RESULT_CODE
            }
            is YPReplyPara -> {
                para.errorMsg = message
                para.resultCode = FAILED_RESULT_CODE
            }
            is SYPReplyPara -> {
                para.errorMsg = message
                para.resultCode = FAILED_RESULT_CODE
            }
        }
        logger(message)
    }

    private fun closeQuietly() {
        opened = false
        runCatching { output?.close() }
        runCatching { input?.close() }
        output = null
        input = null
    }

    private companion object {
        private const val BOARD_ADDRESS = 1
        private const val EXCHANGE_TIMEOUT_MS = 900L
        private const val FAILED_RESULT_CODE = -1
    }
}
