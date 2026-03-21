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

    companion object {
        private const val PREFS_NAME = "bp_auth_session"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_TOKEN_TYPE = "token_type"
        private const val KEY_EXPIRES_AT = "expires_at"
    }
}
