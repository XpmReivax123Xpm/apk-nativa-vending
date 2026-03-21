package com.vending.kiosk.app

import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.vending.kiosk.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class KioskLoginActivity : AppCompatActivity() {

    private lateinit var etCorreo: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnLogin: Button
    private lateinit var progressLogin: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var logoContainer: View
    private lateinit var loginContainer: View

    private val authSessionManager by lazy { AuthSessionManager(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_kiosk_login)

        etCorreo = findViewById(R.id.etCorreo)
        etPassword = findViewById(R.id.etPassword)
        btnLogin = findViewById(R.id.btnLogin)
        progressLogin = findViewById(R.id.progressLogin)
        tvStatus = findViewById(R.id.tvLoginStatus)
        logoContainer = findViewById(R.id.logoContainer)
        loginContainer = findViewById(R.id.loginContainer)
        tvStatus.visibility = android.view.View.GONE

        playIntroAnimation()

        btnLogin.setOnClickListener {
            attemptLogin()
        }
    }

    private fun playIntroAnimation() {
        logoContainer.alpha = 0f
        logoContainer.translationY = 80f

        loginContainer.alpha = 0f
        loginContainer.translationY = 56f

        logoContainer.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(520L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                loginContainer.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(420L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            .start()
    }

    private fun attemptLogin() {
        val correo = etCorreo.text?.toString()?.trim().orEmpty()
        val password = etPassword.text?.toString().orEmpty()

        if (correo.isBlank() || password.isBlank()) {
            Toast.makeText(this, "Completa correo y password", Toast.LENGTH_SHORT).show()
            return
        }

        setLoading(true)
        tvStatus.visibility = android.view.View.VISIBLE
        tvStatus.text = "Ingresando..."

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                doLogin(correo, password)
            }

            setLoading(false)

            when (result) {
                is LoginResult.Success -> {
                    authSessionManager.saveSession(
                        accessToken = result.accessToken,
                        tokenType = result.tokenType,
                        expiresInMinutes = result.expiresInMinutes
                    )
                    tvStatus.text = "Conexión exitosa"
                }

                is LoginResult.Error -> {
                    tvStatus.text = "Error login: ${result.message}"
                    Toast.makeText(this@KioskLoginActivity, result.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun doLogin(correo: String, password: String): LoginResult {
        val endpoint = "http://192.168.0.9:8001/api/login"
        var connection: HttpURLConnection? = null

        return try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 12_000
                readTimeout = 12_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }

            val payload = JSONObject().apply {
                put("tcCorreo", correo)
                put("tcPassword", password)
            }.toString()

            connection.outputStream.use { out ->
                out.write(payload.toByteArray(Charsets.UTF_8))
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
                return LoginResult.Error("Respuesta vacia del backend (HTTP $statusCode)")
            }

            val json = JSONObject(rawBody)
            val error = json.optInt("error", -1)
            val status = json.optInt("status", 0)
            val message = json.optString("message", "No se pudo autenticar")

            if (statusCode in 200..299 && error == 0 && status == 1) {
                val values = json.optJSONObject("values")
                    ?: return LoginResult.Error("Respuesta sin 'values'")

                val accessToken = values.optString("accessToken", "")
                val tokenType = values.optString("tokenType", "bearer")
                val expiresInMinutes = values.optLong("expiresInMinutes", 0L)

                if (accessToken.isBlank() || expiresInMinutes <= 0L) {
                    return LoginResult.Error("Token o expiracion invalidos")
                }

                LoginResult.Success(accessToken, tokenType, expiresInMinutes)
            } else {
                LoginResult.Error(message)
            }
        } catch (ex: Exception) {
            LoginResult.Error("Fallo de conexion: ${ex.message ?: "sin detalle"}")
        } finally {
            connection?.disconnect()
        }
    }

    private fun setLoading(loading: Boolean) {
        btnLogin.isEnabled = !loading
        etCorreo.isEnabled = !loading
        etPassword.isEnabled = !loading
        progressLogin.visibility = if (loading) android.view.View.VISIBLE else android.view.View.GONE
    }
}

private sealed interface LoginResult {
    data class Success(
        val accessToken: String,
        val tokenType: String,
        val expiresInMinutes: Long
    ) : LoginResult

    data class Error(val message: String) : LoginResult
}
