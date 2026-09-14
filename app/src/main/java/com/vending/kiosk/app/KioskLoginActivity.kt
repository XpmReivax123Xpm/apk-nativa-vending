package com.vending.kiosk.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.vending.kiosk.app.data.backend.LoginBackendGateway
import com.vending.kiosk.app.data.session.AuthSessionManager
import com.vending.kiosk.app.ui.login.LoginScreen
import com.vending.kiosk.app.ui.login.LoginViewModel

class KioskLoginActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val loginBackendGateway = LoginBackendGateway()
        val authSessionManager = AuthSessionManager(this)
        val loginViewModel = ViewModelProvider(
            this,
            LoginViewModelFactory(loginBackendGateway, authSessionManager)
        )[LoginViewModel::class.java]

        setContent {
            val state by loginViewModel.uiState.collectAsState()

            LaunchedEffect(state.loginSuccess) {
                if (state.loginSuccess) {
                    startActivity(Intent(this@KioskLoginActivity, KioskMachinesActivity::class.java))
                    finish()
                }
            }

            LoginScreen(
                state = state,
                onEmailChange = loginViewModel::updateEmail,
                onPasswordChange = loginViewModel::updatePassword,
                onLoginClick = loginViewModel::login
            )
        }
    }
}

private class LoginViewModelFactory(
    private val loginBackendGateway: LoginBackendGateway,
    private val authSessionManager: AuthSessionManager
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(LoginViewModel::class.java)) {
            return LoginViewModel(loginBackendGateway, authSessionManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
