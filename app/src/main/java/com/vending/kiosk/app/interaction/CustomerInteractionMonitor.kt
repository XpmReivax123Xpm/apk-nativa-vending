package com.vending.kiosk.app.interaction

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CustomerInteractionMonitor(private val context: Context) {

    private val lock = Any()

    private data class PersistenceSnapshot(
        val sessionId: String,
        val logsText: String,
        val bitacoraText: String
    )

    data class SavedArtifacts(
        val logsFile: File,
        val bitacoraFile: File
    )

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val fileFormat = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)

    private val logsBuffer = StringBuilder()
    private val bitacoraBuffer = StringBuilder()
    private var sessionId: String? = null
    private var active = false
    private var lastLogsText: String = ""
    private var lastBitacoraText: String = ""
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
            sessionId = fileFormat.format(Date())
            active = true
            lastSavedArtifacts = null

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
        }
    }

    fun isActive(): Boolean = synchronized(lock) { active }

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

    fun appendBoth(message: String) {
        synchronized(lock) {
            appendBothLocked(message)
        }
    }

    fun getCurrentLogsText(): String = synchronized(lock) { logsBuffer.toString() }

    fun getCurrentBitacoraText(): String = synchronized(lock) { bitacoraBuffer.toString() }

    fun getLastLogsText(): String = synchronized(lock) { lastLogsText }

    fun getLastBitacoraText(): String = synchronized(lock) { lastBitacoraText }

    fun finalizeAndSave(): SavedArtifacts? {
        val snapshot = synchronized(lock) {
            if (!active) {
                null
            } else {
                appendBothLocked("Sesion finalizada")
                PersistenceSnapshot(
                    sessionId = sessionId ?: fileFormat.format(Date()),
                    logsText = logsBuffer.toString(),
                    bitacoraText = bitacoraBuffer.toString()
                )
            }
        }

        if (snapshot == null) {
            return synchronized(lock) { lastSavedArtifacts }
        }

        val saved = persistCurrentBuffers(snapshot) ?: return null
        synchronized(lock) {
            lastLogsText = snapshot.logsText
            lastBitacoraText = snapshot.bitacoraText
            lastSavedArtifacts = saved
            active = false
        }
        return saved
    }

    private fun persistCurrentBuffers(snapshot: PersistenceSnapshot): SavedArtifacts? {
        return try {
            val baseDir = getBaseDir()
            if (!baseDir.exists()) baseDir.mkdirs()

            val logsFile = File(baseDir, "logs_${snapshot.sessionId}.txt")
            val bitacoraFile = File(baseDir, "bitacora_${snapshot.sessionId}.txt")

            logsFile.writeText(snapshot.logsText)
            bitacoraFile.writeText(snapshot.bitacoraText)
            SavedArtifacts(logsFile = logsFile, bitacoraFile = bitacoraFile)
        } catch (_: Exception) {
            null
        }
    }

    private fun getBaseDir(): File {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        return File(root, "monitoreo de ciclo de vida de interaccion con el cliente")
    }

    private fun appendLogLocked(message: String) {
        if (!active) return
        logsBuffer.append("${timestampLocked()} | $message\n")
    }

    private fun appendBitacoraLocked(message: String) {
        if (!active) return
        bitacoraBuffer.append("${timestampLocked()} | $message\n")
    }

    private fun appendBothLocked(message: String) {
        if (!active) return
        appendLogLocked(message)
        appendBitacoraLocked(message)
    }

    private fun timestampLocked(): String = timeFormat.format(Date())
}

