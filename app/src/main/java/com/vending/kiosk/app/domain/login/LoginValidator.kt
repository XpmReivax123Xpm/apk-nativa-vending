package com.vending.kiosk.app.domain.login

object LoginValidator {
    fun isValid(email: String, password: String): Boolean {
        return email.trim().isNotEmpty() && password.isNotEmpty()
    }
}
