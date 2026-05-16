package com.vending.kiosk.app.interaction

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CustomerInteractionMonitor(private val context: Context) {

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
        logsBuffer.clear()
        bitacoraBuffer.clear()
        sessionId = fileFormat.format(Date())
        active = true
        lastSavedArtifacts = null

        val safeMachine = machineCode.ifBlank { "SIN_MAQUINA" }
        val safePedido = if (pedidoId > 0) pedidoId.toString() else "SIN_PEDIDO"
        appendLog("Sesion iniciada | maquina=$safeMachine | pedido=$safePedido")
        appendLog("Metodo de pago: $paymentMethodLabel")
        if (selectedCellsSummary.isEmpty()) {
            appendLog("Celdas seleccionadas: (sin detalle)")
        } else {
            appendLog("Celdas seleccionadas:")
            selectedCellsSummary.forEach { appendLog(" - $it") }
        }
        appendBitacora("SESSION START | maquina=$safeMachine | pedido=$safePedido | metodo=$paymentMethodLabel")
        selectedCellsSummary.forEach { appendBitacora("ITEM | $it") }
    }

    fun isActive(): Boolean = active

    fun appendLog(message: String) {
        if (!active) return
        logsBuffer.append("${timestamp()} | $message\n")
    }

    fun appendBitacora(message: String) {
        if (!active) return
        bitacoraBuffer.append("${timestamp()} | $message\n")
    }

    fun appendBoth(message: String) {
        appendLog(message)
        appendBitacora(message)
    }

    fun getCurrentLogsText(): String = logsBuffer.toString()

    fun getCurrentBitacoraText(): String = bitacoraBuffer.toString()

    fun getLastLogsText(): String = lastLogsText

    fun getLastBitacoraText(): String = lastBitacoraText

    fun getLastSavedArtifacts(): SavedArtifacts? = lastSavedArtifacts

    fun finalizeAndSave(): SavedArtifacts? {
        if (!active) return lastSavedArtifacts
        appendBoth("Sesion finalizada")
        val saved = persistCurrentBuffers() ?: return null
        lastLogsText = logsBuffer.toString()
        lastBitacoraText = bitacoraBuffer.toString()
        lastSavedArtifacts = saved
        active = false
        return saved
    }

    fun forceCloseWithoutSave() {
        if (!active) return
        active = false
    }

    private fun persistCurrentBuffers(): SavedArtifacts? {
        return try {
            val baseDir = getBaseDir()
            if (!baseDir.exists()) baseDir.mkdirs()

            val sid = sessionId ?: fileFormat.format(Date())
            val logsFile = File(baseDir, "logs_$sid.txt")
            val bitacoraFile = File(baseDir, "bitacora_$sid.txt")

            logsFile.writeText(logsBuffer.toString())
            bitacoraFile.writeText(bitacoraBuffer.toString())
            SavedArtifacts(logsFile = logsFile, bitacoraFile = bitacoraFile)
        } catch (_: Exception) {
            null
        }
    }

    fun getBaseDirAbsolutePath(): String = getBaseDir().absolutePath

    private fun getBaseDir(): File {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        return File(root, "monitoreo de ciclo de vida de interaccion con el cliente")
    }

    private fun timestamp(): String = timeFormat.format(Date())
}

