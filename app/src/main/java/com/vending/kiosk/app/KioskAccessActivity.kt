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
import com.vending.kiosk.app.device.DeviceIdentifierResult
import com.vending.kiosk.app.device.DeviceIdentity
import com.vending.kiosk.app.ui.access.AccessScreen
import com.vending.kiosk.app.ui.access.AccessViewModel
import com.vending.kiosk.app.ui.access.DeviceIdentifierScreen
import com.vending.kiosk.app.ui.access.DeviceIdentifierUnavailableScreen

/**
 * Entrada al kiosko: la maquina se identifica por el EQUIPO donde corre este kiosko —el identificador
 * lo entrega la propia tablet cuando esta app la administra, y el operador solo teclea el PIN—.
 */
class KioskAccessActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val machineAuthGateway = MachineAuthGateway
        val authSessionManager = AuthSessionManager(this)
        // El identificador se lee aca y no en el ViewModel porque hace falta un Context. Viene del
        // propio equipo (ver `DeviceIdentity`), no se teclea.
        val accessViewModel = ViewModelProvider(
            this,
            AccessViewModelFactory(
                machineAuthGateway,
                authSessionManager,
                DeviceIdentity.read(this)
            )
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

            val identificadorDelEquipo = state.identificadorDelEquipo
            val identificadorNoDisponible = state.identificadorNoDisponible

            when {
                identificadorDelEquipo != null -> {
                    // Primera vez que el equipo entrega su MAC: se muestra para que la anoten y despues el
                    // acceso sigue con el PIN. No se vuelve a mostrar (ver DeviceIdentifierScreen).
                    DeviceIdentifierScreen(
                        macAddress = identificadorDelEquipo,
                        onContinuarClick = accessViewModel::confirmarIdentificadorAnotado
                    )
                }

                // Sin identificador no hay acceso: no existe el ingreso manual, asi que la unica salida es
                // que el equipo lo entregue (o volver al menu, que tampoco entra al kiosko).
                identificadorNoDisponible != null -> {
                    DeviceIdentifierUnavailableScreen(
                        failure = identificadorNoDisponible,
                        onReintentarClick = {
                            accessViewModel.actualizarIdentificador(DeviceIdentity.read(this@KioskAccessActivity))
                        },
                        onVolverAlMenuClick = {
                            startActivity(Intent(this@KioskAccessActivity, MainActivity::class.java))
                            finish()
                        }
                    )
                }

                else -> {
                    AccessScreen(
                        state = state,
                        onPinChange = accessViewModel::onPinChange,
                        onEnterClick = accessViewModel::autenticar,
                        onBackClick = {
                            startActivity(Intent(this@KioskAccessActivity, MainActivity::class.java))
                            finish()
                        }
                    )
                }
            }
        }
    }
}

private class AccessViewModelFactory(
    private val machineAuthGateway: MachineAuthGateway,
    private val authSessionManager: AuthSessionManager,
    private val deviceIdentifier: DeviceIdentifierResult
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AccessViewModel::class.java)) {
            return AccessViewModel(machineAuthGateway, authSessionManager, deviceIdentifier) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
