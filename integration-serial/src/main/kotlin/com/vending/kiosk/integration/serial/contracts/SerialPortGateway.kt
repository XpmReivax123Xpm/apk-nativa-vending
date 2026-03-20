package com.vending.kiosk.integration.serial.contracts

interface SerialPortGateway {
    suspend fun open(config: SerialConfig): SerialConnectionResult
    suspend fun close()
    suspend fun send(payload: ByteArray): SerialIoResult
    suspend fun receive(timeoutMs: Long): SerialIoResult
    fun connectionState(): SerialConnectionState
}

data class SerialConfig(
    val port: String,
    val baudRate: Int,
)

enum class SerialConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR,
}

sealed interface SerialConnectionResult {
    data object Opened : SerialConnectionResult
    data class Failed(val reason: String) : SerialConnectionResult
}

sealed interface SerialIoResult {
    data class Data(val payload: ByteArray) : SerialIoResult
    data class Timeout(val timeoutMs: Long) : SerialIoResult
    data class Error(val reason: String) : SerialIoResult
}

