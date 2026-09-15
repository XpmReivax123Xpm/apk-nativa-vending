package com.vending.kiosk.app.ui.machines

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vending.kiosk.app.data.backend.Machine
import com.vending.kiosk.app.data.backend.MachineAuthGateway
import com.vending.kiosk.app.data.backend.MachineLoginResult
import com.vending.kiosk.app.data.backend.MachinesBackendGateway
import com.vending.kiosk.app.data.backend.MachinesBackendResult
import com.vending.kiosk.app.data.session.AuthSessionManager
import com.vending.kiosk.app.domain.machines.MachineValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MachinesUiState(
    val machines: List<Machine> = emptyList(),
    val isLoadingMachines: Boolean = false,
    val machinesError: String? = null,
    val selectedMachine: Machine? = null,
    val pin: String = "",
    val pinError: String? = null,
    val isAuthenticating: Boolean = false,
    val sessionExpired: Boolean = false,
    val navigation: MachineNavigation? = null
)

data class MachineNavigation(
    val machineId: Int,
    val machineCode: String,
    val machineLocation: String
)

class MachinesViewModel(
    private val machinesBackendGateway: MachinesBackendGateway,
    private val machineAuthGateway: MachineAuthGateway,
    private val authSessionManager: AuthSessionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(MachinesUiState())
    val uiState: StateFlow<MachinesUiState> = _uiState.asStateFlow()

    fun loadMachines() {
        val currentState = _uiState.value
        if (currentState.isLoadingMachines) return

        val authorization = authSessionManager.getAuthorizationHeader()
        if (authorization.isNullOrBlank()) {
            _uiState.value = currentState.copy(
                machinesError = "Sesion expirada. Inicia sesion nuevamente.",
                sessionExpired = true
            )
            return
        }

        _uiState.value = currentState.copy(
            isLoadingMachines = true,
            machinesError = null
        )

        viewModelScope.launch {
            try {
                when (
                    val result = withContext(Dispatchers.IO) {
                        machinesBackendGateway.fetchMachines(authorization)
                    }
                ) {
                    is MachinesBackendResult.Success -> {
                        _uiState.value = _uiState.value.copy(
                            machines = result.maquinas,
                            machinesError = null
                        )
                    }

                    is MachinesBackendResult.Error -> {
                        _uiState.value = _uiState.value.copy(machinesError = result.message)
                    }
                }
            } finally {
                _uiState.value = _uiState.value.copy(isLoadingMachines = false)
            }
        }
    }

    fun selectMachine(machine: Machine) {
        if (_uiState.value.isAuthenticating) return
        if (!MachineValidator.canSelect(machine.estado)) return

        _uiState.value = _uiState.value.copy(
            selectedMachine = machine,
            pin = "",
            pinError = null
        )
    }

    fun closePinDialog() {
        val currentState = _uiState.value
        if (currentState.isAuthenticating) return

        _uiState.value = currentState.copy(
            selectedMachine = null,
            pin = "",
            pinError = null
        )
    }

    fun onPinChange(pin: String) {
        _uiState.value = _uiState.value.copy(
            pin = pin.take(10),
            pinError = null
        )
    }

    fun authenticateMachine() {
        val currentState = _uiState.value
        val selectedMachine = currentState.selectedMachine ?: return
        if (currentState.isAuthenticating) return

        if (!MachineValidator.isPinValid(currentState.pin)) {
            _uiState.value = currentState.copy(pinError = "Ingresa el PIN de la maquina")
            return
        }

        val submittedPin = currentState.pin.trim()
        _uiState.value = currentState.copy(
            isAuthenticating = true,
            pinError = null
        )

        viewModelScope.launch {
            try {
                when (
                    val loginResult = withContext(Dispatchers.IO) {
                        machineAuthGateway.loginMachine(selectedMachine.codigo, submittedPin)
                    }
                ) {
                    is MachineLoginResult.Success -> {
                        val resolvedMachineId = if (loginResult.machineId > 0) {
                            loginResult.machineId
                        } else {
                            selectedMachine.id
                        }
                        val resolvedMachineCode = loginResult.machineCode.ifBlank {
                            selectedMachine.codigo
                        }

                        authSessionManager.saveSession(
                            accessToken = loginResult.accessToken,
                            tokenType = loginResult.tokenType,
                            expiresInMinutes = loginResult.expiresInMinutes
                        )
                        authSessionManager.saveMachineCredentials(
                            machineId = resolvedMachineId,
                            machineCode = resolvedMachineCode,
                            machinePin = submittedPin
                        )
                        authSessionManager.saveMachineLocation(selectedMachine.locacion)
                        authSessionManager.setKioskAutoResumeEnabled(true)

                        _uiState.value = _uiState.value.copy(
                            selectedMachine = null,
                            pin = "",
                            pinError = null,
                            navigation = MachineNavigation(
                                machineId = resolvedMachineId,
                                machineCode = resolvedMachineCode,
                                machineLocation = selectedMachine.locacion
                            )
                        )
                    }

                    is MachineLoginResult.Error -> {
                        _uiState.value = _uiState.value.copy(pinError = loginResult.message)
                    }
                }
            } finally {
                _uiState.value = _uiState.value.copy(isAuthenticating = false)
            }
        }
    }

    fun consumeNavigation() {
        _uiState.value = _uiState.value.copy(navigation = null)
    }

    fun consumeSessionExpired() {
        _uiState.value = _uiState.value.copy(sessionExpired = false)
    }
}
