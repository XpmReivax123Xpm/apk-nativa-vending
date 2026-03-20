package com.vending.kiosk.domain

enum class MachineState {
    DISCONNECTED,
    CONNECTING,
    READY,
    BUSY,
    RECOVERING,
    BLOCKED,
    FAULTED,
}

