package com.vending.kiosk.integration.serial.runtime

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Byte stream behind SerialManager.
 *
 * Production talks to the native Android serial driver. The desktop simulation
 * build talks to the board simulator over TCP instead, because the vendor .so
 * files (ronyuanserial_port / serial_port) only ship for ARM devices and the
 * PC emulator is x86_64, so the native port can never be opened there.
 */
interface SerialTransport {
    fun open(path: String, baud: Int): SerialLink?
}

interface SerialLink {
    val input: InputStream
    val output: OutputStream
    fun close()
}

class NativeSerialTransport : SerialTransport {
    override fun open(path: String, baud: Int): SerialLink? {
        val port = android_serialport_api.SerialPort(File(path), baud, 0)
        return object : SerialLink {
            override val input: InputStream = port.getInputStream()
            override val output: OutputStream = port.getOutputStream()

            // The bundled native library does not expose closeNative(); closing
            // the streams releases the descriptor without aborting the SDK flow.
            override fun close() {
                runCatching { input.close() }
                runCatching { output.close() }
            }
        }
    }
}

class TcpSerialTransport(
    private val host: String,
    private val port: Int,
    private val connectTimeoutMs: Int = 5_000,
) : SerialTransport {

    override fun open(path: String, baud: Int): SerialLink? {
        val socket = Socket()
        val failure = AtomicReference<Throwable?>(null)
        val connected = CountDownLatch(1)

        // Android forbids network calls on the UI thread (NetworkOnMainThreadException)
        // and the app opens the port and sends its polling from exactly that thread, so
        // even the connect runs in its own thread and TcpSerialLink keeps every later
        // transfer off the caller too.
        Thread({
            try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            } catch (ex: Throwable) {
                failure.set(ex)
            } finally {
                connected.countDown()
            }
        }, "SerialSimConnect").apply { isDaemon = true }.start()

        if (!connected.await(connectTimeoutMs + 1_000L, TimeUnit.MILLISECONDS)) {
            runCatching { socket.close() }
            throw IOException("Timeout conectando al simulador de placa $host:$port")
        }
        failure.get()?.let { cause ->
            throw IOException("No se pudo conectar al simulador de placa $host:$port (${cause.message})", cause)
        }
        return TcpSerialLink(socket)
    }
}

/**
 * Socket-backed link with queue-decoupled streams: writes enqueue for a writer
 * thread and reads come from a reader thread, so the app can keep calling the
 * transport from any thread exactly as it does with the native port.
 */
private class TcpSerialLink(private val socket: Socket) : SerialLink {
    private val inbound = LinkedBlockingQueue<ByteArray>()
    private val outbound = LinkedBlockingQueue<ByteArray>()
    private val endOfStream = AtomicReference(false)
    private var current: ByteArray = EMPTY
    private var offset = 0

    private val reader = Thread({
        val buffer = ByteArray(1024)
        try {
            val stream = socket.getInputStream()
            while (!endOfStream.get()) {
                val read = stream.read(buffer)
                if (read <= 0) break
                inbound.put(buffer.copyOf(read))
            }
        } catch (_: Throwable) {
        } finally {
            endOfStream.set(true)
        }
    }, "SerialSimRx")

    private val writer = Thread({
        try {
            val stream = socket.getOutputStream()
            while (!endOfStream.get()) {
                // SerialManager interrumpe sus hilos al cerrar el puerto: se ignora la
                // interrupcion y se sale por endOfStream, para no reportar un error falso.
                val chunk = try {
                    outbound.poll(100, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    continue
                } ?: continue
                if (chunk.isEmpty()) break
                stream.write(chunk)
                stream.flush()
            }
        } catch (_: Throwable) {
        } finally {
            endOfStream.set(true)
        }
    }, "SerialSimTx")

    init {
        reader.isDaemon = true
        writer.isDaemon = true
        reader.start()
        writer.start()
    }

    override val input: InputStream = object : InputStream() {
        override fun read(): Int {
            if (!fill()) return -1
            return current[offset++].toInt() and 0xFF
        }

        override fun read(target: ByteArray, targetOffset: Int, length: Int): Int {
            if (length == 0) return 0
            if (!fill()) return -1
            val size = minOf(length, current.size - offset)
            System.arraycopy(current, offset, target, targetOffset, size)
            offset += size
            return size
        }

        private fun fill(): Boolean {
            while (offset >= current.size) {
                if (endOfStream.get() && inbound.isEmpty()) return false
                // SerialManager interrumpe su hilo lector al cerrar el puerto: la
                // interrupcion no es un error, se sigue esperando el cierre real.
                val next = try {
                    inbound.poll(100, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    continue
                } ?: continue
                current = next
                offset = 0
            }
            return true
        }
    }

    override val output: OutputStream = object : OutputStream() {
        override fun write(byte: Int) = write(byteArrayOf(byte.toByte()), 0, 1)

        override fun write(source: ByteArray, sourceOffset: Int, length: Int) {
            if (endOfStream.get()) throw IOException("Transmisor simulado cerrado")
            outbound.put(source.copyOfRange(sourceOffset, sourceOffset + length))
        }
    }

    override fun close() {
        endOfStream.set(true)
        runCatching { outbound.put(EMPTY) }
        runCatching { socket.close() }
        reader.interrupt()
        writer.interrupt()
    }

    private companion object {
        private val EMPTY = ByteArray(0)
    }
}

/**
 * Selects the transport for the whole app. "native" is the production value and
 * keeps /dev/ttyS1; "tcp" routes the very same Modbus traffic to a board
 * simulator running on the PC that hosts the emulator.
 */
object SerialTransportConfig {
    const val MODE_NATIVE = "native"
    const val MODE_SIMULATOR = "tcp"

    const val DEFAULT_ENDPOINT = "10.0.2.2:9100"

    @Volatile private var mode: String = MODE_NATIVE
    @Volatile private var endpoint: String = DEFAULT_ENDPOINT

    fun configure(mode: String?, endpoint: String?) {
        this.mode = mode?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: MODE_NATIVE
        endpoint?.trim()?.takeIf { it.isNotEmpty() }?.let { this.endpoint = it }
    }

    fun isSimulator(): Boolean = mode == MODE_SIMULATOR

    fun endpointHost(): String = endpoint.substringBefore(":").trim().ifBlank { "10.0.2.2" }

    fun endpointPort(): Int =
        endpoint.substringAfter(":", DEFAULT_ENDPOINT.substringAfter(":")).trim().toIntOrNull() ?: 9100

    fun current(): SerialTransport =
        if (isSimulator()) TcpSerialTransport(endpointHost(), endpointPort()) else NativeSerialTransport()

    fun describe(path: String, baud: Int): String =
        if (isSimulator()) "simulador de placa en $endpoint (en lugar de $path @ $baud)"
        else "puerto serie $path @ $baud"
}
