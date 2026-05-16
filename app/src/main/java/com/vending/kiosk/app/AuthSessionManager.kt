package com.vending.kiosk.app

import android.content.Context

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

    fun saveMachineCredentials(machineId: Int, machineCode: String, machinePin: String) {
        prefs.edit()
            .putInt(KEY_MACHINE_ID, machineId)
            .putString(KEY_MACHINE_CODE, machineCode)
            .putString(KEY_MACHINE_PIN, machinePin)
            .apply()
    }

    fun getMachineCredentials(): MachineCredentials? {
        val machineCode = prefs.getString(KEY_MACHINE_CODE, "").orEmpty().trim()
        val machinePin = prefs.getString(KEY_MACHINE_PIN, "").orEmpty().trim()
        val machineId = prefs.getInt(KEY_MACHINE_ID, 0)
        if (machineCode.isBlank() || machinePin.isBlank()) return null
        return MachineCredentials(
            machineId = machineId,
            machineCode = machineCode,
            machinePin = machinePin
        )
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

    fun clearSession() {
        prefs.edit().clear().apply()
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
        private const val KEY_MACHINE_PIN = "machine_pin"
        private const val KEY_KIOSK_AUTO_RESUME_ENABLED = "kiosk_auto_resume_enabled"
        private const val KEY_LAST_MACHINE_LOCATION = "last_machine_location"
    }
}

data class MachineCredentials(
    val machineId: Int,
    val machineCode: String,
    val machinePin: String
)
