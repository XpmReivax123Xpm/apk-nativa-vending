package com.vending.kiosk.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.vending.kiosk.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private val authSessionManager by lazy { AuthSessionManager(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val credentials = authSessionManager.getMachineCredentials()
        val autoResumeEnabled = authSessionManager.isKioskAutoResumeEnabled()
        val hasMachineData = credentials != null &&
            credentials.machineId > 0 &&
            credentials.machineCode.isNotBlank() &&
            credentials.machinePin.isNotBlank()

        if (autoResumeEnabled && hasMachineData) {
            lifecycleScope.launch {
                val refresh = withContext(Dispatchers.IO) {
                    MachineAuthGateway.refreshSessionWithStoredMachineCredentials(authSessionManager)
                }
                when (refresh) {
                    is MachineLoginResult.Success -> {
                        val refreshedCredentials = authSessionManager.getMachineCredentials()
                        val targetMachineId = refreshedCredentials?.machineId ?: refresh.machineId
                        val targetMachineCode = refreshedCredentials?.machineCode ?: refresh.machineCode
                        if (targetMachineId <= 0 || targetMachineCode.isBlank()) {
                            Log.w(TAG, "Auto-resume aborted after refresh: invalid machine target data")
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
                    }
                }
            }
        } else {
            val reason = when {
                !autoResumeEnabled -> "auto-resume disabled"
                credentials == null -> "missing machine credentials"
                credentials.machineId <= 0 -> "invalid machine_id"
                credentials.machineCode.isBlank() -> "missing machine_code"
                credentials.machinePin.isBlank() -> "missing machine_pin"
                else -> "unknown"
            }
            Log.d(TAG, "Auto-resume skipped: $reason")
        }

        val btnVendingKiosk = findViewById<View>(R.id.btnVendingKiosk)
        val btnVendingTester = findViewById<View>(R.id.btnVendingTester)
        val btnVendingCalibrator = findViewById<View>(R.id.btnVendingCalibrator)
        val btnExitApp = findViewById<View>(R.id.btnExitApp)

        btnVendingKiosk.setOnClickListener {
            startActivity(Intent(this, KioskLoginActivity::class.java))
        }
        btnVendingTester.setOnClickListener {
            startActivity(Intent(this, VendingTesterActivity::class.java))
        }
        btnVendingCalibrator.setOnClickListener {
            startActivity(Intent(this, VendingCalibratorActivity::class.java))
        }
        btnExitApp.setOnClickListener {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(homeIntent)
            finishAffinity()
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
