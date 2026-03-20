package com.vending.kiosk.integration.backend.contracts

/**
 * Contract for payment confirmation synchronization strategy.
 *
 * v1 default strategy: polling with backoff.
 */
interface PaymentConfirmationSync {
    suspend fun waitForPaymentConfirmation(transactionId: String): PaymentSyncResult
}

sealed interface PaymentSyncResult {
    data class Confirmed(val transactionId: String) : PaymentSyncResult
    data class NotConfirmed(val transactionId: String, val reason: String) : PaymentSyncResult
    data class Timeout(val transactionId: String) : PaymentSyncResult
}

