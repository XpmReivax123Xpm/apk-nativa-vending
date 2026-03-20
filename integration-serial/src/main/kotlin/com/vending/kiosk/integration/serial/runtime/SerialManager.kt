package com.vending.kiosk.integration.serial.runtime

import android_serialport_api.SerialPort
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class SerialManager {
    interface Listener {
        fun onRx(data: ByteArray, size: Int)
        fun onError(e: Exception)
        fun onStatus(msg: String)
    }

    private var serialPort: SerialPort? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    @Volatile private var running = false
    private var rxThread: Thread? = null

    fun isOpen(): Boolean = serialPort != null

    fun open(path: String, baud: Int, listener: Listener) {
        try {
            close()
            listener.onStatus("Abriendo puerto: $path @ $baud")
            serialPort = SerialPort(File(path), baud, 0)
            input = serialPort?.getInputStream()
            output = serialPort?.getOutputStream()
            running = true
            rxThread = Thread({
                val inStream = input ?: return@Thread
                val buffer = ByteArray(1024)
                while (running) {
                    try {
                        val n = inStream.read(buffer)
                        if (n > 0) listener.onRx(buffer.copyOf(n), n)
                    } catch (e: Exception) {
                        listener.onError(e)
                        return@Thread
                    }
                }
            }, "SerialRx")
            rxThread?.start()
            listener.onStatus("Puerto abierto")
        } catch (e: Exception) {
            listener.onError(e)
        }
    }

    fun sendHex(hex: String, listener: Listener) {
        sendBytes(HexUtil.hexToBytes(hex), listener)
    }

    fun sendBytes(data: ByteArray, listener: Listener) {
        try {
            val out = output ?: error("Puerto no abierto")
            out.write(data)
            out.flush()
            listener.onStatus("TX: ${HexUtil.bytesToHex(data)}")
        } catch (e: Exception) {
            listener.onError(e)
        }
    }

    fun close() {
        running = false
        try {
            rxThread?.interrupt()
        } catch (_: Exception) {
        }
        try {
            input?.close()
        } catch (_: Exception) {
        }
        try {
            output?.close()
        } catch (_: Exception) {
        }
        try {
            serialPort?.close()
        } catch (_: Exception) {
        }
        rxThread = null
        input = null
        output = null
        serialPort = null
    }
}
