package com.vending.kiosk.device.contracts

interface KioskController {
    suspend fun enableKiosk(vendingId: String): KioskResult
    suspend fun disableKioskWithPin(pin: String): KioskResult
    fun isKioskActive(): Boolean
}

sealed interface KioskResult {
    data object Success : KioskResult
    data class Failure(val reason: String) : KioskResult
}

