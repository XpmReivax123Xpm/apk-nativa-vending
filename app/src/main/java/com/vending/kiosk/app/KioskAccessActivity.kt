package com.vending.kiosk.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.vending.kiosk.app.data.backend.MachineAuthGateway
import com.vending.kiosk.app.data.session.AuthSessionManager
import com.vending.kiosk.app.ui.access.AccessScreen
import com.vending.kiosk.app.ui.access.AccessViewModel

/**
 * Entrada al kiosko: la maquina se identifica por el EQUIPO donde corre este kiosko —que se carga una
 * sola vez— y el operador solo teclea el PIN.
 */
class KioskAccessActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val machineAuthGateway = MachineAuthGateway
        val authSessionManager = AuthSessionManager(this)
        val accessViewModel = ViewModelProvider(
            this,
            AccessViewModelFactory(machineAuthGateway, authSessionManager)
        )[AccessViewModel::class.java]

        setContent {
            val state by accessViewModel.uiState.collectAsState()

            LaunchedEffect(state.navigation) {
                state.navigation?.let { navigation ->
                    startActivity(
                        Intent(this@KioskAccessActivity, KioskCatalogActivity::class.java).apply {
                            putExtra(KioskCatalogActivity.EXTRA_MACHINE_ID, navigation.machineId)
                            putExtra(KioskCatalogActivity.EXTRA_MACHINE_CODE, navigation.machineCode)
                            putExtra(KioskCatalogActivity.EXTRA_MACHINE_LOCATION, navigation.machineLocation)
                        }
                    )
                    accessViewModel.consumeNavigation()
                    finish()
                }
            }

            AccessScreen(
                state = state,
                onDireccionMacChange = accessViewModel::onDireccionMacChange,
                onPinChange = accessViewModel::onPinChange,
                onEnterClick = accessViewModel::autenticar,
                onCambiarEquipoClick = accessViewModel::cambiarEquipo,
                onBackClick = {
                    startActivity(Intent(this@KioskAccessActivity, MainActivity::class.java))
                    finish()
                }
            )
        }
    }
}

private class AccessViewModelFactory(
    private val machineAuthGateway: MachineAuthGateway,
    private val authSessionManager: AuthSessionManager
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AccessViewModel::class.java)) {
            return AccessViewModel(machineAuthGateway, authSessionManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
