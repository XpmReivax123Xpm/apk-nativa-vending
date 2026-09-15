package com.vending.kiosk.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.vending.kiosk.app.data.backend.MachineAuthGateway
import com.vending.kiosk.app.data.backend.MachinesBackendGateway
import com.vending.kiosk.app.data.session.AuthSessionManager
import com.vending.kiosk.app.ui.machines.MachinesScreen
import com.vending.kiosk.app.ui.machines.MachinesViewModel

class KioskMachinesActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val machinesBackendGateway = MachinesBackendGateway
        val machineAuthGateway = MachineAuthGateway
        val authSessionManager = AuthSessionManager(this)
        val machinesViewModel = ViewModelProvider(
            this,
            MachinesViewModelFactory(
                machinesBackendGateway,
                machineAuthGateway,
                authSessionManager
            )
        )[MachinesViewModel::class.java]

        setContent {
            val state by machinesViewModel.uiState.collectAsState()

            LaunchedEffect(machinesViewModel) {
                machinesViewModel.loadMachines()
            }

            LaunchedEffect(state.sessionExpired) {
                if (state.sessionExpired) {
                    Toast.makeText(
                        this@KioskMachinesActivity,
                        "Sesion expirada. Inicia sesion nuevamente.",
                        Toast.LENGTH_SHORT
                    ).show()
                    machinesViewModel.consumeSessionExpired()
                    finish()
                }
            }

            LaunchedEffect(state.navigation) {
                state.navigation?.let { navigation ->
                    startActivity(
                        Intent(this@KioskMachinesActivity, KioskCatalogActivity::class.java).apply {
                            putExtra(KioskCatalogActivity.EXTRA_MACHINE_ID, navigation.machineId)
                            putExtra(KioskCatalogActivity.EXTRA_MACHINE_CODE, navigation.machineCode)
                            putExtra(KioskCatalogActivity.EXTRA_MACHINE_LOCATION, navigation.machineLocation)
                        }
                    )
                    machinesViewModel.consumeNavigation()
                }
            }

            MachinesScreen(
                state = state,
                onMachineClick = machinesViewModel::selectMachine,
                onPinChange = machinesViewModel::onPinChange,
                onAuthenticateClick = machinesViewModel::authenticateMachine,
                onDismissPin = machinesViewModel::closePinDialog,
                onBackClick = {
                    startActivity(Intent(this@KioskMachinesActivity, MainActivity::class.java))
                    finish()
                }
            )
        }
    }
}

private class MachinesViewModelFactory(
    private val machinesBackendGateway: MachinesBackendGateway,
    private val machineAuthGateway: MachineAuthGateway,
    private val authSessionManager: AuthSessionManager
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MachinesViewModel::class.java)) {
            return MachinesViewModel(
                machinesBackendGateway,
                machineAuthGateway,
                authSessionManager
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
