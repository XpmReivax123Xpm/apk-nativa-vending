package com.vending.kiosk.app.interaction

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * La bitacora de lo que pasa durante una dispensacion, para el operador del ERP.
 *
 * La unidad de trabajo es LA UNIDAD del pedido, no la compra: en un carrito de tres productos una
 * unidad puede salir y otra fallar, y lo que hay que poder leer es el texto de la que fallo —por eso
 * la bitacora se abre y se cierra por unidad—. La sesion sigue viva mientras dura el pedido y los
 * archivos viven en el storage externo del equipo.
 */
class CustomerInteractionMonitor(private val context: Context) {

    private val lock = Any()

    private data class PersistenceSnapshot(
        val sessionId: String,
        val logsText: String,
        val bitacoraText: String,
        val rawBitacoraText: String
    )

    data class SavedArtifacts(
        val logsFile: File,
        val bitacoraFile: File,
        val rawBitacoraFile: File?
    )

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val fileFormat = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)

    private val logsBuffer = StringBuilder()
    private val bitacoraBuffer = StringBuilder()
    private val rawBitacoraBuffer = StringBuilder()
    private var sessionId: String? = null
    private var active = false
    /** La linea del pedido de la unidad en curso: es a la que se cuelga su bitacora. */
    private var unitPedidoDetalle: Int = 0
    private var lastLogsText: String = ""
    private var lastBitacoraText: String = ""
    private var lastRawBitacoraText: String = ""
    private var lastSavedArtifacts: SavedArtifacts? = null

    fun startSession(
        machineCode: String,
        pedidoId: Int,
        paymentMethodLabel: String,
        selectedCellsSummary: List<String>
    ) {
        synchronized(lock) {
            logsBuffer.clear()
            bitacoraBuffer.clear()
            rawBitacoraBuffer.clear()
            sessionId = fileFormat.format(Date())
            active = true
            lastSavedArtifacts = null
            unitPedidoDetalle = 0

            val safeMachine = machineCode.ifBlank { "SIN_MAQUINA" }
            val safePedido = if (pedidoId > 0) pedidoId.toString() else "SIN_PEDIDO"
            appendLogLocked("Sesion iniciada | maquina=$safeMachine | pedido=$safePedido")
            appendLogLocked("Metodo de pago: $paymentMethodLabel")
            if (selectedCellsSummary.isEmpty()) {
                appendLogLocked("Celdas seleccionadas: (sin detalle)")
            } else {
                appendLogLocked("Celdas seleccionadas:")
                selectedCellsSummary.forEach { appendLogLocked(" - $it") }
            }
            appendBitacoraLocked("SESSION START | maquina=$safeMachine | pedido=$safePedido | metodo=$paymentMethodLabel")
            selectedCellsSummary.forEach { appendBitacoraLocked("ITEM | $it") }
            appendRawLocked("RAW SESSION START | maquina=$safeMachine | pedido=$safePedido")
        }
    }

    fun isActive(): Boolean = synchronized(lock) { active }

    /**
     * Abre la bitacora de UNA unidad del pedido: la maquina dispensa unidad por unidad y el texto
     * que interesa es el de la que fallo, no el de toda la compra.
     */
    fun startUnit(pedidoDetalle: Int) {
        synchronized(lock) {
            if (!active) return
            unitPedidoDetalle = pedidoDetalle
            val linea = if (pedidoDetalle > 0) pedidoDetalle.toString() else "SIN_LINEA"
            appendBitacoraLocked("UNIT START | linea=$linea")
            appendLogLocked("Unit start | linea=$linea")
        }
    }

    /**
     * Cierra la bitacora de la unidad en curso: la escribe en SU archivo y limpia los buffers para
     * la siguiente, sin cerrar la sesion del pedido.
     *
     * Devuelve `null` cuando no hay nada que escribir (no hay sesion o la unidad no dejo texto).
     */
    fun finalizeUnit(): File? {
        synchronized(lock) {
            if (!active) return null

            val linea = if (unitPedidoDetalle > 0) unitPedidoDetalle.toString() else "SIN_LINEA"
            appendBitacoraLocked("UNIT END | linea=$linea")

            val archivado = escribirBitacoraDeUnidad()

            bitacoraBuffer.clear()
            logsBuffer.clear()
            unitPedidoDetalle = 0

            return archivado
        }
    }

    fun appendLog(message: String) {
        synchronized(lock) {
            appendLogLocked(message)
        }
    }

    fun appendBitacora(message: String) {
        synchronized(lock) {
            appendBitacoraLocked(message)
        }
    }

    fun appendRaw(message: String) {
        synchronized(lock) {
            appendRawLocked(message)
        }
    }

    fun appendBoth(message: String) {
        synchronized(lock) {
            appendBothLocked(message)
        }
    }

    fun getCurrentLogsText(): String = synchronized(lock) { logsBuffer.toString() }

    fun getCurrentBitacoraText(): String = synchronized(lock) { bitacoraBuffer.toString() }

    fun getCurrentRawBitacoraText(): String = synchronized(lock) { rawBitacoraBuffer.toString() }

    fun getLastLogsText(): String = synchronized(lock) { lastLogsText }

    fun getLastBitacoraText(): String = synchronized(lock) { lastBitacoraText }

    fun getLastRawBitacoraText(): String = synchronized(lock) {
        if (lastRawBitacoraText.isNotBlank()) {
            lastRawBitacoraText
        } else {
            latestRawBitacoraFileLocked()?.runCatching { readText() }?.getOrDefault("").orEmpty()
        }
    }

    fun hasSavedRawBitacora(): Boolean = synchronized(lock) {
        latestRawBitacoraFileLocked() != null
    }

    fun finalizeAndSave(saveRawBitacora: Boolean = false): SavedArtifacts? {
        val snapshot = synchronized(lock) {
            if (!active) {
                null
            } else {
                appendBothLocked("Sesion finalizada")
                PersistenceSnapshot(
                    sessionId = sessionId ?: fileFormat.format(Date()),
                    logsText = logsBuffer.toString(),
                    bitacoraText = bitacoraBuffer.toString(),
                    rawBitacoraText = rawBitacoraBuffer.toString()
                )
            }
        }

        if (snapshot == null) {
            return synchronized(lock) { lastSavedArtifacts }
        }

        val saved = persistCurrentBuffers(snapshot, saveRawBitacora) ?: return null
        synchronized(lock) {
            lastLogsText = snapshot.logsText
            lastBitacoraText = snapshot.bitacoraText
            lastRawBitacoraText = snapshot.rawBitacoraText
            lastSavedArtifacts = saved
            active = false
            rawBitacoraBuffer.clear()
        }
        return saved
    }

    private fun persistCurrentBuffers(
        snapshot: PersistenceSnapshot,
        saveRawBitacora: Boolean
    ): SavedArtifacts? {
        return try {
            val baseDir = getBaseDir()
            if (!baseDir.exists()) baseDir.mkdirs()

            val logsFile = File(baseDir, "logs_${snapshot.sessionId}.txt")
            val bitacoraFile = File(baseDir, "bitacora_${snapshot.sessionId}.txt")

            logsFile.writeText(snapshot.logsText)
            bitacoraFile.writeText(snapshot.bitacoraText)
            val rawBitacoraFile = if (saveRawBitacora) {
                snapshot.rawBitacoraText
                    .takeIf { it.isNotBlank() }
                    ?.let {
                        File(baseDir, "bitacora cruda_${snapshot.sessionId}.txt").also { file ->
                            file.writeText(it)
                        }
                    }
            } else {
                null
            }
            SavedArtifacts(
                logsFile = logsFile,
                bitacoraFile = bitacoraFile,
                rawBitacoraFile = rawBitacoraFile
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun getBaseDir(): File {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        return File(root, "monitoreo de ciclo de vida de interaccion con el cliente")
    }

    /**
     * El archivo de la unidad en curso, dentro de la carpeta de la sesion: el nombre lleva la linea
     * del pedido para poder cruzarlo con lo que el backend recibio.
     */
    private fun escribirBitacoraDeUnidad(): File? {
        return try {
            val baseDir = getBaseDir()
            if (!baseDir.exists()) baseDir.mkdirs()

            val sufijo = if (unitPedidoDetalle > 0) "u$unitPedidoDetalle" else "sesion"
            val archivo = File(baseDir, "bitacora_${sessionId ?: fileFormat.format(Date())}_$sufijo.txt")
            archivo.writeText(bitacoraBuffer.toString())

            archivo
        } catch (_: Exception) {
            null
        }
    }

    private fun appendLogLocked(message: String) {
        if (!active) return
        logsBuffer.append("${timestampLocked()} | $message\n")
    }

    private fun appendBitacoraLocked(message: String) {
        if (!active) return
        bitacoraBuffer.append("${timestampLocked()} | $message\n")
    }

    private fun appendRawLocked(message: String) {
        if (!active) return
        rawBitacoraBuffer.append("${timestampLocked()} | $message\n")
    }

    private fun appendBothLocked(message: String) {
        if (!active) return
        appendLogLocked(message)
        appendBitacoraLocked(message)
    }

    private fun timestampLocked(): String = timeFormat.format(Date())

    private fun latestRawBitacoraFileLocked(): File? {
        val baseDir = getBaseDir()
        return baseDir.listFiles()
            ?.asSequence()
            ?.filter { file ->
                file.isFile &&
                    file.name.startsWith("bitacora cruda_") &&
                    file.name.endsWith(".txt")
            }
            ?.maxByOrNull(File::lastModified)
    }
}

