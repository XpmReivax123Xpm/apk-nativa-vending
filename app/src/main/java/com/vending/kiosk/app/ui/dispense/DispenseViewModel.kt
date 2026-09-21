package com.vending.kiosk.app.ui.dispense

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface DispenseSurface {
    data object Hidden : DispenseSurface
    data object Dispensing : DispenseSurface
    data object Retrieve : DispenseSurface
    data object IoTimeout : DispenseSurface
    data object ProlongedWait : DispenseSurface
    data object PlatformStuck : DispenseSurface
    data object PlatformRecovering : DispenseSurface
    data object Success : DispenseSurface
    data object Error : DispenseSurface
}

sealed interface ManualRetryUiState {
    data object Available : ManualRetryUiState
    data object InProgress : ManualRetryUiState
    data object AlreadyInProgress : ManualRetryUiState
    data object UnableToStart : ManualRetryUiState
}

data class DispenseProductSummary(
    val name: String,
    val quantity: Int = 1,
    val imageSource: String? = null
)

data class DispenseErrorUi(
    val message: String,
    val code: String? = null,
    val isProductCrushed: Boolean = false,
    val deliveredProducts: List<DispenseProductSummary> = emptyList(),
    val pendingProducts: List<DispenseProductSummary> = emptyList(),
    val reviewProducts: List<DispenseProductSummary> = emptyList()
)

data class DispenseUiState(
    val surface: DispenseSurface = DispenseSurface.Hidden,
    val currentIndex: Int = 0,
    val totalItems: Int = 0,
    val productName: String = "",
    val productImageSource: String? = null,
    val statusText: String = "",
    val retrieveTitle: String = "",
    val retrieveMessage: String = "",
    val modalMessage: String = "",
    val manualRetryState: ManualRetryUiState = ManualRetryUiState.Available,
    val error: DispenseErrorUi? = null,
    val successSecondsLeft: Int? = null
)

class DispenseViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(DispenseUiState())
    val uiState: StateFlow<DispenseUiState> = _uiState.asStateFlow()

    fun showDispensing(
        currentIndex: Int,
        totalItems: Int,
        productName: String,
        productImageSource: String? = null,
        statusText: String = "Espere un momento, por favor..."
    ) {
        _uiState.value = DispenseUiState(
            surface = DispenseSurface.Dispensing,
            currentIndex = currentIndex,
            totalItems = totalItems,
            productName = productName,
            productImageSource = productImageSource,
            statusText = statusText
        )
    }

    fun updateStatus(statusText: String) {
        _uiState.value = _uiState.value.copy(statusText = statusText)
    }

    fun showRetrieve(
        title: String,
        message: String,
        productName: String = "",
        productImageSource: String? = null
    ) {
        _uiState.value = DispenseUiState(
            surface = DispenseSurface.Retrieve,
            productName = productName,
            productImageSource = productImageSource,
            retrieveTitle = title,
            retrieveMessage = message
        )
    }

    fun showIoTimeout(message: String) {
        _uiState.value = DispenseUiState(
            surface = DispenseSurface.IoTimeout,
            modalMessage = message
        )
    }

    fun showProlongedWait(
        message: String,
        manualRetryState: ManualRetryUiState = ManualRetryUiState.Available
    ) {
        _uiState.value = DispenseUiState(
            surface = DispenseSurface.ProlongedWait,
            modalMessage = message,
            manualRetryState = manualRetryState
        )
    }

    fun setManualRetryInProgress() = setManualRetryState(ManualRetryUiState.InProgress)

    fun setManualRetryAlreadyInProgress() = setManualRetryState(ManualRetryUiState.AlreadyInProgress)

    fun setManualRetryUnableToStart() = setManualRetryState(ManualRetryUiState.UnableToStart)

    fun showPlatformStuck(message: String) {
        _uiState.value = DispenseUiState(
            surface = DispenseSurface.PlatformStuck,
            modalMessage = message
        )
    }

    fun showPlatformRecovering(message: String) {
        _uiState.value = DispenseUiState(
            surface = DispenseSurface.PlatformRecovering,
            modalMessage = message
        )
    }

    fun showSuccess(message: String, secondsLeft: Int? = null) {
        _uiState.value = DispenseUiState(
            surface = DispenseSurface.Success,
            modalMessage = message,
            successSecondsLeft = secondsLeft
        )
    }

    fun updateSuccessCountdown(secondsLeft: Int?) {
        _uiState.value = _uiState.value.copy(successSecondsLeft = secondsLeft)
    }

    fun showError(error: DispenseErrorUi) {
        _uiState.value = DispenseUiState(surface = DispenseSurface.Error, error = error)
    }

    fun hide() {
        _uiState.value = DispenseUiState()
    }

    private fun setManualRetryState(manualRetryState: ManualRetryUiState) {
        _uiState.value = _uiState.value.copy(manualRetryState = manualRetryState)
    }
}
