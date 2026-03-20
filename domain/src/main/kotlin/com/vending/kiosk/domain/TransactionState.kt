package com.vending.kiosk.domain

enum class TransactionState {
    DRAFT,
    PAYMENT_PENDING,
    PAYMENT_CONFIRMED,
    DISPENSING,
    PARTIALLY_DISPENSED,
    COMPLETED,
    FAILED,
    COMPENSATION_PENDING,
    CLOSED,
}

