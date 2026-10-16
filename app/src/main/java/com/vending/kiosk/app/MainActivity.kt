package com.vending.kiosk.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.vending.kiosk.R
import com.vending.kiosk.app.data.backend.MachineAuthGateway
import com.vending.kiosk.app.data.backend.MachineLoginResult
import com.vending.kiosk.app.data.session.AuthSessionManager
import com.vending.kiosk.app.device.DeviceIdentifierResult
import com.vending.kiosk.app.device.DeviceIdentity
import com.vending.kiosk.app.device.HardwareWatchdog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private val authSessionManager by lazy { AuthSessionManager(this) }
    private var userSelectedMainMenuAction = false
    private var autoResumeJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        SimulationRuntime.apply()

        // El watchdog de placa se alimenta mientras la app viva: si el kiosko se cuelga, la placa reinicia
        // la tablet sola. En equipos sin el servicio del fabricante no hace nada.
        HardwareWatchdog.start(this)

        val startupLoadingView = findViewById<View>(R.id.startupLoadingView)
        val mainMenuContent = findViewById<View>(R.id.mainMenuContent)

        fun showMainMenu() {
            startupLoadingView.visibility = View.GONE
            mainMenuContent.visibility = View.VISIBLE
        }

        val equipo = authSessionManager.getEquipo()
        val autoResumeEnabled = authSessionManager.isKioskAutoResumeEnabled()
        val hasKioskAccess = equipo != null && equipo.machineId > 0
        val deviceIdentifier = DeviceIdentity.read(this)
        val hasDeviceIdentifier = deviceIdentifier is DeviceIdentifierResult.Available
        // El kiosko no inicia sin el identificador del equipo (no hay ingreso manual), ni antes de que se
        // le haya mostrado UNA vez al operador para que lo anote.
        val deviceIdentifierPending = hasDeviceIdentifier && !authSessionManager.isDeviceIdentifierRevealed()

        val job = lifecycleScope.launch {
            delay(AUTO_RESUME_BOOT_DELAY_MS)

            if (autoResumeEnabled && hasKioskAccess && hasDeviceIdentifier && !deviceIdentifierPending) {
                if (userSelectedMainMenuAction) {
                    Log.d(TAG, "Auto-resume aborted: user selected a main menu action")
                    showMainMenu()
                    return@launch
                }

                val delayedEquipo = authSessionManager.getEquipo()
                val delayedAutoResumeEnabled = authSessionManager.isKioskAutoResumeEnabled()
                val delayedHasKioskAccess = delayedEquipo != null && delayedEquipo.machineId > 0

                if (!delayedAutoResumeEnabled || !delayedHasKioskAccess) {
                    Log.d(TAG, "Auto-resume aborted after boot delay: saved machine data changed")
                    showMainMenu()
                    return@launch
                }

                val refresh = withContext(Dispatchers.IO) {
                    MachineAuthGateway.refreshSessionWithStoredMachineCredentials(authSessionManager)
                }

                if (userSelectedMainMenuAction) {
                    Log.d(TAG, "Auto-resume aborted after refresh: user selected a main menu action")
                    showMainMenu()
                    return@launch
                }

                when (refresh) {
                    is MachineLoginResult.Success -> {
                        val refreshedEquipo = authSessionManager.getEquipo()
                        val targetMachineId = refreshedEquipo?.machineId?.takeIf { it > 0 } ?: refresh.machineId
                        val targetMachineCode = refreshedEquipo?.machineCode?.ifBlank { refresh.machineCode }
                            ?: refresh.machineCode
                        if (targetMachineId <= 0 || targetMachineCode.isBlank()) {
                            Log.w(TAG, "Auto-resume aborted after refresh: invalid machine target data")
                            showMainMenu()
                            return@launch
                        }
                        val machineLocation = authSessionManager.getMachineLocation().orEmpty()
                        Log.d(TAG, "redirecting to KioskCatalogActivity via auto-resume")
                        startActivity(
                            Intent(this@MainActivity, KioskCatalogActivity::class.java).apply {
                                putExtra(KioskCatalogActivity.EXTRA_MACHINE_ID, targetMachineId)
                                putExtra(KioskCatalogActivity.EXTRA_MACHINE_CODE, targetMachineCode)
                                putExtra(KioskCatalogActivity.EXTRA_MACHINE_LOCATION, machineLocation)
                            }
                        )
                        finish()
                        return@launch
                    }

                    is MachineLoginResult.Error -> {
                        Log.w(TAG, "Auto-resume refresh failed: ${refresh.message}. Showing normal menu.")
                        showMainMenu()
                    }
                }
            } else {
                val reason = when {
                    !autoResumeEnabled -> "auto-resume disabled"
                    !hasDeviceIdentifier -> "el equipo no entrego su identificador"
                    deviceIdentifierPending -> "el identificador de la tablet todavia no se mostro"
                    equipo == null -> "el kiosko no tiene equipo configurado"
                    equipo.machineId <= 0 -> "sin datos de la maquina (sin login previo)"
                    else -> "unknown"
                }
                Log.d(TAG, "Auto-resume skipped after startup delay: $reason")
                showMainMenu()
            }
        }
        autoResumeJob = job
        job.invokeOnCompletion {
            if (autoResumeJob === job) {
                autoResumeJob = null
            }
        }

        val btnVendingKiosk = findViewById<View>(R.id.btnVendingKiosk)
        val btnVendingTester = findViewById<View>(R.id.btnVendingTester)
        val btnVendingCalibrator = findViewById<View>(R.id.btnVendingCalibrator)
        val btnExitApp = findViewById<View>(R.id.btnExitApp)

        btnVendingKiosk.setOnClickListener {
            autoResumeJob?.takeIf { it.isActive }?.cancel()
            userSelectedMainMenuAction = true
            startActivity(Intent(this, KioskAccessActivity::class.java))
        }
        btnVendingTester.setOnClickListener {
            autoResumeJob?.takeIf { it.isActive }?.cancel()
            userSelectedMainMenuAction = true
            startActivity(Intent(this, VendingTesterActivity::class.java))
        }
        btnVendingCalibrator.setOnClickListener {
            autoResumeJob?.takeIf { it.isActive }?.cancel()
            userSelectedMainMenuAction = true
            startActivity(Intent(this, VendingCalibratorActivity::class.java))
        }
        btnExitApp.setOnClickListener {
            autoResumeJob?.takeIf { it.isActive }?.cancel()
            userSelectedMainMenuAction = true
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(homeIntent)
            finishAffinity()
        }
    }

    override fun onDestroy() {
        autoResumeJob?.cancel()
        autoResumeJob = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val AUTO_RESUME_BOOT_DELAY_MS = 5_000L
    }
}
