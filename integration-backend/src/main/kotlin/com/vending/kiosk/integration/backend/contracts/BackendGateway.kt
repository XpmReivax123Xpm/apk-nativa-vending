package com.vending.kiosk.integration.backend.contracts

/**
 * Contract for backend operations.
 *
 * NOTE: JSON schema, endpoints and field naming are pending backend alignment.
 */
interface BackendGateway {
    suspend fun loginOperator(username: String, password: String): BackendResult
    suspend fun getAssignedVendings(operatorId: String): BackendResult
    suspend fun getCatalog(vendingId: String): BackendResult
    suspend fun createTransaction(payload: Map<String, Any?>): BackendResult
    suspend fun createPaymentIntent(transactionId: String, method: String): BackendResult
    suspend fun getPaymentStatus(transactionId: String): BackendResult
    suspend fun validateReservation(code: String, vendingId: String): BackendResult
    suspend fun reportItemResult(transactionId: String, itemId: String, payload: Map<String, Any?>): BackendResult
    suspend fun reportTransactionResult(transactionId: String, payload: Map<String, Any?>): BackendResult
}

sealed interface BackendResult {
    data class Success(val payload: Map<String, Any?>) : BackendResult
    data class Error(val code: String, val message: String) : BackendResult
}

