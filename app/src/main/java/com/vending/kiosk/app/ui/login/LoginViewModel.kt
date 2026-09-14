package com.vending.kiosk.app.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vending.kiosk.app.data.backend.LoginBackendGateway
import com.vending.kiosk.app.data.backend.LoginBackendResult
import com.vending.kiosk.app.data.session.AuthSessionManager
import com.vending.kiosk.app.domain.login.LoginValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LoginUiState(
    val email: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val message: String? = null,
    val loginSuccess: Boolean = false
)

class LoginViewModel(
    private val loginBackendGateway: LoginBackendGateway,
    private val authSessionManager: AuthSessionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun updateEmail(email: String) {
        _uiState.value = _uiState.value.copy(email = email)
    }

    fun updatePassword(password: String) {
        _uiState.value = _uiState.value.copy(password = password)
    }

    fun login() {
        val currentState = _uiState.value
        if (currentState.isLoading) return

        if (!LoginValidator.isValid(currentState.email, currentState.password)) {
            _uiState.value = currentState.copy(message = "Completa correo y contraseña")
            return
        }

        _uiState.value = currentState.copy(
            isLoading = true,
            message = null,
            loginSuccess = false
        )

        viewModelScope.launch {
            try {
                when (
                    val result = withContext(Dispatchers.IO) {
                        loginBackendGateway.login(currentState.email.trim(), currentState.password)
                    }
                ) {
                    is LoginBackendResult.Success -> {
                        authSessionManager.saveSession(
                            result.accessToken,
                            result.tokenType,
                            result.expiresInMinutes
                        )
                        _uiState.value = _uiState.value.copy(loginSuccess = true)
                    }

                    is LoginBackendResult.Error -> {
                        _uiState.value = _uiState.value.copy(message = result.message)
                    }
                }
            } finally {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }
}
