package android_serialport_api

import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

class SerialPort(device: File, baudrate: Int, flags: Int) {
    private val fd: FileDescriptor = open(device.absolutePath, baudrate, flags)
        ?: throw SecurityException("No se pudo abrir el puerto: ${device.absolutePath}")
    private val fileInputStream = FileInputStream(fd)
    private val fileOutputStream = FileOutputStream(fd)

    fun getInputStream(): InputStream = fileInputStream
    fun getOutputStream(): OutputStream = fileOutputStream
    fun close() = closeNative()

    private external fun closeNative()

    companion object {
        @JvmStatic
        private external fun open(path: String, baudrate: Int, flags: Int): FileDescriptor?

        init {
            System.loadLibrary("ronyuanserial_port")
        }
    }
}
