package com.vending.kiosk.app.mqtt

import android.content.Context

/**
 * Credenciales del broker de avisos que el backend le entrega a ESTA maquina.
 *
 * No son el token de la sesion: el token vence en minutos y estas credenciales son de vida larga, con
 * su propio usuario en el broker (el id numerico de la maquina). El usuario es tambien el segmento del
 * tema, asi que una maquina no puede leer los avisos de otra.
 */
data class MqttCredentials(
    val host: String,
    val puerto: Int,
    val tls: Boolean,
    val prefijo: String,
    val usuario: String,
    val clave: String
) {
    val uri: String get() = "${if (tls) "ssl" else "tcp"}://$host:$puerto"

    val temaComando: String get() = "$prefijo/$usuario/comando"

    val temaEstado: String get() = "$prefijo/$usuario/estado"
}

/**
 * Guarda las credenciales del broker entre reinicios del kiosko.
 *
 * Se piden UNA vez (o cuando el broker las rechaza) y quedan aca: pedirlas en cada arranque rotaria la
 * contrasena sin necesidad y dejaria al broker sin poder validar la que ya estaba en uso.
 */
class MqttCredentialStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun cargar(): MqttCredentials? {
        val host = prefs.getString(KEY_HOST, "").orEmpty().trim()
        val usuario = prefs.getString(KEY_USUARIO, "").orEmpty().trim()
        val clave = prefs.getString(KEY_CLAVE, "").orEmpty().trim()

        if (host.isBlank() || usuario.isBlank() || clave.isBlank()) return null

        return MqttCredentials(
            host = host,
            puerto = prefs.getInt(KEY_PUERTO, 1883),
            tls = prefs.getBoolean(KEY_TLS, false),
            prefijo = prefs.getString(KEY_PREFIJO, "maq").orEmpty().ifBlank { "maq" },
            usuario = usuario,
            clave = clave
        )
    }

    fun guardar(credenciales: MqttCredentials) {
        prefs.edit()
            .putString(KEY_HOST, credenciales.host)
            .putInt(KEY_PUERTO, credenciales.puerto)
            .putBoolean(KEY_TLS, credenciales.tls)
            .putString(KEY_PREFIJO, credenciales.prefijo)
            .putString(KEY_USUARIO, credenciales.usuario)
            .putString(KEY_CLAVE, credenciales.clave)
            .apply()
    }

    /** El broker rechazo la credencial: se olvida para pedir una nueva. */
    fun limpiar() {
        prefs.edit().clear().apply()
    }

    private companion object {
        private const val PREFS_NAME = "bp_mqtt"
        private const val KEY_HOST = "host"
        private const val KEY_PUERTO = "puerto"
        private const val KEY_TLS = "tls"
        private const val KEY_PREFIJO = "prefijo"
        private const val KEY_USUARIO = "usuario"
        private const val KEY_CLAVE = "clave"
    }
}
