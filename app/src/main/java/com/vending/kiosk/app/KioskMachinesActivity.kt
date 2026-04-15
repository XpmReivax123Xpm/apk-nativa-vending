package com.vending.kiosk.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.vending.kiosk.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class KioskMachinesActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var contentContainer: LinearLayout
    private val authSessionManager by lazy { AuthSessionManager(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_kiosk_machines)

        tvStatus = findViewById(R.id.tvMachinesStatus)
        contentContainer = findViewById(R.id.llMachinesContainer)

        val authHeader = authSessionManager.getAuthorizationHeader()
        if (authHeader.isNullOrBlank()) {
            Toast.makeText(this, "Sesion expirada. Inicia sesion nuevamente.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        loadMachines(authHeader)
    }

    private fun loadMachines(authHeader: String) {
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = "Cargando maquinas..."
        contentContainer.removeAllViews()

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { fetchMachines(authHeader) }

            when (result) {
                is MachinesResult.Success -> {
                    tvStatus.visibility = View.GONE
                    if (result.maquinas.isEmpty()) {
                        tvStatus.visibility = View.VISIBLE
                        tvStatus.text = "Sin maquinas asignadas"
                    } else {
                        renderMachines(result.maquinas)
                    }
                }

                is MachinesResult.Error -> {
                    tvStatus.visibility = View.VISIBLE
                    tvStatus.text = "No se pudieron cargar maquinas"
                    Toast.makeText(this@KioskMachinesActivity, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun fetchMachines(authHeader: String): MachinesResult {
        val endpoint = "https://boxipagobackend.pagofacil.com.bo/api/maquinas"
        var connection: HttpURLConnection? = null

        return try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 12_000
                setRequestProperty("Authorization", authHeader)
                setRequestProperty("Accept", "application/json")
            }

            val statusCode = connection.responseCode
            val rawBody = runCatching {
                if (statusCode in 200..299) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                }
            }.getOrDefault("")

            if (rawBody.isBlank()) {
                return MachinesResult.Error("Respuesta vacia del backend (HTTP $statusCode)")
            }

            val json = JSONObject(rawBody)
            val error = json.optInt("error", -1)
            val status = json.optInt("status", 0)
            val message = json.optString("message", "Error consultando maquinas")

            if (statusCode !in 200..299 || error != 0 || status != 1) {
                return MachinesResult.Error(message)
            }

            val values = json.optJSONObject("values") ?: JSONObject()
            val maquinasJson = values.optJSONArray("maquinas")
            val maquinas = mutableListOf<MaquinaUi>()

            if (maquinasJson != null) {
                for (i in 0 until maquinasJson.length()) {
                    val item = maquinasJson.optJSONObject(i) ?: continue
                    maquinas += MaquinaUi(
                        id = item.optInt("tnMaquina", 0),
                        codigo = item.optString("tcCodigo", "SIN-CODIGO"),
                        locacion = item.optString("tcLocacion", "Sin locacion"),
                        estado = item.optInt("tnEstado", 0)
                    )
                }
            }

            MachinesResult.Success(maquinas)
        } catch (ex: Exception) {
            MachinesResult.Error("Fallo de conexion: ${ex.message ?: "sin detalle"}")
        } finally {
            connection?.disconnect()
        }
    }

    private fun renderMachines(maquinas: List<MaquinaUi>) {
        maquinas.forEach { maquina ->
            val card = layoutInflater.inflate(R.layout.item_machine_card, contentContainer, false)
            card.findViewById<TextView>(R.id.tvMachineCode).text = maquina.codigo
            card.findViewById<TextView>(R.id.tvMachineLocation).text = maquina.locacion
            card.findViewById<TextView>(R.id.tvMachineState).apply {
                if (maquina.estado == 1) {
                    text = "Activa"
                    setBackgroundResource(R.drawable.bg_status_connected)
                    setTextColor(resources.getColor(R.color.badge_connected_text, theme))
                } else {
                    text = "Inactiva"
                    setBackgroundResource(R.drawable.bg_status_disconnected)
                    setTextColor(resources.getColor(R.color.badge_disconnected_text, theme))
                }
            }
            card.setOnClickListener {
                if (maquina.estado != 1) {
                    Toast.makeText(this, "Maquina inactiva", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                showMachinePinDialog(maquina)
            }
            contentContainer.addView(card)
        }
    }

    private fun showMachinePinDialog(maquina: MaquinaUi) {
        val view = layoutInflater.inflate(R.layout.dialog_machine_login_pin, null)
        val tvTitle = view.findViewById<TextView>(R.id.tvMachinePinDialogTitle)
        val tvSubtitle = view.findViewById<TextView>(R.id.tvMachinePinDialogSubtitle)
        val etPin = view.findViewById<EditText>(R.id.etMachinePin)
        val tvError = view.findViewById<TextView>(R.id.tvMachinePinError)
        val progress = view.findViewById<ProgressBar>(R.id.progressMachinePin)
        val btnCancel = view.findViewById<Button>(R.id.btnMachinePinCancel)
        val btnConfirm = view.findViewById<Button>(R.id.btnMachinePinConfirm)

        tvTitle.text = "Ingresar PIN de maquina"
        tvSubtitle.text = "Maquina: ${maquina.codigo}"

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(false)
            .create()

        fun setLoading(loading: Boolean) {
            progress.visibility = if (loading) View.VISIBLE else View.GONE
            etPin.isEnabled = !loading
            btnCancel.isEnabled = !loading
            btnConfirm.isEnabled = !loading
        }

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnConfirm.setOnClickListener {
            val pin = etPin.text?.toString()?.trim().orEmpty()
            if (pin.isBlank()) {
                tvError.visibility = View.VISIBLE
                tvError.text = "Ingresa el PIN de la maquina"
                return@setOnClickListener
            }

            setLoading(true)
            tvError.visibility = View.GONE
            lifecycleScope.launch {
                val loginResult = withContext(Dispatchers.IO) {
                    MachineAuthGateway.loginMachine(
                        machineCode = maquina.codigo,
                        pin = pin
                    )
                }
                setLoading(false)

                when (loginResult) {
                    is MachineLoginResult.Success -> {
                        authSessionManager.saveSession(
                            accessToken = loginResult.accessToken,
                            tokenType = loginResult.tokenType,
                            expiresInMinutes = loginResult.expiresInMinutes
                        )
                        authSessionManager.saveMachineCredentials(
                            machineId = if (loginResult.machineId > 0) loginResult.machineId else maquina.id,
                            machineCode = loginResult.machineCode,
                            machinePin = pin
                        )
                        dialog.dismiss()

                        val intent = Intent(this@KioskMachinesActivity, KioskCatalogActivity::class.java).apply {
                            putExtra(KioskCatalogActivity.EXTRA_MACHINE_ID, maquina.id)
                            putExtra(KioskCatalogActivity.EXTRA_MACHINE_CODE, maquina.codigo)
                            putExtra(KioskCatalogActivity.EXTRA_MACHINE_LOCATION, maquina.locacion)
                        }
                        startActivity(intent)
                    }

                    is MachineLoginResult.Error -> {
                        tvError.visibility = View.VISIBLE
                        tvError.text = loginResult.message
                    }
                }
            }
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }
}

private data class MaquinaUi(
    val id: Int,
    val codigo: String,
    val locacion: String,
    val estado: Int
)

private sealed interface MachinesResult {
    data class Success(val maquinas: List<MaquinaUi>) : MachinesResult
    data class Error(val message: String) : MachinesResult
}
