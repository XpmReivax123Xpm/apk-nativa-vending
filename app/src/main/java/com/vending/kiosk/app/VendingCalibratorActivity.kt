package com.vending.kiosk.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import cc.uling.usdk.USDK
import cc.uling.usdk.board.UBoard
import cc.uling.usdk.board.wz.para.DSReplyPara
import cc.uling.usdk.board.wz.para.SYPReplyPara
import cc.uling.usdk.board.wz.para.TYReplyPara
import cc.uling.usdk.board.wz.para.YPReplyPara
import cc.uling.usdk.board.wz.para.YSReplyPara
import cc.uling.usdk.constants.CodeUtil
import cc.uling.usdk.para.BaseClsPara
import com.vending.kiosk.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

class VendingCalibratorActivity : Activity() {
    private var board: UBoard? = null

    private lateinit var tvConnection: TextView
    private lateinit var tvColumns: TextView
    private lateinit var tvResult: TextView
    private lateinit var tvLogs: TextView
    private lateinit var tvTestPoints: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var etRowsInUse: EditText
    private lateinit var etTestCm: EditText

    private val rowCmInputs = arrayOfNulls<EditText>(10)
    private val rowPointLabels = arrayOfNulls<TextView>(10)
    private val rowContainers = arrayOfNulls<LinearLayout>(10)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vending_calibrator)

        USDK.getInstance().init(application)
        bindViews()
        bindActions()
        applyRowVisibility()
        updateAllRowPointLabels()
        updateTestPointsLabel()
        renderConnectionState(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectMachine()
    }

    private fun bindViews() {
        tvConnection = findViewById(R.id.tv_connection)
        tvColumns = findViewById(R.id.tv_columns)
        tvResult = findViewById(R.id.tv_result)
        tvLogs = findViewById(R.id.tv_logs)
        tvTestPoints = findViewById(R.id.tv_test_points)
        btnConnect = findViewById(R.id.btn_connect)
        btnDisconnect = findViewById(R.id.btn_disconnect)
        etRowsInUse = findViewById(R.id.et_rows_in_use)
        etTestCm = findViewById(R.id.et_test_cm)

        rowContainers[0] = findViewById(R.id.row_f1)
        rowContainers[1] = findViewById(R.id.row_f2)
        rowContainers[2] = findViewById(R.id.row_f3)
        rowContainers[3] = findViewById(R.id.row_f4)
        rowContainers[4] = findViewById(R.id.row_f5)
        rowContainers[5] = findViewById(R.id.row_f6)
        rowContainers[6] = findViewById(R.id.row_f7)
        rowContainers[7] = findViewById(R.id.row_f8)
        rowContainers[8] = findViewById(R.id.row_f9)
        rowContainers[9] = findViewById(R.id.row_f10)

        rowCmInputs[0] = findViewById(R.id.et_f1_cm)
        rowCmInputs[1] = findViewById(R.id.et_f2_cm)
        rowCmInputs[2] = findViewById(R.id.et_f3_cm)
        rowCmInputs[3] = findViewById(R.id.et_f4_cm)
        rowCmInputs[4] = findViewById(R.id.et_f5_cm)
        rowCmInputs[5] = findViewById(R.id.et_f6_cm)
        rowCmInputs[6] = findViewById(R.id.et_f7_cm)
        rowCmInputs[7] = findViewById(R.id.et_f8_cm)
        rowCmInputs[8] = findViewById(R.id.et_f9_cm)
        rowCmInputs[9] = findViewById(R.id.et_f10_cm)

        rowPointLabels[0] = findViewById(R.id.tv_f1_points)
        rowPointLabels[1] = findViewById(R.id.tv_f2_points)
        rowPointLabels[2] = findViewById(R.id.tv_f3_points)
        rowPointLabels[3] = findViewById(R.id.tv_f4_points)
        rowPointLabels[4] = findViewById(R.id.tv_f5_points)
        rowPointLabels[5] = findViewById(R.id.tv_f6_points)
        rowPointLabels[6] = findViewById(R.id.tv_f7_points)
        rowPointLabels[7] = findViewById(R.id.tv_f8_points)
        rowPointLabels[8] = findViewById(R.id.tv_f9_points)
        rowPointLabels[9] = findViewById(R.id.tv_f10_points)

        tvColumns.text = "Columnas fijas de maquina: 9"
    }

    private fun bindActions() {
        btnConnect.setOnClickListener { connectMachine() }
        btnDisconnect.setOnClickListener { disconnectMachine() }
        findViewById<View>(R.id.btn_platform_status).setOnClickListener { readPlatformStatus() }
        findViewById<View>(R.id.btn_load_saved).setOnClickListener { loadSavedRows() }
        findViewById<View>(R.id.btn_save_y).setOnClickListener { saveRows() }
        findViewById<View>(R.id.btn_test_y).setOnClickListener { testPosition() }
        findViewById<View>(R.id.btn_clear_log).setOnClickListener {
            tvLogs.text = ""
            tvResult.visibility = View.GONE
            tvResult.text = "Historial limpio."
        }

        etRowsInUse.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) applyRowVisibility()
        }

        for (i in rowCmInputs.indices) {
            rowCmInputs[i]?.addTextChangedListener(object : SimpleTextWatcher() {
                override fun afterTextChanged(s: Editable?) {
                    updateRowPointLabel(i)
                }
            })
        }
        etTestCm.addTextChangedListener(object : SimpleTextWatcher() {
            override fun afterTextChanged(s: Editable?) {
                updateTestPointsLabel()
            }
        })
    }

    private fun connectMachine() {
        board = USDK.getInstance().create(INTERNAL_PORT)
        val ret = board?.EF_OpenDev(INTERNAL_PORT, INTERNAL_BAUD) ?: -1
        if (ret == 0) {
            renderConnectionState(true)
            addInfo("Conexion abierta correctamente.")
        } else {
            renderConnectionState(false)
            addWarn("No se pudo abrir conexion. Revisa cableado y energia.")
        }
    }

    private fun disconnectMachine() {
        val b = board
        if (b != null && b.EF_Opened()) {
            b.EF_CloseDev()
            renderConnectionState(false)
            addInfo("Conexion cerrada.")
        } else {
            renderConnectionState(false)
        }
    }

    private fun readPlatformStatus() {
        if (!checkOpen()) return
        val para = YSReplyPara(INTERNAL_ADDR)
        board?.GetYStatus(para)
        if (para.isOK) {
            val status = translateBoardText(CodeUtil.getXYStatusMsg(para.runStatus))
            val fault = translateBoardText(CodeUtil.getFaultMsg(para.faultCode))
            addInfo("Estado plataforma: $status. Error: $fault.")
        } else {
            addErrorSimple("No se pudo consultar estado de plataforma.", para)
        }
    }

    private fun readSensorsStatus() {
        if (!checkOpen()) return
        val para = DSReplyPara(INTERNAL_ADDR)
        board?.GetDropStatus(para)
        if (para.isOK) {
            val text = if (para.status == 1) "Sensores de caida normales." else "Sensor desconectado u obstruido."
            addInfo("Estado sensores: $text")
        } else {
            addErrorSimple("No se pudo consultar estado de sensores.", para)
        }
    }

    private fun loadSavedRows() {
        if (!checkOpen()) return
        val para = YPReplyPara(INTERNAL_ADDR)
        board?.GetYPos(para)
        if (!para.isOK) {
            addErrorSimple("No se pudieron cargar datos guardados.", para)
            return
        }
        val points = intArrayOf(para.y0, para.y1, para.y2, para.y3, para.y4, para.y5, para.y6, para.y7, para.y8, para.y9)
        for (i in rowCmInputs.indices) {
            val cm = pointsToCm(points[i])
            rowCmInputs[i]?.setText(formatCm(cm))
        }
        updateAllRowPointLabels()
        addInfo("Datos guardados cargados. Ahora ves cm y puntos calculados.")
    }

    private fun saveRows() {
        if (!checkOpen()) return
        val rowsInUse = parseRowsInUse()
        applyRowVisibility()
        val pointsToSave = IntArray(10)
        val outOfRangeRows = mutableListOf<String>()

        for (i in 0 until rowsInUse) {
            val raw = rowCmInputs[i]?.text?.toString()?.trim().orEmpty()
            if (raw.isEmpty()) {
                addWarn("Completa la Fila ${i + 1} antes de guardar.")
                return
            }
            val cm = parseFloatOrDefault(raw, 0f)
            val points = max(0, cmToPoints(cm))
            pointsToSave[i] = points
            if (!isWithinRecommendedCm(cm)) {
                outOfRangeRows += "Fila ${i + 1} (${formatCm(cm)} cm)"
            }
        }

        val para = SYPReplyPara(
            INTERNAL_ADDR,
            pointsToSave[0], pointsToSave[1], pointsToSave[2], pointsToSave[3], pointsToSave[4],
            pointsToSave[5], pointsToSave[6], pointsToSave[7], pointsToSave[8], pointsToSave[9]
        )
        board?.SeYPos(para)
        if (!para.isOK) {
            addErrorSimple("No se pudieron guardar los cambios.", para)
            return
        }

        updateAllRowPointLabels()
        if (outOfRangeRows.isEmpty()) {
            addInfo("Cambios guardados. La maquina recibio puntos Y calculados desde cm.")
        } else {
            addInfo("Cambios guardados. Revisa filas: ${outOfRangeRows.joinToString(", ")}.")
        }
    }

    private fun testPosition() {
        if (!checkOpen()) return
        val raw = etTestCm.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) {
            addWarn("Escribe un valor en cm para la prueba.")
            return
        }
        val cm = parseFloatOrDefault(raw, 0f)
        val points = max(0, cmToPoints(cm))
        val para = TYReplyPara(INTERNAL_ADDR, points.toShort())
        board?.ToY(para)
        if (!para.isOK) {
            addErrorSimple("No se pudo ejecutar la prueba de posicion.", para)
            return
        }

        addInfo("Prueba enviada: ${formatCm(cm)} cm -> $points puntos Y.")
    }

    private fun applyRowVisibility() {
        val rowsInUse = parseRowsInUse()
        for (i in rowContainers.indices) {
            rowContainers[i]?.visibility = if (i < rowsInUse) View.VISIBLE else View.GONE
        }
    }

    private fun updateAllRowPointLabels() {
        for (i in rowPointLabels.indices) {
            updateRowPointLabel(i)
        }
    }

    private fun updateRowPointLabel(index: Int) {
        val raw = rowCmInputs[index]?.text?.toString().orEmpty()
        val cm = parseFloatOrDefault(raw, 0f)
        val points = max(0, cmToPoints(cm))
        val okRange = isWithinRecommendedCm(cm)
        val rangeText = if (okRange) "" else " | fuera de rango (35-120 cm)"
        rowPointLabels[index]?.text = "Puntos Y calculados: $points$rangeText"
        rowPointLabels[index]?.setTextColor(if (okRange) COLOR_OK else COLOR_WARN)
    }

    private fun updateTestPointsLabel() {
        val raw = etTestCm.text?.toString().orEmpty()
        val cm = parseFloatOrDefault(raw, 0f)
        tvTestPoints.text = "Probando ${formatCm(cm)} cm"
        tvTestPoints.setTextColor(COLOR_OK)
    }

    private fun parseRowsInUse(): Int {
        val rows = parseIntOrDefault(etRowsInUse.text?.toString().orEmpty(), 4).coerceIn(1, 10)
        etRowsInUse.setText(rows.toString())
        etRowsInUse.setSelection(etRowsInUse.text.length)
        return rows
    }

    private fun renderConnectionState(connected: Boolean) {
        if (connected) {
            tvConnection.text = "Conectado"
            tvConnection.setBackgroundResource(R.drawable.bg_status_connected)
            tvConnection.setTextColor(ContextCompat.getColor(this, R.color.badge_connected_text))
            btnConnect.isEnabled = false
            btnConnect.alpha = 0.45f
            btnDisconnect.isEnabled = true
            btnDisconnect.alpha = 1.0f
            btnDisconnect.setBackgroundResource(R.drawable.bg_btn_stop)
        } else {
            tvConnection.text = "Desconectado"
            tvConnection.setBackgroundResource(R.drawable.bg_status_disconnected)
            tvConnection.setTextColor(ContextCompat.getColor(this, R.color.badge_disconnected_text))
            btnConnect.isEnabled = true
            btnConnect.alpha = 1.0f
            btnDisconnect.isEnabled = false
            btnDisconnect.alpha = 0.45f
            btnDisconnect.setBackgroundResource(R.drawable.bg_btn_close)
        }
    }

    private fun checkOpen(): Boolean {
        val b = board
        if (b != null && b.EF_Opened()) return true
        addWarn("Primero pulsa Conectar.")
        return false
    }

    private fun translateBoardText(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return "Sin dato"
        val replacements = linkedMapOf(
            "空闲" to "En reposo",
            "运行" to "En movimiento",
            "停止" to "Detenido",
            "无故障" to "Sin falla",
            "无效管道编号" to "Numero de carril/canal invalido",
            "无效货道编号" to "Numero de carril/canal invalido",
            "电机没有转动" to "Motor sin giro",
            "无电流通过" to "sin corriente",
            "插头未插好" to "conector mal insertado",
            "断线" to "cable cortado",
            "堵转" to "motor bloqueado/atascado",
            "通讯" to "falla de comunicacion",
            "通信" to "falla de comunicacion"
        )

        var translated = text
        replacements.forEach { (zh, es) ->
            translated = translated.replace(zh, es)
        }

        // Normaliza separadores/ruido despues de reemplazos
        translated = translated
            .replace("，", ", ")
            .replace("。", ".")
            .replace("（", "(")
            .replace("）", ")")
            .replace("  ", " ")
            .trim()

        // Si todavia contiene CJK, no dejamos chino en UI/log.
        return if (translated.any { it.code in 0x4E00..0x9FFF }) {
            "Falla reportada por placa (sin traduccion exacta)."
        } else {
            translated
        }
    }

    private fun cmToPoints(cm: Float): Int = (POINTS_PER_CM * cm + POINTS_INTERCEPT).roundToInt()
    private fun pointsToCm(pointsY: Int): Float = (pointsY - POINTS_INTERCEPT) / POINTS_PER_CM
    private fun isWithinRecommendedCm(cm: Float): Boolean = cm in CM_MIN_RECOMMENDED..CM_MAX_RECOMMENDED

    private fun parseIntOrDefault(raw: String, fallback: Int): Int {
        if (TextUtils.isEmpty(raw)) return fallback
        return raw.trim().toIntOrNull() ?: fallback
    }

    private fun parseFloatOrDefault(raw: String, fallback: Float): Float {
        if (TextUtils.isEmpty(raw)) return fallback
        return raw.trim().replace(',', '.').toFloatOrNull() ?: fallback
    }

    private fun formatCm(cm: Float): String = String.format(Locale.US, "%.2f", cm)

    private fun addInfo(msg: String) {
        tvResult.visibility = View.VISIBLE
        tvResult.text = msg
        appendLog("INFO", msg)
    }

    private fun addWarn(msg: String) {
        tvResult.visibility = View.VISIBLE
        tvResult.text = msg
        appendLog("AVISO", msg)
    }

    private fun addErrorSimple(msg: String, para: BaseClsPara) {
        tvResult.visibility = View.VISIBLE
        tvResult.text = msg
        appendLog("ERROR", "$msg (codigo ${para.resultCode})")
    }

    private fun appendLog(level: String, msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        tvLogs.append("[$ts] $level: $msg\n")
    }

    private abstract class SimpleTextWatcher : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
    }

    companion object {
        private const val INTERNAL_ADDR = 1
        private const val INTERNAL_BAUD = 9600
        private const val INTERNAL_PORT = "/dev/ttyS1"

        private const val CM_MIN_RECOMMENDED = 35.0f
        private const val CM_MAX_RECOMMENDED = 120.0f
        private const val POINTS_PER_CM = 53.67f
        private const val POINTS_INTERCEPT = -43.74f

        private val COLOR_OK = Color.parseColor("#DDEBFF")
        private val COLOR_WARN = Color.parseColor("#FFCA76")
    }
}
