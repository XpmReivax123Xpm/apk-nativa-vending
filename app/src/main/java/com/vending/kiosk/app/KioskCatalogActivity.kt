package com.vending.kiosk.app

import android.content.res.ColorStateList
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.repeatOnLifecycle
import com.vending.kiosk.R
import com.vending.kiosk.app.interaction.CustomerInteractionMonitor
import com.vending.kiosk.app.ui.catalog.CatalogScreen
import com.vending.kiosk.app.ui.catalog.CatalogUiState
import com.vending.kiosk.app.ui.catalog.CatalogViewModel
import com.vending.kiosk.app.ui.cart.CartUiState
import com.vending.kiosk.app.ui.cart.CartScreen
import com.vending.kiosk.app.ui.cart.CartViewModel
import com.vending.kiosk.app.ui.dispense.DispenseErrorUi
import com.vending.kiosk.app.ui.dispense.DispenseProductSummary
import com.vending.kiosk.app.ui.dispense.DispenseScreen
import com.vending.kiosk.app.ui.dispense.DispenseSurface
import com.vending.kiosk.app.ui.dispense.DispenseUiState
import com.vending.kiosk.app.ui.dispense.DispenseViewModel
import com.vending.kiosk.app.ui.dispense.ManualRetryUiState
import com.vending.kiosk.app.ui.idle.IdleVideoOverlayView
import com.vending.kiosk.app.ui.payment.PaymentEvent
import com.vending.kiosk.app.ui.payment.PaymentScreen
import com.vending.kiosk.app.ui.payment.PaymentStep
import com.vending.kiosk.app.ui.payment.PaymentTerminalResult
import com.vending.kiosk.app.ui.payment.PaymentUiState
import com.vending.kiosk.app.ui.payment.PaymentViewModel
import com.vending.kiosk.app.data.backend.HttpVendingBackendGateway
import com.vending.kiosk.app.data.backend.CatalogGatewayException
import com.vending.kiosk.app.data.backend.MachineAuthGateway
import com.vending.kiosk.app.data.images.CatalogImageCache
import com.vending.kiosk.app.data.session.AuthSessionManager
import com.vending.kiosk.app.domain.cart.CartItem
import com.vending.kiosk.app.domain.cart.CartUseCase
import androidx.compose.ui.platform.ComposeView
import com.vending.kiosk.integration.backend.models.CatalogResponse as BackendCatalogResponse
import com.vending.kiosk.integration.backend.models.CreateOrderQrResponse
import com.vending.kiosk.integration.backend.models.DispenseStatusRequest as BackendDispenseStatusRequest
import com.vending.kiosk.integration.serial.runtime.CommandSet
import com.vending.kiosk.integration.serial.runtime.HexUtil
import com.vending.kiosk.integration.serial.runtime.SerialManager
import com.vending.kiosk.integration.serial.runtime.VendingFlowController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class KioskCatalogActivity : AppCompatActivity() {

    private lateinit var tvTitle: TextView
    private lateinit var screenRootView: View
    private lateinit var catalogComposeHost: ComposeView
    private lateinit var catalogViewModel: CatalogViewModel
    private lateinit var cartViewModel: CartViewModel
    private lateinit var paymentViewModel: PaymentViewModel
    private lateinit var dispenseViewModel: DispenseViewModel
    private var catalogComposeState by mutableStateOf(CatalogUiState())
    private var cartComposeState by mutableStateOf(CartUiState())
    private var paymentComposeState by mutableStateOf(PaymentUiState())
    private var dispenseComposeState by mutableStateOf(DispenseUiState())
    private var cartTimeoutTimer: CountDownTimer? = null
    private var paymentTimeoutTimer: CountDownTimer? = null
    private var cartModalShown = false
    private var paymentModalShown = false
    private var dispenseModalShown = false
    private var paymentTimeoutStep: PaymentStep? = null
    private var paidOrderHandledId: Int? = null
    private var catalogLoadInProgress = false
    private var sessionLostHandled = false
    private var btnKioskBackToMain: Button? = null
    private var btnKioskViewLogs: Button? = null
    private var btnKioskViewBitacora: Button? = null
    private var btnDisableAutoResumeKiosk: Button? = null
    private var kioskUnlockedByPin = false

    private val authSessionManager by lazy { AuthSessionManager(this) }
    private val vendingBackendGateway by lazy { HttpVendingBackendGateway(authSessionManager) }
    private var machineId: Int = 0
    private var machineCode: String = ""
    private var authHeader: String = ""
    private val catalogImageCache by lazy { CatalogImageCache(this) }

    private val serial = SerialManager()
    private lateinit var vendFlow: VendingFlowController
    private var dispensingQueue: List<DispenseQueueItem> = emptyList()
    private var dispensingCursor = 0
    private var dispensingInProgress = false
    private var clearCartOnDispenseFinish = false
    private var activeDispensePedidoId = 0
    private val dispenseSuccessTimerHandler = Handler(Looper.getMainLooper())
    private var dispenseSuccessTimerRunnable: Runnable? = null
    private var monitorViewerDialog: AlertDialog? = null
    private var monitorViewerRunnable: Runnable? = null
    private var idleVideoOverlayView: IdleVideoOverlayView? = null
    private var kioskLocked = false
    private val unlockHoldHandler = Handler(Looper.getMainLooper())
    private var unlockHoldTriggered = false
    private val inactivityHandler = Handler(Looper.getMainLooper())
    private val idleIoHandler = Handler(Looper.getMainLooper())
    private var idleIoPollingActive = false
    private var activeModalCount = 0
    private val inactivityRunnable = Runnable {
        if (activeModalCount > 0) {
            Log.d(TAG, "Inactivity refresh skipped: modal is open")
            scheduleInactivityRefresh()
            return@Runnable
        }
        if (machineId <= 0 || authHeader.isBlank()) return@Runnable
        refreshCatalogAndClearCart()
        idleVideoOverlayView?.show()
        Toast.makeText(
            this,
            "Inactividad detectada. Mostrando video de espera.",
            Toast.LENGTH_SHORT
        ).show()
        scheduleInactivityRefresh()
    }

    private val serialListener = object : SerialManager.Listener {
        override fun onRx(data: ByteArray, size: Int) {
            val rx = HexUtil.bytesToHex(data, size).replace(" ", "").uppercase()
            interactionMonitor.appendBitacora("RX: $rx")
            if (::vendFlow.isInitialized) {
                vendFlow.onRx(data, size)
            }
        }

        override fun onError(e: Exception) {
            if (dispensingInProgress) {
                runOnUiThread {
                    onDispenseError("Error serial: ${e.message ?: "sin detalle"}", "DRIVER_0000")
                }
            }
        }

        override fun onStatus(msg: String) {
            interactionMonitor.appendBitacora(msg)
            if (dispensingInProgress && msg.startsWith("TX:")) {
                runOnUiThread {
                    dispenseViewModel.updateStatus("Espera un momento, por favor...")
                }
            }
        }
    }

    private val vendingUi = object : VendingFlowController.Ui {
        override fun onLog(msg: String) {
            interactionMonitor.appendBoth(msg)
        }

        override fun onNeedRetrieve(msg: String) {
            interactionMonitor.appendBoth("NEED_RETRIEVE: $msg")
            runOnUiThread {
                if (dispensingInProgress) {
                    reportDispenseStateByIndex(
                        index = dispensingCursor,
                        tnEstado = 3,
                        tcEstado = "RECOJO_PENDIENTE"
                    )
                    val current = (dispensingCursor + 1).coerceAtMost(dispensingQueue.size)
                    showRetrieveDialogForCurrentItem()
                    dispenseViewModel.updateStatus(
                        "Por favor retira el producto. Preparando siguiente item ($current de ${dispensingQueue.size})..."
                    )
                }
            }
        }

        override fun onDone() {
            interactionMonitor.appendBoth("DONE: ciclo de retiro confirmado")
            runOnUiThread { onDispenseItemDone() }
        }

        override fun onPlatformStuck(msg: String) {
            interactionMonitor.appendBoth("PLATFORM_STUCK: $msg")
            runOnUiThread {
                if (dispensingInProgress) {
                    dispenseViewModel.showPlatformStuck(msg)
                }
            }
        }

        override fun onError(msg: String) {
            interactionMonitor.appendBoth("ERROR_RUNTIME: $msg")
            runOnUiThread {
                val parsed = parseRuntimeDispenseError(msg)
                onDispenseError(parsed.second, parsed.first)
            }
        }

        override fun onStep(stepMsg: String) {
            interactionMonitor.appendBoth("STEP: $stepMsg")
            runOnUiThread {
                val parts = stepMsg.split("|", limit = 2)
                val code = parts.firstOrNull()?.trim().orEmpty()
                if (code == "IO_TIMEOUT_RECOVERED") {
                    if (dispensingInProgress) {
                        showRetrieveDialogForCurrentItem()
                    }
                } else if (code == "IO_TIMEOUT_PROLONGED") {
                    if (dispensingInProgress) {
                        showDispenseIoProlongedWaitDialog()
                    }
                } else if (code == "DRIVER_ZERO_PICKUP_MODE") {
                    dispensingQueue.getOrNull(dispensingCursor)?.driverZeroDelivered = true
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { initializeScreen() }
            .onFailure { error -> showSafeFallback(error) }
    }

    private fun initializeScreen() {
        applyCatalogSystemBars()
        val layoutRes = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1) {
            R.layout.activity_kiosk_catalog_legacy
        } else {
            R.layout.activity_kiosk_catalog
        }
        setContentView(layoutRes)
        tvTitle = findViewById(R.id.tvCatalogTitle)
        catalogComposeHost = findViewById(R.id.catalogComposeHost)
        btnKioskBackToMain = findViewById(R.id.btnKioskBackToMain)
        btnKioskViewLogs = findViewById(R.id.btnKioskViewLogs)
        btnKioskViewBitacora = findViewById(R.id.btnKioskViewBitacora)
        screenRootView = (findViewById<View>(android.R.id.content) as ViewGroup).getChildAt(0)
        setupDispenseRuntime()
        setupBackToMainButton()
        setupMonitoringButtons()
        idleVideoOverlayView = IdleVideoOverlayView(
            root = screenRootView,
            onOverlayTouched = {
                idleVideoOverlayView?.hide()
                scheduleInactivityRefresh()
            },
            onPlaybackError = {
                idleVideoOverlayView?.hide()
                scheduleInactivityRefresh()
            }
        )

        machineId = intent.getIntExtra(EXTRA_MACHINE_ID, 0)
        machineCode = intent.getStringExtra(EXTRA_MACHINE_CODE).orEmpty()
        val machineLocation = intent.getStringExtra(EXTRA_MACHINE_LOCATION).orEmpty()

        if (machineId <= 0) {
            throw IllegalStateException("Maquina invalida")
        }

        moveUnlockGestureTargetAboveComposeHost()
        setupUnlockGestureOnMachineTitle()
        enterKioskMode()

        authHeader = authSessionManager.getAuthorizationHeader().orEmpty()
        catalogViewModel = ViewModelProvider(
            this,
            CatalogViewModelFactory(vendingBackendGateway, catalogImageCache, authSessionManager)
        )[CatalogViewModel::class.java]
        cartViewModel = ViewModelProvider(
            this,
            CartViewModelFactory(CartUseCase())
        )[CartViewModel::class.java]
        paymentViewModel = ViewModelProvider(
            this,
            PaymentViewModelFactory(
                vendingBackendGateway,
                MachineAuthGateway,
                authSessionManager,
                machineId
            )
        )[PaymentViewModel::class.java]
        dispenseViewModel = ViewModelProvider(this)[DispenseViewModel::class.java]
        observeCartState()
        observeCatalogState()
        observePaymentState()
        observeDispenseState()
        catalogViewModel.configureMachine(machineId, machineCode, machineLocation)
        catalogLoadInProgress = true
        catalogViewModel.loadCatalog()
    }

    override fun onResume() {
        super.onResume()
        paymentViewModel.prefetchPaymentMethodsIfNeeded()
        applyImmersiveKioskUi()
        startIdleIoPolling()
        scheduleInactivityRefresh()
    }

    override fun onPause() {
        unlockHoldHandler.removeCallbacksAndMessages(null)
        inactivityHandler.removeCallbacksAndMessages(null)
        stopIdleIoPolling()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
    }

    override fun onDestroy() {
        cartTimeoutTimer?.cancel()
        cartTimeoutTimer = null
        paymentTimeoutTimer?.cancel()
        paymentTimeoutTimer = null
        stopDispenseSuccessCountdown()
        idleVideoOverlayView?.hide()
        idleVideoOverlayView?.stopPlayback()
        monitorViewerDialog?.takeIf { it.isShowing }?.dismiss()
        monitorViewerDialog = null
        monitorViewerRunnable?.let { inactivityHandler.removeCallbacks(it) }
        monitorViewerRunnable = null
        unlockHoldHandler.removeCallbacksAndMessages(null)
        inactivityHandler.removeCallbacksAndMessages(null)
        if (::vendFlow.isInitialized) {
            runCatching { vendFlow.stop() }
        }
        runCatching { serial.close() }
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && kioskLocked) {
            applyImmersiveKioskUi()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (kioskLocked && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_APP_SWITCH,
                KeyEvent.KEYCODE_MENU -> return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun setupDispenseRuntime() {
        vendFlow = VendingFlowController(
            serial = serial,
            serialListener = serialListener,
            ui = vendingUi,
            platformRecoveryCommand = SdkYZeroPlatformRecoveryCommand(
                context = this,
                serial = serial,
                serialListener = serialListener,
                portProvider = { DEFAULT_PORT },
                baudProvider = { DEFAULT_BAUD },
                beforeSdkOpen = { stopIdleIoPolling() },
                afterRawReopen = { startIdleIoPolling() }
            )
        )
    }

    private val interactionMonitor by lazy { CustomerInteractionMonitor(this) }

    private fun setupBackToMainButton() {
        setupDisableAutoResumeButton()
        btnKioskBackToMain?.setOnClickListener {
            if (!kioskUnlockedByPin) return@setOnClickListener
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
        updateBackToMainVisibility()
    }

    private fun updateBackToMainVisibility() {
        val visibility = if (kioskUnlockedByPin) View.VISIBLE else View.GONE
        btnKioskBackToMain?.visibility = visibility
        btnKioskViewLogs?.visibility = visibility
        btnKioskViewBitacora?.visibility = visibility
        btnDisableAutoResumeKiosk?.visibility = visibility
    }

    private fun setupMonitoringButtons() {
        btnKioskViewLogs?.setOnClickListener {
            val text = interactionMonitor.getLastLogsText()
            if (text.isBlank()) {
                Toast.makeText(this, "Aun no hay logs guardados de clientes.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showMonitoringViewerDialog(
                title = "Logs - ultima interaccion",
                live = false,
                bitacora = false
            )
        }
        btnKioskViewBitacora?.setOnClickListener {
            val text = interactionMonitor.getLastBitacoraText()
            if (text.isBlank()) {
                Toast.makeText(this, "Aun no hay bitacora guardada de clientes.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showMonitoringViewerDialog(
                title = "Bitacora - ultima interaccion",
                live = false,
                bitacora = true
            )
        }
    }

    private fun setupDisableAutoResumeButton() {
        val existing = btnDisableAutoResumeKiosk
        if (existing != null) {
            btnDisableAutoResumeKiosk = existing
        } else {
            val backButton = btnKioskBackToMain ?: return
            val parent = backButton.parent as? LinearLayout ?: return
            btnDisableAutoResumeKiosk = Button(this).apply {
                text = "Desactivar auto-resume kiosk"
                isAllCaps = false
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                backgroundTintList = ColorStateList.valueOf(Color.parseColor("#B3261E"))
                visibility = View.GONE
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(40)
                ).apply {
                    marginStart = dp(10)
                }
                layoutParams = params
            }
            parent.addView(btnDisableAutoResumeKiosk)
        }

        btnDisableAutoResumeKiosk?.setOnClickListener {
            if (!kioskUnlockedByPin) return@setOnClickListener
            authSessionManager.disableKioskAutoResume()
            Log.d(TAG, "Auto-resume kiosk disabled by admin action")
            Toast.makeText(this, "Auto-resume kiosk desactivado", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onBackPressed() {
        if (dispenseComposeState.surface != DispenseSurface.Hidden) {
            return
        }
        if (kioskLocked) {
            Toast.makeText(this, "Modo kiosk activo", Toast.LENGTH_SHORT).show()
            return
        }
        super.onBackPressed()
    }

    private fun setupUnlockGestureOnMachineTitle() {
        val holdRunnable = Runnable {
            if (!kioskLocked) return@Runnable
            unlockHoldTriggered = true
            showUnlockPinDialog()
        }

        tvTitle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    unlockHoldTriggered = false
                    unlockHoldHandler.removeCallbacksAndMessages(null)
                    unlockHoldHandler.postDelayed(holdRunnable, 2_000L)
                    true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    unlockHoldHandler.removeCallbacksAndMessages(null)
                    unlockHoldTriggered
                }

                else -> false
            }
        }
    }

    private fun moveUnlockGestureTargetAboveComposeHost() {
        val header = tvTitle.parent as? ViewGroup ?: return
        header.removeView(tvTitle)
        header.visibility = View.GONE
        val root = screenRootView as? FrameLayout ?: return
        root.addView(
            tvTitle,
            (root.childCount - 1).coerceAtLeast(0),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(72)
            )
        )
        tvTitle.alpha = 0f
    }

    private fun enterKioskMode() {
        Log.d(TAG, "entering kiosk mode")
        kioskLocked = true
        kioskUnlockedByPin = false
        updateBackToMainVisibility()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersiveKioskUi()
        Log.d(TAG, "Android LockTask disabled; OEM kiosk mode is responsible for navigation hiding")
    }

    private fun exitKioskMode() {
        Log.d(TAG, "exiting kiosk mode")
        kioskLocked = false
        kioskUnlockedByPin = true
        updateBackToMainVisibility()
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        Toast.makeText(this, "Modo kiosk desbloqueado", Toast.LENGTH_SHORT).show()
    }

    private fun applyImmersiveKioskUi() {
        var flags = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        window.decorView.systemUiVisibility = flags
    }

    private fun showUnlockPinDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_unlock_pin, null)
        val etPin = view.findViewById<EditText>(R.id.etUnlockPin)
        val tvError = view.findViewById<TextView>(R.id.tvUnlockError)
        val progress = view.findViewById<ProgressBar>(R.id.progressUnlock)
        val btnCancel = view.findViewById<Button>(R.id.btnUnlockCancel)
        val btnConfirm = view.findViewById<Button>(R.id.btnUnlockConfirm)

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(false)
            .create()

        fun setLoading(loading: Boolean) {
            progress.visibility = if (loading) View.VISIBLE else View.GONE
            etPin.isEnabled = !loading
            btnCancel.isEnabled = !loading
            btnConfirm.isEnabled = !loading
        }

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnConfirm.setOnClickListener {
            val pin = etPin.text?.toString()?.trim().orEmpty()
            if (pin.isBlank()) {
                tvError.visibility = View.VISIBLE
                tvError.text = "Ingresa el PIN"
                return@setOnClickListener
            }

            setLoading(true)
            tvError.visibility = View.GONE
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    validateMachineAccess(machineCode, pin)
                }
                setLoading(false)
                when (result) {
                    is MachineAccessResult.Granted -> {
                        dialog.dismiss()
                        exitKioskMode()
                    }

                    is MachineAccessResult.Denied -> {
                        tvError.visibility = View.VISIBLE
                        tvError.text = result.message
                    }

                    is MachineAccessResult.Error -> {
                        tvError.visibility = View.VISIBLE
                        tvError.text = result.message
                    }
                }
            }
        }

        onModalShown()
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setOnDismissListener {
            onModalDismissed()
        }
    }

    private fun validateMachineAccess(machineCode: String, pin: String): MachineAccessResult {
        if (machineCode.isBlank()) return MachineAccessResult.Error("Codigo de maquina invalido")
        val endpoint = "https://boxipagobackend.pagofacil.com.bo/api/maquinas/acceso"
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 12_000
                readTimeout = 12_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }

            val payload = JSONObject().apply {
                put("tcCodigoMaquina", machineCode)
                put("tcPin", pin)
            }.toString()

            connection.outputStream.use { output ->
                output.write(payload.toByteArray(Charsets.UTF_8))
            }

            val statusCode = connection.responseCode
            val rawBody = runCatching {
                if (statusCode in 200..299) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                }
            }.getOrDefault("")

            if (rawBody.isBlank()) {
                return MachineAccessResult.Error("Sin respuesta de validacion de PIN")
            }

            val json = JSONObject(rawBody)
            val backendError = json.optInt("error", -1)
            val backendStatus = json.optInt("status", 0)
            val backendMessage = json.optString("message", "No se pudo validar acceso")
            val values = json.optJSONObject("values") ?: JSONObject()
            val acceso = values.optInt("tnAcceso", 0)

            return if (statusCode in 200..299 && backendError == 0 && backendStatus == 1 && acceso == 1) {
                MachineAccessResult.Granted
            } else if (acceso == 0 || backendError != 0 || backendStatus != 1) {
                MachineAccessResult.Denied(backendMessage.ifBlank { "PIN invalido" })
            } else {
                MachineAccessResult.Error("$backendMessage (HTTP $statusCode)")
            }
        } catch (ex: Exception) {
            MachineAccessResult.Error("Error validando acceso: ${ex.message ?: "sin detalle"}")
        } finally {
            connection?.disconnect()
        }
    }

    private fun showSafeFallback(error: Throwable) {
        setContentView(R.layout.activity_kiosk_catalog_fallback)
        findViewById<TextView>(R.id.tvFallbackTitle).text =
            intent.getStringExtra(EXTRA_MACHINE_CODE).orEmpty().ifBlank { "Catalogo" }
        findViewById<TextView>(R.id.tvFallbackDetail).text =
            "No se pudo abrir la vista completa en este dispositivo.\nDetalle: ${error.message ?: "sin detalle"}"
        Toast.makeText(this, "Modo seguro de catalogo activado", Toast.LENGTH_SHORT).show()
    }

    private fun handleAuthSessionLost() {
        Toast.makeText(
            this,
            "Sesion de maquina expirada. Selecciona la maquina e ingresa PIN nuevamente.",
            Toast.LENGTH_LONG
        ).show()
        finish()
    }

    private fun loadCatalog(machineId: Int, authHeader: String) {
        catalogLoadInProgress = true
        catalogViewModel.loadCatalog()
    }

    private fun observeCatalogState() {
        catalogComposeHost.setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                CatalogScreen(
                    state = catalogComposeState,
                    cartQuantities = cartComposeState.items.associate { it.planogramCellId to it.quantity },
                    cartTotalUnits = cartComposeState.totalUnits,
                    cartTotalAmount = cartComposeState.totalAmount,
                    onIncrementProduct = { item ->
                        if (cartComposeState.items.any { it.planogramCellId == item.planogramCellId }) {
                            cartViewModel.increment(item.planogramCellId)
                        } else {
                            cartViewModel.addProduct(item)
                        }
                    },
                    onDecrementProduct = { item -> cartViewModel.decrement(item.planogramCellId) },
                    onCartClick = { cartViewModel.openCart() }
                )

                AnimatedVisibility(
                    visible = cartComposeState.isCartOpen && paymentComposeState.step == PaymentStep.Closed,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(ComposeColor.Black.copy(alpha = 0.48f))
                            .clickable { closeCartFromCompose() }
                    )
                }

                AnimatedVisibility(
                    visible = cartComposeState.isCartOpen && paymentComposeState.step == PaymentStep.Closed,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = slideInVertically { it },
                    exit = slideOutVertically { it }
                ) {
                    CartScreen(
                        state = cartComposeState,
                        onIncrement = { cellId -> cartViewModel.increment(cellId) },
                        onDecrement = { cellId -> cartViewModel.decrement(cellId) },
                        onRemove = { cellId -> cartViewModel.remove(cellId) },
                        onClear = { cartViewModel.clear() },
                        onBuy = ::buyCartFromCompose,
                        onClose = ::closeCartFromCompose,
                        onUserInteraction = ::restartCartTimeout
                    )
                }

                if (paymentComposeState.step != PaymentStep.Closed) {
                    PaymentScreen(
                        state = paymentComposeState,
                        onSelectPaymentMethod = paymentViewModel::selectPaymentMethod,
                        onContinueToCheckout = paymentViewModel::continueToCheckout,
                        onReturnToMethodSelection = paymentViewModel::returnToMethodSelection,
                        onConfirmCheckout = paymentViewModel::confirmCheckout,
                        onCancel = ::cancelPaymentFromCompose,
                        onInteraction = ::restartPaymentTimeout
                    )
                }

                if (dispenseComposeState.surface != DispenseSurface.Hidden) {
                    DispenseScreen(
                        state = dispenseComposeState,
                        onManualPickupRetry = ::requestManualPickupRetry,
                        onPlatformRecoveryRequested = ::requestPlatformRecovery,
                        onSuccessCloseRequested = ::closeDispenseSuccessSurface,
                        onErrorViewLogsRequested = ::showDispenseErrorLogs,
                        onErrorViewBitacoraRequested = ::showDispenseErrorBitacora,
                        onErrorSaveMonitoringRequested = ::saveDispenseErrorMonitoring
                    )
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                catalogViewModel.uiState.collect { state ->
                    catalogComposeState = state
                    if (state.isLoading) {
                        catalogLoadInProgress = true
                    }
                    if (state.sessionLost) {
                        if (!sessionLostHandled) {
                            sessionLostHandled = true
                            handleAuthSessionLost()
                        }
                    } else {
                        sessionLostHandled = false
                    }
                    if (!state.isLoading && catalogLoadInProgress) {
                        catalogLoadInProgress = false
                        if (state.error != null || state.sessionLost) return@collect
                        authHeader = authSessionManager.getAuthorizationHeader().orEmpty()
                        cartViewModel.syncWithCatalog(state.items)
                    }
                }
            }
        }
    }

    private fun observeDispenseState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                dispenseViewModel.uiState.collect { state ->
                    dispenseComposeState = state
                    handleDispenseVisibility(state.surface != DispenseSurface.Hidden)
                }
            }
        }
    }

    private fun handleDispenseVisibility(isVisible: Boolean) {
        if (isVisible && !dispenseModalShown) {
            dispenseModalShown = true
            onModalShown()
        } else if (!isVisible && dispenseModalShown) {
            dispenseModalShown = false
            onModalDismissed()
        }
    }

    private fun observeCartState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                cartViewModel.uiState.collect { state ->
                    cartComposeState = state
                    handleCartVisibility(state.isCartOpen)
                    if (state.operationNotApplied) {
                        Toast.makeText(this@KioskCatalogActivity, "Stock maximo alcanzado en carrito", Toast.LENGTH_SHORT).show()
                        cartViewModel.consumeOperationNotApplied()
                    }
                }
            }
        }
    }

    private fun CartItem.toCeldaUi(): CeldaUi = CeldaUi(
        planogramaCeldaId = planogramCellId,
        productoId = productId,
        codigoCelda = cellCode,
        producto = name,
        precio = unitPrice,
        stockDisponible = availableStock,
        vendible = isVendible,
        physicalCell = physicalCell,
        imagenUrl = primaryImageUrl,
        imagenUrlSecundaria = secondaryImageUrl
    )

    private fun List<CartItem>.toPurchaseSelections(): List<PurchaseSelection> = map {
        PurchaseSelection(it.toCeldaUi(), it.quantity)
    }

    private fun handleCartVisibility(isCartOpen: Boolean) {
        if (isCartOpen && !cartModalShown) {
            cartModalShown = true
            onModalShown()
            restartCartTimeout()
        } else if (!isCartOpen && cartModalShown) {
            cartModalShown = false
            cartTimeoutTimer?.cancel()
            cartTimeoutTimer = null
            onModalDismissed()
        }
    }

    private fun restartCartTimeout() {
        if (!cartViewModel.uiState.value.isCartOpen) return
        cartTimeoutTimer?.cancel()
        cartTimeoutTimer = object : CountDownTimer(PRODUCT_DIALOG_TIMEOUT_MS, 1_000L) {
            override fun onTick(millisUntilFinished: Long) = Unit

            override fun onFinish() {
                cartViewModel.clear()
                cartViewModel.closeCart()
                refreshCatalogAndClearCart()
            }
        }.start()
    }

    private fun closeCartFromCompose() {
        cartViewModel.closeCart()
    }

    private fun buyCartFromCompose() {
        val snapshot = cartViewModel.uiState.value.items.map { it.copy() }
        if (snapshot.isEmpty()) return
        cartViewModel.closeCart()
        paymentViewModel.startPayment(snapshot)
    }

    private fun refreshCatalogAndClearCart() {
        cartViewModel.clear()
        if (machineId > 0 && authHeader.isNotBlank()) {
            loadCatalog(machineId, authHeader)
        }
    }

    private suspend fun fetchCatalog(machineId: Int): CatalogResult {
        return CatalogResult.Error("Legacy catalog loading is disabled")
    }

    private fun mapCatalogResponse(response: BackendCatalogResponse): CatalogResult.Success {
        val celdas = response.cells.mapIndexed { index, cell ->
            val slotBase = when {
                cell.productId > 0 -> "product_${cell.productId}"
                else -> "cell_${cell.sourceCellId.takeIf { it != 0 } ?: index}"
            }
            CeldaUi(
                planogramaCeldaId = cell.planogramCellId,
                productoId = cell.productId,
                codigoCelda = cell.cellCode,
                producto = cell.productName,
                precio = cell.price,
                stockDisponible = cell.availableStock,
                vendible = cell.vendible,
                physicalCell = cell.physicalCell,
                imagenUrl = catalogImageCache.resolveImageSourceForCache(
                    slot = "${slotBase}_principal",
                    incomingId = cell.imageId,
                    remoteUrl = cell.imageUrl,
                    targetSizePx = 480
                ),
                imagenUrlSecundaria = catalogImageCache.resolveImageSourceForCache(
                    slot = "${slotBase}_secondary",
                    incomingId = cell.secondaryImageId,
                    remoteUrl = cell.secondaryImageUrl,
                    targetSizePx = 480
                )
            )
        }
        val promotions = response.promotions.map { promo ->
            PromoSlideUi(
                url = catalogImageCache.resolveImageSourceForCache(
                    slot = "promo_${promo.id}",
                    incomingId = promo.id,
                    remoteUrl = promo.url,
                    targetSizePx = 900
                ),
                visualOrder = promo.visualOrder,
                id = promo.id
            )
        }
        val backgroundImageUrl = catalogImageCache.resolveImageSourceForCache(
            slot = "background_main",
            incomingId = response.backgroundImageId,
            remoteUrl = response.backgroundImageUrl,
            targetSizePx = 1440
        )
        return CatalogResult.Success(celdas, promotions, backgroundImageUrl)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (idleVideoOverlayView?.isVisible == true) {
            idleVideoOverlayView?.hide()
        }
        scheduleInactivityRefresh()
    }

    private fun scheduleInactivityRefresh() {
        inactivityHandler.removeCallbacks(inactivityRunnable)
        if (activeModalCount > 0) {
            Log.d(TAG, "Inactivity timer paused while modal is visible")
            return
        }
        inactivityHandler.postDelayed(inactivityRunnable, PLANOGRAM_INACTIVITY_REFRESH_MS)
    }

    private fun onModalShown() {
        activeModalCount += 1
        inactivityHandler.removeCallbacks(inactivityRunnable)
    }

    private fun onModalDismissed() {
        activeModalCount = (activeModalCount - 1).coerceAtLeast(0)
        if (activeModalCount == 0) {
            scheduleInactivityRefresh()
        }
    }

    private fun applyUiBackground(imageUrl: String) {
        if (imageUrl.isBlank()) {
            screenRootView.setBackgroundResource(R.drawable.bg_kiosk_catalog_screen_hot)
            return
        }

        val tagValue = "ui-bg:$imageUrl"
        screenRootView.tag = tagValue
        val cached = catalogImageCache.getBitmap(imageUrl)
        if (cached != null) {
            screenRootView.background = BitmapDrawable(resources, cached)
            return
        }

        if (catalogImageCache.isLocalImagePath(imageUrl)) {
            val bitmap = catalogImageCache.loadBitmapFromLocalPath(imageUrl, 1440)
            if (bitmap != null) {
                catalogImageCache.putBitmap(imageUrl, bitmap)
                screenRootView.background = BitmapDrawable(resources, bitmap)
                return
            }
            screenRootView.setBackgroundResource(R.drawable.bg_kiosk_catalog_screen_hot)
            return
        }

        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { catalogImageCache.downloadBitmap(imageUrl, 1440) }
            if (bitmap != null) {
                catalogImageCache.putBitmap(imageUrl, bitmap)
            }
            if (screenRootView.tag == tagValue && bitmap != null) {
                screenRootView.background = BitmapDrawable(resources, bitmap)
            } else if (screenRootView.tag == tagValue) {
                screenRootView.setBackgroundResource(R.drawable.bg_kiosk_catalog_screen_hot)
            }
        }
    }

    private fun observePaymentState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                paymentViewModel.uiState.collect { state ->
                    paymentComposeState = state
                    handlePaymentVisibility(state)
                    if (state.sessionLost) {
                        paymentViewModel.consumeSessionLost()
                        handleAuthSessionLost()
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                paymentViewModel.events.collect { event ->
                    when (event) {
                        is PaymentEvent.Terminal -> handlePaymentTerminal(event.result)
                        PaymentEvent.RefreshCatalogRequested -> {
                            paymentViewModel.closePayment()
                            refreshCatalogAndClearCart()
                        }
                        PaymentEvent.SessionLost -> Unit
                    }
                }
            }
        }
    }

    private fun handlePaymentVisibility(state: PaymentUiState) {
        val isActive = state.step != PaymentStep.Closed
        if (isActive && !paymentModalShown) {
            paymentModalShown = true
            onModalShown()
        } else if (!isActive && paymentModalShown) {
            paymentModalShown = false
            paymentTimeoutTimer?.cancel()
            paymentTimeoutTimer = null
            paymentTimeoutStep = null
            onModalDismissed()
        }

        if (state.step == PaymentStep.MethodSelection || state.step == PaymentStep.Checkout) {
            if (paymentTimeoutStep != state.step) restartPaymentTimeout()
        } else {
            paymentTimeoutTimer?.cancel()
            paymentTimeoutTimer = null
            paymentTimeoutStep = null
        }
    }

    private fun restartPaymentTimeout() {
        val step = paymentViewModel.uiState.value.step
        if (step != PaymentStep.MethodSelection && step != PaymentStep.Checkout) return
        paymentTimeoutTimer?.cancel()
        paymentTimeoutStep = step
        paymentTimeoutTimer = object : CountDownTimer(PRODUCT_DIALOG_TIMEOUT_MS, 1_000L) {
            override fun onTick(millisUntilFinished: Long) = Unit

            override fun onFinish() {
                paymentViewModel.closePayment()
                refreshCatalogAndClearCart()
            }
        }.start()
    }

    private fun cancelPaymentFromCompose() {
        when (paymentViewModel.uiState.value.step) {
            PaymentStep.Qr -> paymentViewModel.cancelPendingOrder()
            PaymentStep.MethodSelection,
            PaymentStep.Checkout,
            PaymentStep.Completed -> paymentViewModel.closePayment()
            PaymentStep.Closed -> Unit
        }
    }

    private fun handlePaymentTerminal(result: PaymentTerminalResult) {
        when (result) {
            is PaymentTerminalResult.Paid -> {
                if (paidOrderHandledId == result.order.orderId) return
                paidOrderHandledId = result.order.orderId
                val state = paymentViewModel.uiState.value
                val paymentMethodLabel = state.selectedPaymentMethod?.label.orEmpty()
                val selections = state.items.toPurchaseSelections()
                if (selections.isEmpty() || paymentMethodLabel.isBlank()) return
                paymentViewModel.closePayment()
                showDispenseDialogAndStart(selections, result.order, paymentMethodLabel)
            }

            is PaymentTerminalResult.Cancelled,
            is PaymentTerminalResult.TimedOut -> {
                val message = when (result) {
                    is PaymentTerminalResult.Cancelled -> result.message
                    is PaymentTerminalResult.TimedOut -> result.message
                    else -> ""
                }
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }

            is PaymentTerminalResult.Failed -> Unit
        }
    }

    private fun showDispenseDialogAndStart(
        selections: List<PurchaseSelection>,
        qrResult: CreateOrderQrResponse,
        paymentMethodLabel: String
    ) {
        val queue = when (val result = buildDispenseQueue(selections, qrResult.details)) {
            is DispenseQueueBuildResult.InvalidCell -> {
                Toast.makeText(this, "No se pudo mapear la celda ${result.cellCode}", Toast.LENGTH_LONG).show()
                return
            }

            is DispenseQueueBuildResult.Success -> result.queue
        }

        if (queue.isEmpty()) {
            Toast.makeText(this, "No hay celdas para dispensar", Toast.LENGTH_SHORT).show()
            return
        }

        if (!ensureSerialConnection()) {
            Toast.makeText(this, "No se pudo abrir puerto serial /dev/ttyS1", Toast.LENGTH_LONG).show()
            return
        }

        dispensingQueue = queue
        dispensingCursor = 0
        dispensingInProgress = true
        clearCartOnDispenseFinish = true
        activeDispensePedidoId = qrResult.orderId

        interactionMonitor.startSession(
            machineCode = machineCode,
            pedidoId = qrResult.orderId,
            paymentMethodLabel = paymentMethodLabel,
            selectedCellsSummary = selections.map {
                "${it.item.codigoCelda} - ${it.item.producto} x${it.quantity}"
            }
        )
        interactionMonitor.appendBoth("Dispensacion iniciada para ${dispensingQueue.size} item(s)")

        val firstItem = dispensingQueue.firstOrNull()
        dispenseViewModel.showDispensing(
            currentIndex = 1,
            totalItems = dispensingQueue.size,
            productName = firstItem?.item?.producto.orEmpty(),
            productImageSource = resolveDispenseImageSource(firstItem?.item?.imagenUrl.orEmpty())
        )

        startNextDispenseItem()
    }

    private fun buildDispenseQueue(
        selections: List<PurchaseSelection>,
        details: List<CreateOrderQrResponse.OrderDetail>
    ): DispenseQueueBuildResult {
        val queue = mutableListOf<DispenseQueueItem>()
        val detallePendiente = details.toMutableList()
        selections.forEach { selection ->
            val physical = selection.item.physicalCell.takeIf { it in 10..68 }
                ?: mapCellCodeToPhysical(selection.item.codigoCelda)
                ?: return DispenseQueueBuildResult.InvalidCell(selection.item.codigoCelda)
            repeat(selection.quantity) {
                val idx = detallePendiente.indexOfFirst { it.planogramCellId == selection.item.planogramaCeldaId }
                val detalle = if (idx >= 0) {
                    detallePendiente.removeAt(idx)
                } else {
                    if (detallePendiente.isNotEmpty()) detallePendiente.removeAt(0) else null
                }
                queue += DispenseQueueItem(
                    cell = physical,
                    item = selection.item,
                    tnPedidoDetalle = detalle?.orderDetailId ?: 0,
                    tnEstadoDispensacion = 0
                )
            }
        }
        return DispenseQueueBuildResult.Success(queue)
    }

    private sealed interface DispenseQueueBuildResult {
        data class Success(val queue: List<DispenseQueueItem>) : DispenseQueueBuildResult
        data class InvalidCell(val cellCode: String) : DispenseQueueBuildResult
    }

    private fun ensureSerialConnection(): Boolean {
        if (serial.isOpen()) return true
        serial.open(DEFAULT_PORT, DEFAULT_BAUD, serialListener)
        if (serial.isOpen()) {
            startIdleIoPolling()
        }
        return serial.isOpen()
    }

    private fun startIdleIoPolling() {
        if (idleIoPollingActive) return
        idleIoPollingActive = true
        idleIoHandler.post(idleIoPollRunnable)
    }

    private fun stopIdleIoPolling() {
        idleIoPollingActive = false
        idleIoHandler.removeCallbacks(idleIoPollRunnable)
    }

    private fun startNextDispenseItem() {
        if (!dispensingInProgress) return
        if (dispensingCursor >= dispensingQueue.size) {
            onDispenseFinished()
            return
        }

        val currentItem = dispensingQueue[dispensingCursor]
        val currentCell = currentItem.cell
        val currentNumber = dispensingCursor + 1
        val total = dispensingQueue.size
        dispenseViewModel.showDispensing(
            currentIndex = currentNumber,
            totalItems = total,
            productName = currentItem.item.producto,
            productImageSource = resolveDispenseImageSource(currentItem.item.imagenUrl),
            statusText = "Espera un momento, por favor..."
        )
        interactionMonitor.appendBoth(
            "Inicio dispensacion celda ${currentItem.item.codigoCelda} (fisica $currentCell) | producto=${currentItem.item.producto} | item ${currentNumber} de $total"
        )
        reportDispenseStateByIndex(
            index = dispensingCursor,
            tnEstado = 2,
            tcEstado = "EN_PROCESO"
        )
        vendFlow.start(currentCell)
    }

    private fun onDispenseItemDone() {
        if (!dispensingInProgress) return
        val justDone = dispensingQueue.getOrNull(dispensingCursor)
        if (justDone != null) {
            interactionMonitor.appendBoth(
                "Dispensacion celda ${justDone.item.codigoCelda} completa | producto=${justDone.item.producto}"
            )
        }
        if (justDone?.driverZeroDelivered == true) {
            reportDispenseStateByIndex(
                index = dispensingCursor,
                tnEstado = 7,
                tcEstado = "ENTREGADO_CON_DRIVER_0000"
            )
        } else {
            reportDispenseStateByIndex(
                index = dispensingCursor,
                tnEstado = 4,
                tcEstado = "COMPLETADO"
            )
        }
        dispensingCursor++
        startNextDispenseItem()
    }

    private fun onDispenseError(message: String, errorCode: String = "") {
        interactionMonitor.appendBoth("Incidencia en dispensacion | code=$errorCode | message=$message")
        if (errorCode == "IO_TIMEOUT") {
            if (dispensingInProgress) {
                dispenseViewModel.showIoTimeout(message)
            }
            return
        }

        if (dispensingInProgress) {
            if (errorCode == "ANOMALO" || errorCode == "IO_TIMEOUT_CANCEL") {
                reportDispenseStateByIndex(
                    index = dispensingCursor,
                    tnEstado = 7,
                    tcEstado = "ANOMALO"
                )
            } else {
                reportDispenseStateByIndex(
                    index = dispensingCursor,
                    tnEstado = 5,
                    tcEstado = "FALLIDO"
                )
            }
        }
        dispensingInProgress = false
        runCatching { vendFlow.stop() }
        stopDispenseSuccessCountdown()
        showDispenseError(message, errorCode)
    }

    private fun onDispenseFinished() {
        dispensingInProgress = false
        runCatching { vendFlow.stop() }
        stopDispenseSuccessCountdown()
        interactionMonitor.appendBoth("Compra finalizada sin incidencias fatales")
        interactionMonitor.finalizeAndSave()

        if (clearCartOnDispenseFinish) {
            cartViewModel.clear()
        }

        showDispenseSuccessDialog()

        loadCatalog(machineId, authHeader)
    }

    private fun parseRuntimeDispenseError(raw: String): Pair<String, String> {
        val parts = raw.split("|", limit = 2)
        if (parts.size != 2) return "" to raw
        val code = parts[0].trim()
        val message = parts[1].trim()
        return when (code) {
            "ANOMALO", "DRIVER_0000", "DRIVER_TIMEOUT", "IO_TIMEOUT", "IO_TIMEOUT_CANCEL", "PRODUCT_CRUSHED", "PLATFORM_RECOVERY_COMMAND_FAILED", "PLATFORM_RECOVERY_TIMEOUT" -> code to message
            else -> "" to raw
        }
    }

    private fun reportDispenseStateByIndex(index: Int, tnEstado: Int, tcEstado: String) {
        val current = dispensingQueue.getOrNull(index) ?: return
        if (current.tnEstadoDispensacion == tnEstado) return
        current.tnEstadoDispensacion = tnEstado
        if (activeDispensePedidoId <= 0 || current.tnPedidoDetalle <= 0) return

        lifecycleScope.launch(Dispatchers.IO) {
            sendDispenseStatus(
                BackendDispenseStatusRequest(
                    orderId = activeDispensePedidoId,
                    orderDetailId = current.tnPedidoDetalle,
                    planogramCellId = current.item.planogramaCeldaId,
                    dispenseStatusId = tnEstado,
                    dispenseStatus = tcEstado
                )
            )
        }
    }

    private suspend fun sendDispenseStatus(request: BackendDispenseStatusRequest) {
        if (authHeader.isBlank()) return
        try {
            vendingBackendGateway.reportDispenseStatus(request)
        } catch (_: Exception) {
            // no bloquea UX por falla de reporte
        }
    }

    private fun showDispenseError(message: String, errorCode: String) {
        fun List<DispenseQueueItem>.toProductSummaries(): List<DispenseProductSummary> =
            groupingBy { it.item.producto.ifBlank { "Producto sin nombre" } to it.item.imagenUrl }
                .eachCount()
                .map { (entry, quantity) ->
                    DispenseProductSummary(
                        name = entry.first,
                        quantity = quantity,
                        imageSource = resolveDispenseImageSource(entry.second)
                    )
                }

        dispenseViewModel.showError(
            DispenseErrorUi(
                message = message.ifBlank { "No se pudo completar la dispensacion." },
                code = errorCode.takeIf { it.isNotBlank() },
                isProductCrushed = errorCode == "PRODUCT_CRUSHED",
                deliveredProducts = dispensingQueue.filter { it.tnEstadoDispensacion == 4 }.toProductSummaries(),
                pendingProducts = dispensingQueue.filter { it.tnEstadoDispensacion != 4 && it.tnEstadoDispensacion != 7 }.toProductSummaries(),
                reviewProducts = dispensingQueue.filter { it.tnEstadoDispensacion == 7 }.toProductSummaries()
            )
        )
    }

    private fun showDispenseIoProlongedWaitDialog() {
        dispenseViewModel.showProlongedWait(
            message = "Esto está tardando más de lo esperado. Por favor, presione el botón para un reintento manual.",
            manualRetryState = ManualRetryUiState.Available
        )
    }

    private fun showDispenseSuccessDialog() {
        dispenseViewModel.showSuccess("Dispensado completado correctamente.")
        startDispenseSuccessCountdown()
    }

    private fun startDispenseSuccessCountdown() {
        stopDispenseSuccessCountdown()
        var secondsLeft = (DISPENSE_SUCCESS_DIALOG_TIMEOUT_MS / 1000L).toInt().coerceAtLeast(0)

        fun scheduleNextTick() {
            dispenseViewModel.updateSuccessCountdown(secondsLeft)
            if (secondsLeft <= 0) {
                dispenseViewModel.hide()
                return
            }
            secondsLeft -= 1
            val runnable = Runnable { scheduleNextTick() }
            dispenseSuccessTimerRunnable = runnable
            dispenseSuccessTimerHandler.postDelayed(runnable, 1000L)
        }

        scheduleNextTick()
    }

    private fun stopDispenseSuccessCountdown() {
        dispenseSuccessTimerRunnable?.let { dispenseSuccessTimerHandler.removeCallbacks(it) }
        dispenseSuccessTimerRunnable = null
    }

    private fun showRetrieveDialogForCurrentItem() {
        val currentNumber = (dispensingCursor + 1).coerceAtMost(dispensingQueue.size)
        val total = dispensingQueue.size.coerceAtLeast(1)
        val currentProduct = dispensingQueue.getOrNull(dispensingCursor)?.item?.producto
            ?.takeIf { it.isNotBlank() }
            ?: "producto"
        val title = "Producto listo! $currentNumber de $total"
        val message = "Por favor, retira tu $currentProduct"

        val currentItem = dispensingQueue.getOrNull(dispensingCursor)?.item
        dispenseViewModel.showRetrieve(
            title = title,
            message = message,
            productName = currentItem?.producto.orEmpty(),
            productImageSource = resolveDispenseImageSource(currentItem?.imagenUrl.orEmpty())
        )
    }

    private fun requestManualPickupRetry() {
        val wasAlreadyRetrying = vendFlow.isManualDoorRetryRunning()
        val started = runCatching { vendFlow.requestManualDoorRetrySequence() }.getOrDefault(false)
        when {
            started -> dispenseViewModel.setManualRetryInProgress()
            wasAlreadyRetrying || vendFlow.isManualDoorRetryRunning() -> dispenseViewModel.setManualRetryAlreadyInProgress()
            else -> dispenseViewModel.setManualRetryUnableToStart()
        }
    }

    private fun requestPlatformRecovery() {
        val started = runCatching { vendFlow.requestPlatformRecoveryToBase() }.getOrDefault(false)
        if (started) {
            dispenseViewModel.showPlatformRecovering("Recuperando plataforma. Por favor espere...")
        } else {
            Toast.makeText(this, "No hay recuperacion pendiente.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun closeDispenseSuccessSurface() {
        stopDispenseSuccessCountdown()
        dispenseViewModel.hide()
    }

    private fun showDispenseErrorLogs() {
        showMonitoringViewerDialog(
            title = "Logs en vivo - incidencia",
            live = true,
            bitacora = false
        )
    }

    private fun showDispenseErrorBitacora() {
        showMonitoringViewerDialog(
            title = "Bitacora en vivo - incidencia",
            live = true,
            bitacora = true
        )
    }

    private fun saveDispenseErrorMonitoring() {
        val saved = interactionMonitor.finalizeAndSave()
        if (saved == null) {
            Toast.makeText(this, "No habia una sesion activa para guardar.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(
                this,
                "Guardado en: ${saved.logsFile.parentFile?.absolutePath.orEmpty()}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun resolveDispenseImageSource(imageSource: String): String? {
        return imageSource.takeIf(catalogImageCache::isLocalImagePath)
    }

    private fun showMonitoringViewerDialog(
        title: String,
        live: Boolean,
        bitacora: Boolean
    ) {
        monitorViewerDialog?.takeIf { it.isShowing }?.dismiss()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val tvTitle = TextView(this).apply {
            text = title
            setTextColor(Color.parseColor("#0B456F"))
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val scroll = android.widget.ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(420)
            ).apply { topMargin = dp(10) }
        }
        val tvContent = TextView(this).apply {
            setTextColor(Color.parseColor("#0A2239"))
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(Color.parseColor("#F3F7FB"))
        }
        scroll.addView(tvContent)
        container.addView(tvTitle)
        container.addView(scroll)

        val dialog = AlertDialog.Builder(this)
            .setView(container)
            .setPositiveButton("Cerrar", null)
            .create()

        fun updateContent() {
            val text = when {
                live && interactionMonitor.isActive() && bitacora -> interactionMonitor.getCurrentBitacoraText()
                live && interactionMonitor.isActive() -> interactionMonitor.getCurrentLogsText()
                bitacora -> interactionMonitor.getLastBitacoraText()
                else -> interactionMonitor.getLastLogsText()
            }
            tvContent.text = text.ifBlank { "Sin datos para mostrar." }
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }

        updateContent()
        if (live) {
            val runnable = object : Runnable {
                override fun run() {
                    if (monitorViewerDialog?.isShowing != true) return
                    updateContent()
                    inactivityHandler.postDelayed(this, 350L)
                }
            }
            monitorViewerRunnable = runnable
            inactivityHandler.post(runnable)
        }

        dialog.setOnDismissListener {
            monitorViewerDialog = null
            monitorViewerRunnable?.let { inactivityHandler.removeCallbacks(it) }
            monitorViewerRunnable = null
        }

        monitorViewerDialog = dialog
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.WHITE))
    }

    private fun formatPrice(value: Double): String = String.format(java.util.Locale.US, "%.2f", value)

    private fun mapCellCodeToPhysical(code: String): Int? {
        val clean = code.trim().uppercase()
        clean.toIntOrNull()?.let { direct ->
            if (direct in 10..68) return direct
        }

        val match = Regex("^([A-F])(\\d{1,2})$").find(clean) ?: return null
        val letter = match.groupValues[1][0]
        val column = match.groupValues[2].toIntOrNull() ?: return null
        if (column !in 1..9) return null

        val rowIndex = letter - 'A'
        if (rowIndex !in 0..5) return null

        return ((rowIndex + 1) * 10) + (column - 1)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun applyCatalogSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = Color.parseColor("#FFFFFF")
            window.navigationBarColor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Color.parseColor("#FFFFFF")
            } else {
                Color.parseColor("#3F546D")
            }
        }
        var flags = window.decorView.systemUiVisibility
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        window.decorView.systemUiVisibility = flags
    }

    private val idleIoPollRunnable = object : Runnable {
        override fun run() {
            if (!idleIoPollingActive) return
            try {
                if (
                    serial.isOpen() &&
                    ::vendFlow.isInitialized &&
                    !vendFlow.isRunning() &&
                    !vendFlow.isWaitingPickup() &&
                    !vendFlow.isWaitingPlatformRecovery()
                ) {
                    serial.sendHex(CommandSet.POLL_IO_STATUS, serialListener)
                }
            } catch (_: Exception) {
                // no bloquea UX
            } finally {
                idleIoHandler.postDelayed(this, IDLE_IO_POLL_MS)
            }
        }
    }

    companion object {
        private const val TAG = "KioskCatalogActivity"
        const val EXTRA_MACHINE_ID = "extra_machine_id"
        const val EXTRA_MACHINE_CODE = "extra_machine_code"
        const val EXTRA_MACHINE_LOCATION = "extra_machine_location"

        private const val DEFAULT_PORT = "/dev/ttyS1"
        private const val DEFAULT_BAUD = 9600
        private const val PRODUCT_DIALOG_TIMEOUT_MS = 60_000L
        private const val DISPENSE_SUCCESS_DIALOG_TIMEOUT_MS = 5_000L
        private const val PLANOGRAM_INACTIVITY_REFRESH_MS = 60_000L
        private const val IDLE_IO_POLL_MS = 750L
    }
}

