package com.vending.kiosk.integration.serial.contracts

/**
 * Adapter between domain intent and real vending controller protocol.
 *
 * NOTE: Real command set is pending hardware specification.
 */
interface MachineProtocolAdapter {
    fun encode(intent: MachineIntent): ByteArray
    fun decode(response: ByteArray): MachineResponse
}

sealed interface MachineIntent {
    data class Connect(val machineId: String) : MachineIntent
    data class DispenseItem(val slotCode: String) : MachineIntent
    data object Heartbeat : MachineIntent
    data object RecoveryProbe : MachineIntent
}

sealed interface MachineResponse {
    data class Ack(val payload: Map<String, String> = emptyMap()) : MachineResponse
    data class Nack(val reason: String) : MachineResponse
    data class Unknown(val raw: ByteArray) : MachineResponse
}

