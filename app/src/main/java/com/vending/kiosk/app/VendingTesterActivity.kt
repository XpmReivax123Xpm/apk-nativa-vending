package com.vending.kiosk.app

import android.os.Bundle
import android.text.TextUtils
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.vending.kiosk.R
import com.vending.kiosk.integration.serial.runtime.CommandSet
import com.vending.kiosk.integration.serial.runtime.HexUtil
import com.vending.kiosk.integration.serial.runtime.SerialManager
import com.vending.kiosk.integration.serial.runtime.VendingFlowController
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VendingTesterActivity : AppCompatActivity() {
    private lateinit var etPort: EditText
    private lateinit var etBaud: EditText
    private lateinit var etCell: EditText
    private lateinit var tvPrompt: TextView
    private lateinit var tvLog: TextView
    private lateinit var svLog: ScrollView
    private lateinit var btnConnect: Button
    private lateinit var btnVendTest: Button
    private lateinit var btnContinue: Button
    private lateinit var btnResetLift: Button
    private lateinit var btnStop: Button

    private val serial = SerialManager()
    private lateinit var vendFlow: VendingFlowController

    private val queue = mutableListOf<Int>()
    private var queueIndex = 0
    private var waitingForContinue = false
    private var currentCell = -1
    private var pollLogCounter = 0
    private var pollIoLogCounter = 0
    private var promptBody = ""

    private var logFile: File? = null
    private var logWriter: FileWriter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vending_tester)
        bindViews()
        initLogFile()

        val flowUi = object : VendingFlowController.Ui {
            override fun onLog(msg: String) = appendLog(msg)
            override fun onNeedRetrieve(msg: String) {
                waitingForContinue = false
                setPromptBody(msg)
                setContinueEnabled(false)
            }

            override fun onDone() {
                waitingForContinue = true
                setPromptBody("Ciclo terminado (segundo click confirmado). Presione Continuar.")
                setContinueEnabled(true)
                appendLog("VEND terminado y listo para continuar (celda $currentCell)")
            }

            override fun onError(msg: String) {
                appendLog("ERROR: $msg")
                setPromptBody("Error en el proceso. Presiona STOP y vuelve a intentar.")
                waitingForContinue = false
                setContinueEnabled(false)
                setResetLiftEnabled(msg.contains("status 0000"))
                clearQueue()
            }

            override fun onStep(stepMsg: String) = Unit
        }

        val serialListener = object : SerialManager.Listener {
            override fun onRx(data: ByteArray, size: Int) {
                val rxHex = HexUtil.bytesToHex(data, size)
                appendLog("RX: $rxHex")
                vendFlow.onRx(data, size)
            }

            override fun onError(e: Exception) {
                appendLog("SERIAL ERROR: ${e.message}")
            }

            override fun onStatus(msg: String) {
                if (msg.startsWith("TX:")) {
                    if (msg.contains("01 03 00 03 00 01 74 0A")) {
                        pollLogCounter++
                        if (pollLogCounter % 10 != 0) return
                    }
                    if (msg.contains("01 03 00 0B 00 01 F5 C8") || msg.contains("01 03 00 0B 00 04 35 CB")) {
                        pollIoLogCounter++
                        if (pollIoLogCounter % 10 != 0) return
                    }
                }
                appendLog(msg)
            }
        }

        vendFlow = VendingFlowController(serial, serialListener, flowUi)
        appendLog("App lista.")
        appendLog("Log guardandose en: ${logFile?.absolutePath ?: "N/A"}")
        setPromptBody("Listo. Escribe pedido ej: 38,40,22 y presiona INICIAR PEDIDO.")
        setContinueEnabled(false)
        setResetLiftEnabled(false)

        btnConnect.setOnClickListener {
            val port = etPort.text.toString().trim()
            val baudStr = etBaud.text.toString().trim()
            if (TextUtils.isEmpty(port) || TextUtils.isEmpty(baudStr)) {
                appendLog("Escribe port y baud.")
                return@setOnClickListener
            }
            try {
                serial.open(port, baudStr.toInt(), serialListener)
            } catch (_: Exception) {
                appendLog("Baud invalido: $baudStr")
            }
        }

        btnVendTest.setOnClickListener {
            if (!serial.isOpen()) {
                appendLog("Abre el puerto primero.")
                return@setOnClickListener
            }
            if (vendFlow.isRunning() || vendFlow.isWaitingPickup()) {
                appendLog("Ya hay una operacion corriendo / esperando ciclo puerta.")
                return@setOnClickListener
            }
            val text = etCell.text.toString().trim()
            if (text.isEmpty()) {
                appendLog("Escribe un pedido (ej: 38,40,22).")
                return@setOnClickListener
            }
            try {
                val parsed = parseCellList(text)
                queue.clear()
                queue.addAll(parsed)
                queueIndex = 0
                waitingForContinue = false
                currentCell = -1
                pollLogCounter = 0
                pollIoLogCounter = 0
                setContinueEnabled(false)
                setResetLiftEnabled(false)
                setPromptBody("Pedido cargado: $queue. Ejecutando primer despacho...")
                startNextFromQueue()
            } catch (ex: Exception) {
                appendLog("Pedido invalido: ${ex.message}")
            }
        }

        btnContinue.setOnClickListener {
            if (!waitingForContinue) {
                appendLog("Aun no esta listo para continuar.")
                return@setOnClickListener
            }
            waitingForContinue = false
            setContinueEnabled(false)
            setPromptBody("Continuando con el siguiente pedido...")
            startNextFromQueue()
        }

        btnStop.setOnClickListener {
            pollLogCounter = 0
            pollIoLogCounter = 0
            waitingForContinue = false
            setContinueEnabled(false)
            setResetLiftEnabled(false)
            setPromptBody("STOP. Pedido cancelado.")
            clearQueue()
            vendFlow.stop()
        }

        btnResetLift.setOnClickListener {
            if (!serial.isOpen()) {
                appendLog("Abre el puerto primero.")
                return@setOnClickListener
            }
            waitingForContinue = false
            setContinueEnabled(false)
            clearQueue()
            vendFlow.stop()
            val resetHex = CommandSet.buildResetLift()
            appendLog("Intentando RESET LIFT para volver a estado base...")
            appendLog("TX RESET LIFT: $resetHex")
            serial.sendHex(resetHex, serialListener)
            setResetLiftEnabled(false)
            setPromptBody("ResetLift enviado. Verifica si la plataforma vuelve a base.")
        }
    }

    private fun bindViews() {
        etPort = findViewById(R.id.etPort)
        etBaud = findViewById(R.id.etBaud)
        etCell = findViewById(R.id.etCell)
        tvPrompt = findViewById(R.id.tvPrompt)
        tvLog = findViewById(R.id.tvLog)
        svLog = findViewById(R.id.svLog)
        btnConnect = findViewById(R.id.btnConnect)
        btnVendTest = findViewById(R.id.btnVendTest)
        btnContinue = findViewById(R.id.btnContinue)
        btnResetLift = findViewById(R.id.btnResetLift)
        btnStop = findViewById(R.id.btnStop)
    }

    private fun startNextFromQueue() {
        if (queueIndex >= queue.size) {
            setPromptBody("Pedido completado. No hay mas celdas.")
            appendLog("Pedido finalizado: $queue")
            clearQueue()
            setContinueEnabled(false)
            return
        }
        val cell = queue[queueIndex++]
        currentCell = cell
        setPromptBody("Ejecutando celda $cell... (esperando DONE y segundo click)")
        appendLog("Ejecutando celda $cell")
        vendFlow.start(cell)
    }

    private fun clearQueue() {
        queue.clear()
        queueIndex = 0
        currentCell = -1
    }

    private fun parseCellList(input: String): List<Int> {
        val out = mutableListOf<Int>()
        input.split(",").forEach { part ->
            val s = part.trim()
            if (s.isNotEmpty()) {
                val cell = s.toIntOrNull() ?: throw IllegalArgumentException("No es numero: '$s'")
                require(cell in 10..68) { "Celda fuera de rango (10..68): $cell" }
                out += cell
            }
        }
        require(out.isNotEmpty()) { "Lista vacia" }
        return out
    }

    private fun setPromptBody(msg: String) {
        promptBody = msg
        runOnUiThread { tvPrompt.text = promptBody }
    }

    private fun setContinueEnabled(enabled: Boolean) {
        runOnUiThread { btnContinue.isEnabled = enabled }
    }

    private fun setResetLiftEnabled(enabled: Boolean) {
        runOnUiThread { btnResetLift.isEnabled = enabled }
    }

    private fun appendLog(msg: String) {
        val m = msg.ifEmpty { "null" }
        runOnUiThread {
            tvLog.append("$m\n")
            svLog.post { svLog.fullScroll(ScrollView.FOCUS_DOWN) }
        }
        writeLogLine(m)
    }

    private fun initLogFile() {
        try {
            var dir = getExternalFilesDir("vend_logs")
            if (dir == null) dir = File(filesDir, "vend_logs")
            if (!dir.exists()) dir.mkdirs()
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            logFile = File(dir, "vend_log_$ts.txt")
            logWriter = FileWriter(logFile, true)
            writeLogLine("=== LOG START $ts ===")
        } catch (_: Exception) {
            logFile = null
            logWriter = null
        }
    }

    private fun writeLogLine(line: String) {
        try {
            val writer = logWriter ?: return
            val t = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            writer.write("$t | $line\n")
            writer.flush()
        } catch (_: Exception) {
        }
    }

    private fun closeLogFile() {
        try {
            logWriter?.let {
                writeLogLine("=== LOG END ===")
                it.close()
            }
        } catch (_: Exception) {
        }
        logWriter = null
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            vendFlow.stop()
        } catch (_: Exception) {
        }
        try {
            serial.close()
        } catch (_: Exception) {
        }
        closeLogFile()
    }
}
