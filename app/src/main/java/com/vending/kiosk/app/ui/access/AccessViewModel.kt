package com.vending.kiosk.app.ui.access

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vending.kiosk.app.data.backend.MachineAuthGateway
import com.vending.kiosk.app.data.backend.MachineLoginResult
import com.vending.kiosk.app.data.session.AuthSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Acceso al kiosko: el equipo donde corre —que se carga UNA sola vez— y el PIN de la maquina.
 *
 * La maquina se identifica por su APARATO: quien conoce el codigo impreso en el frente no puede
 * entrar con el al planograma de otra maquina.
 */
data class AccessUiState(
    val direccionMac: String = "",
    val pin: String = "",
    val needsEquipo: Boolean = true,
    val machineCode: String = "",
    val machineLocation: String = "",
    val isAuthenticating: Boolean = false,
    val error: String? = null,
    val navigation: AccessNavigation? = null
)

data class AccessNavigation(
    val machineId: Int,
    val machineCode: String,
    val machineLocation: String
)

class AccessViewModel(
    private val machineAuthGateway: MachineAuthGateway,
    private val authSessionManager: AuthSessionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(AccessUiState())
    val uiState: StateFlow<AccessUiState> = _uiState.asStateFlow()

    init {
        val equipo = authSessionManager.getEquipo()

        _uiState.value = _uiState.value.copy(
            direccionMac = equipo?.direccionMac.orEmpty(),
            needsEquipo = equipo == null,
            machineCode = equipo?.machineCode.orEmpty(),
            machineLocation = authSessionManager.getMachineLocation().orEmpty()
        )
    }

    fun onDireccionMacChange(value: String) {
        _uiState.value = _uiState.value.copy(direccionMac = value.trim(), error = null)
    }

    fun onPinChange(value: String) {
        _uiState.value = _uiState.value.copy(pin = value.take(10), error = null)
    }

    /** Cambio de tablet: el kiosko olvida su equipo y lo vuelve a pedir. */
    fun cambiarEquipo() {
        authSessionManager.forgetEquipo()
        _uiState.value = _uiState.value.copy(
            needsEquipo = true,
            direccionMac = "",
            pin = "",
            error = null
        )
    }

    fun autenticar() {
        val state = _uiState.value
        if (state.isAuthenticating) return

        val direccionMac = state.direccionMac.trim()

        if (direccionMac.isBlank()) {
            _uiState.value = state.copy(error = "Ingresa el identificador del equipo")
            return
        }
        if (state.pin.isBlank()) {
            _uiState.value = state.copy(error = "Ingresa el PIN del kiosko")
            return
        }

        _uiState.value = state.copy(isAuthenticating = true, error = null)

        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    machineAuthGateway.loginMachine(direccionMac, state.pin)
                }

                when (result) {
                    is MachineLoginResult.Success -> {
                        // El equipo se guarda SOLO cuando el login salio bien: asi el kiosko no queda
                        // configurado con un identificador que el backend rechaza.
                        authSessionManager.saveEquipo(direccionMac, state.pin)
                        authSessionManager.saveSession(
                            accessToken = result.accessToken,
                            tokenType = result.tokenType,
                            expiresInMinutes = result.expiresInMinutes
                        )
                        authSessionManager.saveMaquina(result.machineId, result.machineCode)
                        authSessionManager.saveMachineLocation(result.machineLocation)
                        authSessionManager.setKioskAutoResumeEnabled(true)

                        _uiState.value = _uiState.value.copy(
                            pin = "",
                            error = null,
                            navigation = AccessNavigation(
                                machineId = result.machineId,
                                machineCode = result.machineCode,
                                machineLocation = result.machineLocation
                            )
                        )
                    }

                    is MachineLoginResult.Error -> {
                        _uiState.value = _uiState.value.copy(error = result.message)
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
}