private class CatalogViewModelFactory(
    private val vendingBackendGateway: HttpVendingBackendGateway,
    private val catalogImageCache: CatalogImageCache,
    private val authSessionManager: AuthSessionManager
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CatalogViewModel::class.java)) {
            return CatalogViewModel(
                vendingBackendGateway,
                catalogImageCache,
                authSessionManager
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

private class CartViewModelFactory(
    private val cartUseCase: CartUseCase
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CartViewModel::class.java)) {
            return CartViewModel(cartUseCase) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

private class PaymentViewModelFactory(
    private val vendingBackendGateway: HttpVendingBackendGateway,
    private val machineAuthGateway: MachineAuthGateway,
    private val authSessionManager: AuthSessionManager,
    private val machineId: Int
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PaymentViewModel::class.java)) {
            return PaymentViewModel(
                vendingBackendGateway,
                machineAuthGateway,
                authSessionManager,
                machineId
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

private data class PromoSlideUi(
    val url: String,
    val visualOrder: Int,
    val id: Int
)

private data class CeldaUi(
    val planogramaCeldaId: Int,
    val productoId: Int,
    val codigoCelda: String,
    val producto: String,
    val precio: Double,
    val stockDisponible: Int,
    val vendible: Boolean,
    val physicalCell: Int,
    val imagenUrl: String,
    val imagenUrlSecundaria: String
)

private data class PurchaseSelection(
    val item: CeldaUi,
    val quantity: Int
)

private data class DispenseQueueItem(
    val cell: Int,
    val item: CeldaUi,
    val tnPedidoDetalle: Int = 0,
    var tnEstadoDispensacion: Int = 0,
    var driverZeroDelivered: Boolean = false
)

private sealed interface CatalogResult {
    data class Success(
        val celdas: List<CeldaUi>,
        val promotions: List<PromoSlideUi>,
        val backgroundImageUrl: String
    ) : CatalogResult
    data class Error(val message: String, val unauthorized: Boolean = false) : CatalogResult
}

private sealed interface MachineAccessResult {
    data object Granted : MachineAccessResult
    data class Denied(val message: String) : MachineAccessResult
    data class Error(val message: String) : MachineAccessResult
}

