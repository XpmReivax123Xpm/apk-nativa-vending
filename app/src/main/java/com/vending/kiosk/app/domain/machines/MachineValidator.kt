package com.vending.kiosk.app.domain.machines

object MachineValidator {

    fun canSelect(status: Int): Boolean = status == 1

    fun isPinValid(pin: String): Boolean = pin.trim().isNotEmpty()
}
