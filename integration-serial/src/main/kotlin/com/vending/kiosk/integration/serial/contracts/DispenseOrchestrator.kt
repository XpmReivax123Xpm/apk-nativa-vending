package com.vending.kiosk.integration.serial.contracts

interface DispenseOrchestrator {
    suspend fun execute(plan: DispensePlan): DispenseExecutionResult
}

data class DispensePlan(
    val transactionId: String,
    val items: List<DispenseItem>,
)

data class DispenseItem(
    val itemId: String,
    val slotCode: String,
)

data class DispenseExecutionResult(
    val transactionId: String,
    val itemResults: List<ItemDispenseResult>,
    val globalResult: GlobalDispenseResult,
)

data class ItemDispenseResult(
    val itemId: String,
    val result: ItemResult,
    val detail: String? = null,
)

enum class ItemResult {
    DELIVERED,
    FAILED,
    UNKNOWN,
}

enum class GlobalDispenseResult {
    SUCCESS,
    PARTIAL_FAILURE,
    FAILURE,
    ABORTED,
}

