package com.vending.kiosk.app.ui.access

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vending.kiosk.app.data.backend.MachineAuthGateway
import com.vending.kiosk.app.data.backend.MachineLoginResult
import com.vending.kiosk.app.data.session.AuthSessionManager
import com.vending.kiosk.app.device.DeviceIdentifierFailure
import com.vending.kiosk.app.device.DeviceIdentifierResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Acceso al kiosko: SOLO el PIN de la maquina.
 *
 * El identificador del equipo no se pide ni se teclea nunca: lo entrega el aparato (ver
 * `DeviceIdentity`) y sin el el kiosko no inicia sesion. La maquina se identifica por su APARATO:
 * quien conoce el codigo impreso en el frente no puede entrar al planograma de otra maquina.
 */
data class AccessUiState(
    /** Identificador del equipo que hay que anotar: se muestra UNA vez, antes del PIN. */
    val identificadorDelEquipo: String? = null,
    /** Por que el equipo no entrego su identificador: sin el, el kiosko no inicia. */
    val identificadorNoDisponible: DeviceIdentifierFailure? = null,
    val machineCode: String = "",
    val machineLocation: String = "",
    val pin: String = "",
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
    private val authSessionManager: AuthSessionManager,
    /** Identificador del equipo, leido al abrir la pantalla (ver `DeviceIdentity`). */
    deviceIdentifier: DeviceIdentifierResult
) : ViewModel() {

    private val _uiState = MutableStateFlow(AccessUiState())
    val uiState: StateFlow<AccessUiState> = _uiState.asStateFlow()

    /** MAC vigente del equipo; null mientras no se haya podido obtener. */
    private var macDelEquipo: String? = null

    init {
        aplicarIdentificador(deviceIdentifier)

        _uiState.value = _uiState.value.copy(
            machineCode = authSessionManager.getEquipo()?.machineCode.orEmpty(),
            machineLocation = authSessionManager.getMachineLocation().orEmpty()
        )
    }

    /** Se volvio a leer el identificador del equipo (boton «Reintentar» de la pantalla de falla). */
    fun actualizarIdentificador(deviceIdentifier: DeviceIdentifierResult) {
        aplicarIdentificador(deviceIdentifier)
    }

    private fun aplicarIdentificador(deviceIdentifier: DeviceIdentifierResult) {
        macDelEquipo = (deviceIdentifier as? DeviceIdentifierResult.Available)?.macAddress

        _uiState.value = _uiState.value.copy(
            identificadorNoDisponible =
                (deviceIdentifier as? DeviceIdentifierResult.Unavailable)?.failure,
            // El identificador se muestra UNA sola vez, para que se anote y se registre en el ERP.
            identificadorDelEquipo = macDelEquipo
                ?.takeIf { !authSessionManager.isDeviceIdentifierRevealed() }
        )
    }

    fun onPinChange(value: String) {
        _uiState.value = _uiState.value.copy(pin = value.take(10), error = null)
    }

    /** El operador anoto el identificador: no se vuelve a mostrar y el acceso sigue con el PIN. */
    fun confirmarIdentificadorAnotado() {
        authSessionManager.markDeviceIdentifierRevealed()
        _uiState.value = _uiState.value.copy(identificadorDelEquipo = null)
    }

    fun autenticar() {
        val state = _uiState.value
        if (state.isAuthenticating) return

        val direccionMac = macDelEquipo.orEmpty()

        // Sin identificador del aparato no hay autenticacion posible: es a proposito, no hay ingreso
        // manual de identificador en el kiosko.
        if (direccionMac.isBlank()) {
            _uiState.value = state.copy(
                error = "No se obtuvo el identificador del equipo: el kiosko no puede ingresar"
            )
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
