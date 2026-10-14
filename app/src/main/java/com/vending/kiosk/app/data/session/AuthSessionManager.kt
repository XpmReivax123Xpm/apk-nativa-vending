package com.vending.kiosk.app.data.session

import android.content.Context

/**
 * Lo que el kiosko guarda entre reinicios: la sesion de la maquina, su EQUIPO y los datos de la
 * maquina que devolvio el login.
 *
 * El identificador del equipo se carga UNA vez —cuando se instala el kiosko o se cambia la tablet— y
 * despues el kiosko solo pide el PIN: la maquina se identifica por su APARATO, nunca por el codigo que
 * esta impreso en su frente (quien se lo supiera entraria al planograma de otra maquina).
 */
class AuthSessionManager(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveSession(accessToken: String, tokenType: String, expiresInMinutes: Long) {
        val expiresAtMillis = System.currentTimeMillis() + (expiresInMinutes * 60_000L)
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_TOKEN_TYPE, tokenType)
            .putLong(KEY_EXPIRES_AT, expiresAtMillis)
            .apply()
    }

    /** El identificador del APARATO y el PIN con el que el kiosko entra: se cargan una sola vez. */
    fun saveEquipo(direccionMac: String, pin: String) {
        prefs.edit()
            .putString(KEY_DIRECCION_MAC, direccionMac.trim())
            .putString(KEY_MACHINE_PIN, pin)
            .apply()
    }

    fun getEquipo(): Equipo? {
        val direccionMac = prefs.getString(KEY_DIRECCION_MAC, "").orEmpty().trim()
        val pin = prefs.getString(KEY_MACHINE_PIN, "").orEmpty().trim()
        if (direccionMac.isBlank() || pin.isBlank()) return null

        return Equipo(
            direccionMac = direccionMac,
            pin = pin,
            machineId = prefs.getInt(KEY_MACHINE_ID, 0),
            machineCode = prefs.getString(KEY_MACHINE_CODE, "").orEmpty().trim()
        )
    }

    /** Olvida el equipo: el kiosko vuelve a pedir su identificador (cambio de tablet). */
    fun forgetEquipo() {
        prefs.edit()
            .remove(KEY_DIRECCION_MAC)
            .remove(KEY_MACHINE_PIN)
            .apply()
    }

    /** Los datos de la MAQUINA que devolvio el login: es el rotulo del kiosko, no una credencial. */
    fun saveMaquina(machineId: Int, machineCode: String) {
        prefs.edit()
            .putInt(KEY_MACHINE_ID, machineId)
            .putString(KEY_MACHINE_CODE, machineCode)
            .apply()
    }

    fun getAuthorizationHeader(): String? {
        val token = prefs.getString(KEY_ACCESS_TOKEN, null) ?: return null
        val type = prefs.getString(KEY_TOKEN_TYPE, "bearer").orEmpty()
        if (isSessionExpired()) return null

        val normalized = if (type.equals("bearer", ignoreCase = true)) "Bearer" else type
        return "$normalized $token"
    }

    fun isSessionExpired(): Boolean {
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        if (expiresAt <= 0L) return true
        return System.currentTimeMillis() >= expiresAt
    }

    fun setKioskAutoResumeEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_KIOSK_AUTO_RESUME_ENABLED, enabled)
            .apply()
    }

    fun isKioskAutoResumeEnabled(): Boolean {
        return prefs.getBoolean(KEY_KIOSK_AUTO_RESUME_ENABLED, false)
    }

    fun saveMachineLocation(location: String?) {
        prefs.edit()
            .putString(KEY_LAST_MACHINE_LOCATION, location.orEmpty().trim())
            .apply()
    }

    fun getMachineLocation(): String? {
        return prefs.getString(KEY_LAST_MACHINE_LOCATION, "")
            ?.trim()
            ?.ifBlank { "" }
    }

    fun disableKioskAutoResume() {
        prefs.edit()
            .putBoolean(KEY_KIOSK_AUTO_RESUME_ENABLED, false)
            .remove(KEY_LAST_MACHINE_LOCATION)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "bp_auth_session"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_TOKEN_TYPE = "token_type"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_MACHINE_ID = "machine_id"
        private const val KEY_MACHINE_CODE = "machine_code"
        private const val KEY_DIRECCION_MAC = "direccion_mac"
        private const val KEY_MACHINE_PIN = "machine_pin"
        private const val KEY_KIOSK_AUTO_RESUME_ENABLED = "kiosk_auto_resume_enabled"
        private const val KEY_LAST_MACHINE_LOCATION = "last_machine_location"
    }
}

/**
 * El equipo en el que corre este kiosko: su identificador —lo que el backend guarda como direccion de
 * la maquina— y el PIN que el operador teclea, mas los datos de la maquina que devolvio el login.
 */
data class Equipo(
    val direccionMac: String,
    val pin: String,
    val machineId: Int,
    val machineCode: String
)
