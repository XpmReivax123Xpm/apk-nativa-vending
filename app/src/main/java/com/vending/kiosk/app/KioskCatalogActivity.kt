package com.vending.kiosk.app

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.util.LruCache
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewFlipper
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.vending.kiosk.R
import com.vending.kiosk.app.kiosk.KioskPolicyManager
import com.vending.kiosk.integration.serial.runtime.SerialManager
import com.vending.kiosk.integration.serial.runtime.VendingFlowController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

class KioskCatalogActivity : AppCompatActivity() {

    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var tvStatus: TextView
    private lateinit var cartFabContainer: View
    private lateinit var tvCartBadge: TextView
    private lateinit var promoCarousel: View
    private lateinit var screenRootView: View
    private var tvPromoTitle: TextView? = null
    private var tvPromoSubtitle: TextView? = null
    private lateinit var contentContainer: LinearLayout

    private val authSessionManager by lazy { AuthSessionManager(this) }
    private val kioskPolicyManager by lazy { KioskPolicyManager(this) }

    private var useLegacyCarousel = false
    private val carouselHandler = Handler(Looper.getMainLooper())
    private val carouselIntervalMs = 5_000L
    private var carouselIndex = 0
    private var promoAdaptiveHeightApplied = false
    private val legacySlides = listOf(
        LegacySlide(R.drawable.bg_catalog_promo_1, "Promociones", "Espacio para ofertas y anuncios"),
        LegacySlide(R.drawable.bg_catalog_promo_2, "Nuevos productos", "Carrusel preparado para imagenes"),
        LegacySlide(R.drawable.bg_catalog_promo_3, "Avisos", "Descuentos, mantenimiento y novedades")
    )
    private var promotionalSlides: List<PromoSlideUi> = emptyList()

    private var machineId: Int = 0
    private var machineCode: String = ""
    private var authHeader: String = ""
    private var catalogItems: List<CeldaUi> = emptyList()
    private val cartItems = linkedMapOf<Int, CartLine>()
    private val imageCache by lazy {
        object : LruCache<String, android.graphics.Bitmap>(8 * 1024 * 1024) {
            override fun sizeOf(key: String, value: android.graphics.Bitmap): Int = value.byteCount
        }
    }
    private val imageTargetsByUrl = mutableMapOf<String, MutableList<ImageView>>()

    private val serial = SerialManager()
    private lateinit var vendFlow: VendingFlowController
    private var dispensingQueue: List<Int> = emptyList()
    private var dispensingCursor = 0
    private var dispensingInProgress = false
    private var clearCartOnDispenseFinish = false

    private var qrPollingJob: Job? = null

    private var dispenseDialog: AlertDialog? = null
    private var tvDispenseStatus: TextView? = null
    private var tvDispenseTitle: TextView? = null
    private var tvDispenseTimer: TextView? = null
    private var progressDispense: ProgressBar? = null
    private var btnDispenseClose: Button? = null
    private var dispenseSuccessCloseTimer: CountDownTimer? = null
    private var kioskLocked = false
    private var lockTaskStarted = false
    private val unlockHoldHandler = Handler(Looper.getMainLooper())
    private var unlockHoldTriggered = false
    private val inactivityHandler = Handler(Looper.getMainLooper())
    private var activeModalCount = 0
    private val inactivityRunnable = Runnable {
        if (activeModalCount > 0) {
            Log.d(TAG, "Inactivity refresh skipped: modal is open")
            scheduleInactivityRefresh()
            return@Runnable
        }
        if (machineId <= 0 || authHeader.isBlank()) return@Runnable
        if (cartItems.isNotEmpty()) {
            cartItems.clear()
            updateCartBadge()
            Toast.makeText(
                this,
                "Inactividad detectada. Carrito vaciado y planograma actualizado.",
                Toast.LENGTH_SHORT
            ).show()
        }
        loadCatalog(machineId, authHeader)
        scheduleInactivityRefresh()
    }

    private val carouselTicker = object : Runnable {
        override fun run() {
            try {
                if (!useLegacyCarousel || tvPromoTitle == null || tvPromoSubtitle == null) return
                val slideCount = getLegacySlideCount()
                if (slideCount <= 1) return
                showLegacySlide(carouselIndex % slideCount)
                carouselIndex++
                carouselHandler.postDelayed(this, carouselIntervalMs)
            } catch (_: Throwable) {
                carouselHandler.removeCallbacks(this)
            }
        }
    }

    private val serialListener = object : SerialManager.Listener {
        override fun onRx(data: ByteArray, size: Int) {
            if (::vendFlow.isInitialized) {
                vendFlow.onRx(data, size)
            }
        }

        override fun onError(e: Exception) {
            if (dispensingInProgress) {
                runOnUiThread {
                    onDispenseError("Error serial: ${e.message ?: "sin detalle"}")
                }
            }
        }

        override fun onStatus(msg: String) {
            if (dispensingInProgress && msg.startsWith("TX:")) {
                runOnUiThread {
                    tvDispenseStatus?.text = "Ejecutando pedido..."
                }
            }
        }
    }

    private val vendingUi = object : VendingFlowController.Ui {
        override fun onLog(msg: String) {
            // Se mantiene silencioso para no saturar UI en modo kiosk.
        }

        override fun onNeedRetrieve(msg: String) {
            runOnUiThread {
                if (dispensingInProgress) {
                    val current = (dispensingCursor + 1).coerceAtMost(dispensingQueue.size)
                    tvDispenseStatus?.text = " Por favor retira el producto. Preparando siguiente item ($current/${dispensingQueue.size})..."
                }
            }
        }

        override fun onDone() {
            runOnUiThread { onDispenseItemDone() }
        }

        override fun onError(msg: String) {
            runOnUiThread { onDispenseError(msg) }
        }

        override fun onStep(stepMsg: String) {
            // Sin salida visual por ahora.
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
        useLegacyCarousel = false

        tvTitle = findViewById(R.id.tvCatalogTitle)
        tvSubtitle = findViewById(R.id.tvCatalogSubtitle)
        tvStatus = findViewById(R.id.tvCatalogStatus)
        cartFabContainer = findViewById(R.id.cartFabContainer)
        tvCartBadge = findViewById(R.id.tvCartBadge)
        promoCarousel = findViewById(R.id.vfPromoCarousel)
        contentContainer = findViewById(R.id.llCatalogContainer)
        screenRootView = (findViewById<View>(android.R.id.content) as ViewGroup).getChildAt(0)
        useLegacyCarousel = promoCarousel !is ViewFlipper

        if (!useLegacyCarousel) {
            (promoCarousel as? ViewFlipper)?.apply {
                isAutoStart = false
                stopFlipping()
                flipInterval = carouselIntervalMs.toInt()
            }
        }

        setupDispenseRuntime()
        setupCartBadge()
        applyCarouselHeight()
        setupCarouselTouchControls()

        machineId = intent.getIntExtra(EXTRA_MACHINE_ID, 0)
        machineCode = intent.getStringExtra(EXTRA_MACHINE_CODE).orEmpty()
        val machineLocation = intent.getStringExtra(EXTRA_MACHINE_LOCATION).orEmpty()

        if (machineId <= 0) {
            throw IllegalStateException("Maquina invalida")
        }

        tvTitle.text = machineCode
        tvSubtitle.text = machineLocation
        setupUnlockGestureOnMachineTitle()
        enterKioskMode()

        authHeader = authSessionManager.getAuthorizationHeader().orEmpty()
        if (authHeader.isBlank()) {
            throw IllegalStateException("Sesion expirada. Inicia sesion nuevamente.")
        }

        loadCatalog(machineId, authHeader)
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveKioskUi()
        scheduleInactivityRefresh()
        if (kioskLocked && !lockTaskStarted) {
            Log.d(TAG, "onResume: kiosk locked but lock task not started. Retrying managed lock task.")
            attemptManagedLockTaskStart()
        }
        if (useLegacyCarousel && tvPromoTitle != null && tvPromoSubtitle != null) {
            carouselHandler.removeCallbacks(carouselTicker)
            carouselHandler.postDelayed(carouselTicker, carouselIntervalMs)
        } else {
            (promoCarousel as? ViewFlipper)?.startFlipping()
        }
    }

    override fun onPause() {
        unlockHoldHandler.removeCallbacksAndMessages(null)
        carouselHandler.removeCallbacks(carouselTicker)
        inactivityHandler.removeCallbacksAndMessages(null)
        (promoCarousel as? ViewFlipper)?.stopFlipping()
        super.onPause()
    }

    override fun onStop() {
        carouselHandler.removeCallbacks(carouselTicker)
        super.onStop()
    }

    override fun onDestroy() {
        qrPollingJob?.cancel()
        unlockHoldHandler.removeCallbacksAndMessages(null)
        carouselHandler.removeCallbacks(carouselTicker)
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
        vendFlow = VendingFlowController(serial, serialListener, vendingUi)
    }

    private fun setupCartBadge() {
        updateCartBadge()
        cartFabContainer.setOnClickListener { showCartDialog() }
    }

    override fun onBackPressed() {
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

    private fun enterKioskMode() {
        Log.d(TAG, "entering kiosk mode")
        kioskLocked = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersiveKioskUi()
        attemptManagedLockTaskStart()
        if (!lockTaskStarted) {
            Log.w(TAG, "fallback visual mode only: managed lock task not active")
        }
    }

    private fun exitKioskMode() {
        Log.d(TAG, "exiting kiosk mode")
        kioskLocked = false
        if (lockTaskStarted) {
            runCatching { stopLockTask() }
                .onSuccess { Log.d(TAG, "stopLockTask success") }
                .onFailure { Log.w(TAG, "stopLockTask failed: ${it.message}") }
            lockTaskStarted = false
        }
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        Toast.makeText(this, "Modo kiosk desbloqueado", Toast.LENGTH_SHORT).show()
    }

    private fun attemptManagedLockTaskStart() {
        if (lockTaskStarted) return

        val isDeviceOwner = kioskPolicyManager.isDeviceOwner()
        Log.d(TAG, "device owner status: $isDeviceOwner")
        if (!isDeviceOwner) {
            Log.w(TAG, "app is not Device Owner")
        } else {
            val allowlisted = kioskPolicyManager.allowCurrentPackageForLockTask()
            Log.d(TAG, "allowlisting result: $allowlisted")
        }

        val permitted = kioskPolicyManager.isCurrentPackageLockTaskPermitted()
        Log.d(TAG, "lock task permitted: $permitted")
        if (!permitted) {
            Log.w(TAG, "package is not permitted for lock task")
            return
        }

        runCatching { startLockTask() }
            .onSuccess {
                lockTaskStarted = true
                Log.d(TAG, "startLockTask success")
            }
            .onFailure {
                lockTaskStarted = false
                Log.w(TAG, "startLockTask failed: ${it.message}")
            }
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

    private fun loadCatalog(machineId: Int, authHeader: String) {
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = "Cargando catalogo..."
        contentContainer.removeAllViews()

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { fetchCatalog(machineId, authHeader) }

            when (result) {
                is CatalogResult.Success -> {
                    catalogItems = result.celdas
                    promotionalSlides = result.promotions
                    applyUiBackground(result.backgroundImageUrl)
                    renderPromotionalCarousel(result.promotions)
                    if (result.celdas.isEmpty()) {
                        tvStatus.visibility = View.VISIBLE
                        tvStatus.text = "Sin productos disponibles (0 celdas recibidas)"
                    } else {
                        tvStatus.visibility = View.GONE
                        syncCartWithCatalog(result.celdas)
                        renderCatalog(result.celdas)
                    }
                }

                is CatalogResult.Error -> {
                    tvStatus.visibility = View.VISIBLE
                    tvStatus.text = result.message
                    Toast.makeText(this@KioskCatalogActivity, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun fetchCatalog(machineId: Int, authHeader: String): CatalogResult {
        val endpoint = "https://boxipagobackend.pagofacil.com.bo/api/maquinas/$machineId/planograma"
        var connection: HttpURLConnection? = null

        return try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 12_000
                setRequestProperty("Authorization", authHeader)
                setRequestProperty("Accept", "application/json")
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
                return CatalogResult.Error("Respuesta vacia del backend (HTTP $statusCode)")
            }

            val json = JSONObject(rawBody)
            val error = json.optInt("error", -1)
            val status = json.optInt("status", 0)
            val message = json.optString("message", "Error consultando catalogo")

            if (statusCode !in 200..299 || error != 0 || status != 1) {
                return CatalogResult.Error("$message (HTTP $statusCode)")
            }

            val values = json.optJSONObject("values") ?: JSONObject()
            val celdasJson =
                values.optJSONArray("celdas")
                    ?: values.optJSONArray("celdasPlanograma")
                    ?: values.optJSONObject("planograma")?.optJSONArray("celdas")

            if (celdasJson == null) {
                return CatalogResult.Error("Respuesta sin celdas en values")
            }

            val celdas = parseCeldas(celdasJson)
            val promotions = parsePromotions(values)
            val backgroundImageUrl = parseUiBackgroundUrl(values)
            val ordenadas = celdas.sortedWith(
                compareBy<CeldaUi> { parseCellCode(it.codigoCelda).first }
                    .thenBy { parseCellCode(it.codigoCelda).second }
                    .thenBy { it.codigoCelda }
            )
            CatalogResult.Success(ordenadas, promotions, backgroundImageUrl)
        } catch (ex: Exception) {
            CatalogResult.Error("Fallo de conexion: ${ex.message ?: "sin detalle"}")
        } finally {
            connection?.disconnect()
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
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

    private fun parsePromotions(values: JSONObject): List<PromoSlideUi> {
        val source =
            values.optJSONArray("taPresentacionArchivos")
                ?: values.optJSONObject("presentacion")?.optJSONArray("taPresentacionArchivos")
                ?: values.optJSONObject("planograma")?.optJSONArray("taPresentacionArchivos")
                ?: values.optJSONObject("taPresentacion")?.optJSONArray("taPresentacionArchivos")
                ?: return emptyList()

        val slides = mutableListOf<PromoSlideUi>()
        for (index in 0 until source.length()) {
            val item = source.optJSONObject(index) ?: continue
            val status = item.optInt("tnEstado", 0)
            if (status != 1) continue
            val usageType = item.optString("tcUsoTipo", "").trim()
            if (usageType.isNotBlank() && !usageType.equals("PROMOCIONAL", ignoreCase = true)) continue
            val mimeType = item.optString("tcMimeType", "").trim()
            if (mimeType.isNotBlank() && !mimeType.startsWith("image/", ignoreCase = true)) continue
            val url = item.optString("tcUrl", "").trim()
            if (url.isBlank()) continue

            slides += PromoSlideUi(
                url = url,
                visualOrder = item.optInt("tnOrdenVisual", Int.MAX_VALUE),
                id = item.optInt("tnPresentacionArchivo", index)
            )
        }

        return slides.sortedWith(compareBy<PromoSlideUi> { it.visualOrder }.thenBy { it.id })
    }

    private fun parseUiBackgroundUrl(values: JSONObject): String {
        val source =
            values.optJSONObject("taFondoUiPrincipal")
                ?: values.optJSONObject("presentacion")?.optJSONObject("taFondoUiPrincipal")
                ?: values.optJSONObject("planograma")?.optJSONObject("taFondoUiPrincipal")
                ?: return ""

        val status = source.optInt("tnEstado", 0)
        if (status != 1) return ""
        val usageType = source.optString("tcUsoTipo", "").trim()
        if (usageType.isNotBlank() && !usageType.equals("FONDO_PLANOGRAMA", ignoreCase = true)) return ""
        val mimeType = source.optString("tcMimeType", "").trim()
        if (mimeType.isNotBlank() && !mimeType.startsWith("image/", ignoreCase = true)) return ""

        return source.optString("tcUrl", "").trim()
    }

    private fun applyUiBackground(imageUrl: String) {
        if (imageUrl.isBlank()) {
            screenRootView.setBackgroundResource(R.drawable.bg_kiosk_catalog_screen_hot)
            return
        }

        val tagValue = "ui-bg:$imageUrl"
        screenRootView.tag = tagValue
        val cached = imageCache.get(imageUrl)
        if (cached != null) {
            screenRootView.background = BitmapDrawable(resources, cached)
            return
        }

        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { downloadBitmap(imageUrl, 1440) }
            if (bitmap != null) {
                imageCache.put(imageUrl, bitmap)
            }
            if (screenRootView.tag == tagValue && bitmap != null) {
                screenRootView.background = BitmapDrawable(resources, bitmap)
            } else if (screenRootView.tag == tagValue) {
                screenRootView.setBackgroundResource(R.drawable.bg_kiosk_catalog_screen_hot)
            }
        }
    }

    private fun renderPromotionalCarousel(promotions: List<PromoSlideUi>) {
        promoAdaptiveHeightApplied = false
        if (useLegacyCarousel) {
            if (promotions.isNotEmpty()) {
                carouselIndex = 0
                showLegacySlide(0)
            } else {
                showLegacySlide(carouselIndex % legacySlides.size)
            }
            return
        }

        val flipper = promoCarousel as? ViewFlipper ?: return
        flipper.stopFlipping()
        flipper.removeAllViews()

        if (promotions.isEmpty()) {
            inflateDefaultViewFlipperSlides(flipper)
        } else {
            promotions.forEach { promo ->
                val slide = FrameLayout(this).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    background = ColorDrawable(Color.parseColor("#DCE7F3"))
                }
                val image = ImageView(this).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                slide.addView(image)
                flipper.addView(slide)
                loadPromoImage(promo.url, image)
            }
        }

        flipper.displayedChild = 0
        if (flipper.childCount > 1) {
            flipper.startFlipping()
        }
    }

    private fun inflateDefaultViewFlipperSlides(flipper: ViewFlipper) {
        val defaults = listOf(
            Triple(R.drawable.bg_catalog_promo_1, "Promo del dia", "Espacio para ofertas y anuncios"),
            Triple(R.drawable.bg_catalog_promo_2, "Nuevos productos", "Carrusel preparado para imagenes"),
            Triple(R.drawable.bg_catalog_promo_3, "Avisos", "Descuentos, mantenimiento y novedades")
        )
        defaults.forEach { (backgroundRes, title, subtitle) ->
            val slide = LinearLayout(this).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundResource(backgroundRes)
                gravity = android.view.Gravity.BOTTOM or android.view.Gravity.START
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(18), dp(18), dp(18))
            }
            val titleView = TextView(this).apply {
                text = title
                setTextColor(Color.parseColor("#0965AF"))
                textSize = 28f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            val subtitleView = TextView(this).apply {
                text = subtitle
                setTextColor(Color.parseColor("#0965AF"))
                textSize = 18f
                setPadding(0, dp(4), 0, 0)
            }
            slide.addView(titleView)
            slide.addView(subtitleView)
            flipper.addView(slide)
        }
    }

    private fun parseCeldas(celdasJson: JSONArray): List<CeldaUi> {
        val celdas = mutableListOf<CeldaUi>()

        for (i in 0 until celdasJson.length()) {
            val celda = celdasJson.optJSONObject(i) ?: continue
            val producto = celda.optJSONObject("taProducto") ?: celda.optJSONObject("producto")
            val inventario = celda.optJSONObject("taInventario") ?: celda.optJSONObject("inventario")

            val esActiva = celda.optInt("tnEsActiva", 0) == 1
            val estadoCeldaActiva = celda.optInt("tnEstado", 0) == 1
            val productoActivo = producto?.optInt("tnEstado", 0) == 1
            val inventarioCantidad = inventario?.optInt("tnCantidad", 0) ?: 0
            val reservadas = inventario?.optInt("tnCantidadReservada", 0) ?: 0
            val stockDisponible = (inventarioCantidad - reservadas).coerceAtLeast(0)

            val nombreProducto = producto?.optString("tcNombre", "")?.trim().orEmpty()
            val precio = producto?.optDouble("tnPrecio", 0.0) ?: 0.0
            val imagenUrl = producto?.optString("tcImagenUrlPrincipal", "")?.trim().orEmpty()

            val codigo = celda.optString("tcCodigo", "--")
            val planogramaCeldaId = when {
                celda.has("tnPlanogramaCelda") -> celda.optInt("tnPlanogramaCelda", 0)
                celda.has("tnCelda") -> celda.optInt("tnCelda", 0)
                else -> 0
            }
            val productoId = when {
                producto?.has("tnProducto") == true -> producto.optInt("tnProducto", 0)
                celda.has("tnProducto") -> celda.optInt("tnProducto", 0)
                else -> 0
            }

            val vendible = esActiva && estadoCeldaActiva && producto != null && productoActivo
            val physicalCell = mapCellCodeToPhysical(codigo) ?: 0

            celdas += CeldaUi(
                planogramaCeldaId = planogramaCeldaId,
                productoId = productoId,
                codigoCelda = codigo,
                producto = if (nombreProducto.isBlank()) "Sin producto" else nombreProducto,
                precio = precio,
                stockDisponible = stockDisponible,
                vendible = vendible,
                physicalCell = physicalCell,
                imagenUrl = imagenUrl
            )
        }

        return celdas
    }

    private fun renderCatalog(celdas: List<CeldaUi>) {
        runCatching {
            val visibles = celdas.filter { isCellSellable(it) }
            if (visibles.isEmpty()) {
                tvStatus.visibility = View.VISIBLE
                tvStatus.text = "Sin productos disponibles para venta"
                contentContainer.removeAllViews()
                return
            }

            val columns = 3
            val rows = 6
            val itemsPerPage = columns * rows
            val pageWidth = resources.displayMetrics.widthPixels - dp(24)

            visibles.chunked(itemsPerPage).forEachIndexed { pageIndex, pageItems ->
                val page = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        pageWidth,
                        LinearLayout.LayoutParams.MATCH_PARENT
                    ).also {
                        if (pageIndex > 0) it.leftMargin = dp(10)
                    }
                }

                for (rowIndex in 0 until rows) {
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            0,
                            1f
                        ).also { it.bottomMargin = if (rowIndex == rows - 1) 0 else dp(8) }
                    }

                    for (columnIndex in 0 until columns) {
                        val cellIndex = rowIndex * columns + columnIndex
                        if (cellIndex < pageItems.size) {
                            val item = pageItems[cellIndex]
                            val card = layoutInflater.inflate(R.layout.item_catalog_cell, row, false)
                            card.findViewById<TextView>(R.id.tvCellCode).text = item.codigoCelda
                            card.findViewById<TextView>(R.id.tvCellProduct).text = item.producto
                            card.findViewById<TextView>(R.id.tvCellPrice).text =
                                if (item.precio > 0.0) "Bs ${formatPrice(item.precio)}" else "Sin precio"
                            card.findViewById<TextView>(R.id.tvCellStock).text = "Stock: ${item.stockDisponible}"
                            card.findViewById<LinearLayout>(R.id.llCellInfo).visibility = View.GONE
                            val ivProduct = card.findViewById<ImageView>(R.id.ivCellProductImage)
                            loadProductImage(item.imagenUrl, ivProduct)

                            val available = isCellSellable(item)
                            card.findViewById<TextView>(R.id.tvCellState).apply {
                                if (available) {
                                    visibility = View.GONE
                                } else {
                                    visibility = View.VISIBLE
                                    text = "No disponible"
                                    setBackgroundResource(R.drawable.bg_catalog_unavailable_badge)
                                    setTextColor(Color.parseColor("#F28E1B"))
                                }
                            }

                            card.alpha = if (available) 1f else 0.78f
                            card.setOnClickListener {
                                if (!available) {
                                    Toast.makeText(this, "Celda no disponible", Toast.LENGTH_SHORT).show()
                                } else {
                                    showProductDialog(item)
                                }
                            }

                            val margin = dp(4)
                            card.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                                if (columnIndex == 0) {
                                    setMargins(0, 0, margin, 0)
                                } else if (columnIndex == columns - 1) {
                                    setMargins(margin, 0, 0, 0)
                                } else {
                                    setMargins(margin, 0, margin, 0)
                                }
                            }
                            row.addView(card)
                        } else {
                            val spacer = View(this).apply {
                                layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
                            }
                            row.addView(spacer)
                        }
                    }
                    page.addView(row)
                }
                contentContainer.addView(page)
            }
        }.onFailure { error ->
            tvStatus.visibility = View.VISIBLE
            tvStatus.text = "Error de render: ${error.message ?: "sin detalle"}"
        }
    }

    private fun showProductDialog(item: CeldaUi) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_product_detail, null)
        val tvCode = view.findViewById<TextView>(R.id.tvDialogProductCode)
        val tvName = view.findViewById<TextView>(R.id.tvDialogProductName)
        val tvPrice = view.findViewById<TextView>(R.id.tvDialogProductPrice)
        val tvStock = view.findViewById<TextView>(R.id.tvDialogProductStock)
        val tvTotal = view.findViewById<TextView>(R.id.tvDialogProductTotal)
        val tvQty = view.findViewById<TextView>(R.id.tvDialogQty)
        val btnMinus = view.findViewById<ImageButton>(R.id.btnQtyMinus)
        val btnPlus = view.findViewById<ImageButton>(R.id.btnQtyPlus)
        val btnAddCart = view.findViewById<Button>(R.id.btnAddCart)
        val btnBuyNow = view.findViewById<Button>(R.id.btnBuyNow)
        val btnClose = view.findViewById<TextView>(R.id.btnProductDialogClose)
        val tvTimer = view.findViewById<TextView>(R.id.tvProductDialogTimer)
        val ivPreview = view.findViewById<ImageView>(R.id.ivProductPreview)

        // Fuerza visual en OEMs que pisan estilos de layout.
        tvTimer.setBackgroundColor(Color.TRANSPARENT)
        tvTimer.setTextColor(Color.WHITE)
        btnClose.text = "X"
        btnClose.setTextColor(Color.WHITE)

        tvCode.text = "Casilla ${item.codigoCelda}"
        tvName.text = item.producto
        tvPrice.text = "Precio unitario: ${if (item.precio > 0) "Bs ${formatPrice(item.precio)}" else "Sin precio"}"
        tvStock.text = "Stock disponible: ${item.stockDisponible}"
        loadProductImage(item.imagenUrl, ivPreview)

        var qty = 1
        tvQty.text = qty.toString()
        tvTotal.text = "Bs ${formatPrice(item.precio * qty)}"

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()
        dialog.setCanceledOnTouchOutside(false)

        var autoCloseTimer: CountDownTimer? = null
        fun resetAutoCloseTimer() {
            autoCloseTimer?.cancel()
            autoCloseTimer = object : CountDownTimer(PRODUCT_DIALOG_TIMEOUT_MS, 1000L) {
                override fun onTick(millisUntilFinished: Long) {
                    val seconds = ((millisUntilFinished + 999L) / 1000L).coerceAtLeast(0L)
                    tvTimer.text = "${seconds}s"
                }

                override fun onFinish() {
                    tvTimer.text = "0s"
                    if (dialog.isShowing) {
                        dialog.dismiss()
                    }
                    if (machineId > 0 && authHeader.isNotBlank()) {
                        loadCatalog(machineId, authHeader)
                    }
                }
            }.start()
        }

        view.setOnTouchListener { _, _ ->
            resetAutoCloseTimer()
            false
        }

        btnMinus.setOnClickListener {
            resetAutoCloseTimer()
            if (qty > 1) {
                qty--
                tvQty.text = qty.toString()
                tvTotal.text = "Bs ${formatPrice(item.precio * qty)}"
            }
        }

        btnPlus.setOnClickListener {
            resetAutoCloseTimer()
            if (qty < item.stockDisponible) {
                qty++
                tvQty.text = qty.toString()
                tvTotal.text = "Bs ${formatPrice(item.precio * qty)}"
            } else {
                Toast.makeText(this, "No puedes superar el stock", Toast.LENGTH_SHORT).show()
            }
        }

        btnClose.setOnClickListener { dialog.dismiss() }

        btnAddCart.setOnClickListener {
            resetAutoCloseTimer()
            addToCart(item, qty)
            dialog.dismiss()
        }

        btnBuyNow.setOnClickListener {
            resetAutoCloseTimer()
            dialog.dismiss()
            openCheckoutDialog(
                selections = listOf(PurchaseSelection(item, qty)),
                fromCart = false,
                paymentMethod = PaymentMethodOption.QR_BCP
            )
        }

        onModalShown()
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        resetAutoCloseTimer()
        dialog.setOnDismissListener {
            autoCloseTimer?.cancel()
            onModalDismissed()
        }
    }

    private fun addToCart(item: CeldaUi, qtyToAdd: Int) {
        val current = cartItems[item.planogramaCeldaId]
        val currentQty = current?.quantity ?: 0
        if (currentQty >= item.stockDisponible) {
            Toast.makeText(this, "Stock maximo alcanzado en carrito", Toast.LENGTH_SHORT).show()
            return
        }

        val newQty = (currentQty + qtyToAdd).coerceAtMost(item.stockDisponible)
        if (current == null) {
            cartItems[item.planogramaCeldaId] = CartLine(item, newQty)
        } else {
            current.item = item
            current.quantity = newQty
        }
        updateCartBadge()
        Toast.makeText(this, "Agregado al carrito", Toast.LENGTH_SHORT).show()
    }

    private fun showCartDialog() {
        if (cartItems.isEmpty()) {
            Toast.makeText(this, "Carrito vacio", Toast.LENGTH_SHORT).show()
            return
        }
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_cart, null)
        val itemsContainer = view.findViewById<LinearLayout>(R.id.llCartItemsContainer)
        val tvTotal = view.findViewById<TextView>(R.id.tvCartDialogTotal)
        val tvTimer = view.findViewById<TextView>(R.id.tvCartDialogTimer)
        val btnClear = view.findViewById<Button>(R.id.btnCartClear)
        val btnClose = view.findViewById<Button>(R.id.btnCartClose)
        val btnBuy = view.findViewById<Button>(R.id.btnCartBuy)

        // Fuerza visual para OEMs que pisan estilos en dialogos.
        tvTimer.setBackgroundColor(Color.TRANSPARENT)
        tvTimer.setTextColor(Color.WHITE)
        tvTimer.textSize = 20f
        tvTimer.setShadowLayer(2f, 0f, 1f, Color.parseColor("#80000000"))

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(true)
            .create()

        var autoCloseTimer: CountDownTimer? = null
        fun resetAutoCloseTimer() {
            autoCloseTimer?.cancel()
            autoCloseTimer = object : CountDownTimer(PRODUCT_DIALOG_TIMEOUT_MS, 1000L) {
                override fun onTick(millisUntilFinished: Long) {
                    val seconds = ((millisUntilFinished + 999L) / 1000L).coerceAtLeast(0L)
                    tvTimer.text = "${seconds}s"
                }

                override fun onFinish() {
                    tvTimer.text = "0s"
                    if (dialog.isShowing) {
                        dialog.dismiss()
                    }
                    if (machineId > 0 && authHeader.isNotBlank()) {
                        loadCatalog(machineId, authHeader)
                    }
                }
            }.start()
        }

        view.setOnTouchListener { _, _ ->
            resetAutoCloseTimer()
            false
        }

        fun bindCartUi() {
            if (cartItems.isEmpty()) {
                dialog.dismiss()
                Toast.makeText(this, "Carrito vacio", Toast.LENGTH_SHORT).show()
                return
            }

            itemsContainer.removeAllViews()
            cartItems.values.forEach { line ->
                val itemView = LayoutInflater.from(this).inflate(R.layout.item_cart_line, itemsContainer, false)
                val tvName = itemView.findViewById<TextView>(R.id.tvCartItemName)
                val tvPrice = itemView.findViewById<TextView>(R.id.tvCartItemPrice)
                val tvQty = itemView.findViewById<TextView>(R.id.tvCartQty)
                val btnMinus = itemView.findViewById<ImageButton>(R.id.btnCartQtyMinus)
                val btnPlus = itemView.findViewById<ImageButton>(R.id.btnCartQtyPlus)
                val ivPreview = itemView.findViewById<ImageView>(R.id.ivCartItemPreview)

                tvName.text = "${line.item.codigoCelda} - ${line.item.producto}"
                tvPrice.text = "Unitario: ${if (line.item.precio > 0) "Bs ${formatPrice(line.item.precio)}" else "Sin precio"}"
                tvQty.text = line.quantity.toString()
                loadProductImage(line.item.imagenUrl, ivPreview)

                btnMinus.setOnClickListener {
                    resetAutoCloseTimer()
                    if (line.quantity > 1) {
                        line.quantity--
                    } else {
                        cartItems.remove(line.item.planogramaCeldaId)
                    }
                    updateCartBadge()
                    bindCartUi()
                }

                btnPlus.setOnClickListener {
                    resetAutoCloseTimer()
                    if (line.quantity < line.item.stockDisponible) {
                        line.quantity++
                        updateCartBadge()
                        bindCartUi()
                    } else {
                        Toast.makeText(this, "No puedes superar el stock", Toast.LENGTH_SHORT).show()
                    }
                }

                itemsContainer.addView(itemView)
            }

            val total = cartItems.values.sumOf { it.item.precio * it.quantity }
            tvTotal.text = "Total: Bs ${formatPrice(total)}"
        }

        btnClear.setOnClickListener {
            resetAutoCloseTimer()
            cartItems.clear()
            updateCartBadge()
            bindCartUi()
        }
        btnClose.setOnClickListener {
            resetAutoCloseTimer()
            dialog.dismiss()
        }
        btnBuy.setOnClickListener {
            resetAutoCloseTimer()
            val selections = cartItems.values.map { PurchaseSelection(it.item, it.quantity) }
            dialog.dismiss()
            openCheckoutDialog(
                selections = selections,
                fromCart = true,
                paymentMethod = PaymentMethodOption.QR_BCP
            )
        }

        onModalShown()
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.94f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        resetAutoCloseTimer()
        dialog.setOnDismissListener {
            autoCloseTimer?.cancel()
            onModalDismissed()
        }
        bindCartUi()
    }

    private fun openPaymentMethodDialog(selections: List<PurchaseSelection>, fromCart: Boolean) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_payment_method, null)
        val rgMethods = view.findViewById<RadioGroup>(R.id.rgPaymentMethods)
        val tvError = view.findViewById<TextView>(R.id.tvPaymentMethodError)
        val tvTimer = view.findViewById<TextView>(R.id.tvPaymentMethodTimer)
        val btnCancel = view.findViewById<Button>(R.id.btnPaymentMethodCancel)
        val btnContinue = view.findViewById<Button>(R.id.btnPaymentMethodContinue)

        // Fuerza visual para OEMs que pisan estilos en dialogos.
        tvTimer.setBackgroundColor(Color.TRANSPARENT)
        tvTimer.setTextColor(Color.WHITE)
        tvTimer.textSize = 20f
        tvTimer.setShadowLayer(2f, 0f, 1f, Color.parseColor("#80000000"))

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(true)
            .create()

        var autoCloseTimer: CountDownTimer? = null
        fun resetAutoCloseTimer() {
            autoCloseTimer?.cancel()
            autoCloseTimer = object : CountDownTimer(PRODUCT_DIALOG_TIMEOUT_MS, 1000L) {
                override fun onTick(millisUntilFinished: Long) {
                    val seconds = ((millisUntilFinished + 999L) / 1000L).coerceAtLeast(0L)
                    tvTimer.text = "${seconds}s"
                }

                override fun onFinish() {
                    tvTimer.text = "0s"
                    if (dialog.isShowing) {
                        dialog.dismiss()
                    }
                    if (machineId > 0 && authHeader.isNotBlank()) {
                        loadCatalog(machineId, authHeader)
                    }
                }
            }.start()
        }

        view.setOnTouchListener { _, _ ->
            resetAutoCloseTimer()
            false
        }

        rgMethods.setOnCheckedChangeListener { _, _ ->
            tvError.visibility = View.GONE
            resetAutoCloseTimer()
        }

        btnCancel.setOnClickListener {
            resetAutoCloseTimer()
            dialog.dismiss()
        }
        btnContinue.setOnClickListener {
            resetAutoCloseTimer()
            val checked = rgMethods.checkedRadioButtonId
            if (checked == View.NO_ID) {
                tvError.visibility = View.VISIBLE
                tvError.text = "Selecciona un metodo de pago"
                return@setOnClickListener
            }

            val method = PaymentMethodOption.QR_BCP
            dialog.dismiss()
            openCheckoutDialog(selections, fromCart, method)
        }

        onModalShown()
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        resetAutoCloseTimer()
        dialog.setOnDismissListener {
            autoCloseTimer?.cancel()
            onModalDismissed()
        }
    }

    private fun openCheckoutDialog(
        selections: List<PurchaseSelection>,
        fromCart: Boolean,
        paymentMethod: PaymentMethodOption
    ) {
        if (selections.isEmpty()) return

        val view = LayoutInflater.from(this).inflate(R.layout.dialog_checkout_qr, null)
        val tvSummary = view.findViewById<TextView>(R.id.tvCheckoutSummary)
        val tvMethod = view.findViewById<TextView>(R.id.tvCheckoutMethod)
        val etName = view.findViewById<EditText>(R.id.etCheckoutName)
        val etPhone = view.findViewById<EditText>(R.id.etCheckoutPhone)
        val etCi = view.findViewById<EditText>(R.id.etCheckoutCi)
        val tvError = view.findViewById<TextView>(R.id.tvCheckoutError)
        val tvTimer = view.findViewById<TextView>(R.id.tvCheckoutDialogTimer)
        val progress = view.findViewById<ProgressBar>(R.id.progressCheckout)
        val btnCancel = view.findViewById<Button>(R.id.btnCheckoutCancel)
        val btnGenerate = view.findViewById<Button>(R.id.btnCheckoutGenerate)

        val lines = selections.joinToString("\n") {
            "${it.item.codigoCelda} - ${it.item.producto} x${it.quantity}"
        }
        val total = selections.sumOf { it.item.precio * it.quantity }

        tvSummary.text = "$lines\n\nTotal: Bs ${formatPrice(total)}"
        tvMethod.text = "Metodo de pago: ${paymentMethod.label}"

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()
        dialog.setCanceledOnTouchOutside(false)
        etName.visibility = View.GONE
        etPhone.visibility = View.GONE
        etCi.visibility = View.GONE

        // Fuerza visual para OEMs que sobreescriben estilos en dialogos.
        tvTimer.setBackgroundColor(Color.TRANSPARENT)
        tvTimer.setTextColor(Color.WHITE)
        tvTimer.textSize = 20f
        tvTimer.setShadowLayer(2f, 0f, 1f, Color.parseColor("#80000000"))

        var autoCloseTimer: CountDownTimer? = null
        fun resetAutoCloseTimer() {
            autoCloseTimer?.cancel()
            autoCloseTimer = object : CountDownTimer(PRODUCT_DIALOG_TIMEOUT_MS, 1000L) {
                override fun onTick(millisUntilFinished: Long) {
                    val seconds = ((millisUntilFinished + 999L) / 1000L).coerceAtLeast(0L)
                    tvTimer.text = "${seconds}s"
                }

                override fun onFinish() {
                    tvTimer.text = "0s"
                    if (dialog.isShowing) {
                        dialog.dismiss()
                    }
                    if (machineId > 0 && authHeader.isNotBlank()) {
                        loadCatalog(machineId, authHeader)
                    }
                }
            }.start()
        }

        view.setOnTouchListener { _, _ ->
            resetAutoCloseTimer()
            false
        }

        btnCancel.setOnClickListener {
            resetAutoCloseTimer()
            dialog.dismiss()
        }
        btnGenerate.setOnClickListener {
            resetAutoCloseTimer()
            setCheckoutLoading(
                loading = true,
                progress = progress,
                btnGenerate = btnGenerate,
                btnCancel = btnCancel,
                etName = etName,
                etPhone = etPhone,
                etCi = etCi
            )
            tvError.visibility = View.GONE
            val generatingDialog = AlertDialog.Builder(this@KioskCatalogActivity)
                .setView(LayoutInflater.from(this@KioskCatalogActivity).inflate(R.layout.dialog_generating_qr, null))
                .setCancelable(false)
                .create().apply {
                    setCanceledOnTouchOutside(false)
                    window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    setOnDismissListener {
                        onModalDismissed()
                    }
                    onModalShown()
                    show()
                }

            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    createOrderAndGenerateQr(
                        machineId = machineId,
                        authHeader = authHeader,
                        paymentMethodId = paymentMethod.id,
                        customerName = DEFAULT_CUSTOMER_NAME,
                        customerPhone = DEFAULT_CUSTOMER_PHONE,
                        customerCi = DEFAULT_CUSTOMER_CI_NIT,
                        items = selections
                    )
                }
                generatingDialog.dismiss()

                setCheckoutLoading(
                    loading = false,
                    progress = progress,
                    btnGenerate = btnGenerate,
                    btnCancel = btnCancel,
                    etName = etName,
                    etPhone = etPhone,
                    etCi = etCi
                )

                when (result) {
                    is QrGenerationResult.Success -> {
                        dialog.dismiss()
                        openQrDialog(result, selections, fromCart)
                    }

                    is QrGenerationResult.Error -> {
                        tvError.visibility = View.VISIBLE
                        tvError.text = result.message
                    }
                }
            }
        }

        onModalShown()
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        resetAutoCloseTimer()
        dialog.setOnDismissListener {
            autoCloseTimer?.cancel()
            onModalDismissed()
        }
    }

    private fun setCheckoutLoading(
        loading: Boolean,
        progress: ProgressBar,
        btnGenerate: Button,
        btnCancel: Button,
        etName: EditText,
        etPhone: EditText,
        etCi: EditText
    ) {
        progress.visibility = if (loading) View.VISIBLE else View.GONE
        btnGenerate.isEnabled = !loading
        btnCancel.isEnabled = !loading
        etName.isEnabled = !loading
        etPhone.isEnabled = !loading
        etCi.isEnabled = !loading
    }

    private fun createOrderAndGenerateQr(
        machineId: Int,
        authHeader: String,
        paymentMethodId: Int,
        customerName: String,
        customerPhone: String,
        customerCi: String,
        items: List<PurchaseSelection>
    ): QrGenerationResult {
        val endpoint = "https://boxipagobackend.pagofacil.com.bo/api/pedido/crear-y-generar-qr"
        var connection: HttpURLConnection? = null

        return try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 14_000
                readTimeout = 14_000
                doOutput = true
                setRequestProperty("Authorization", authHeader)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }

            val itemsJson = JSONArray()
            items.forEach { selection ->
                itemsJson.put(
                    JSONObject().apply {
                        put("tnPlanogramaCelda", selection.item.planogramaCeldaId)
                        put("tnProducto", selection.item.productoId)
                        put("tnCantidad", selection.quantity)
                    }
                )
            }

            val payload = JSONObject().apply {
                put("tnMaquina", machineId)
                put("tcNombreCliente", customerName)
                put("tcTelefonoCliente", customerPhone)
                put("tcNITCliente", customerCi)
                put("tnPaymentMethodId", paymentMethodId)
                put("taItems", itemsJson)
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
                return QrGenerationResult.Error("Respuesta vacia al generar QR (HTTP $statusCode)")
            }

            val json = JSONObject(rawBody)
            val backendError = json.opt("error")?.toString()?.toIntOrNull()
            val backendStatus = json.opt("status")?.toString()?.toIntOrNull()

            if (statusCode !in 200..299) {
                return QrGenerationResult.Error(
                    buildBackendErrorMessage(
                        statusCode = statusCode,
                        rawBody = rawBody,
                        fallbackMessage = "Fallo al generar QR"
                    )
                )
            }

            if (backendError != null && backendError != 0) {
                return QrGenerationResult.Error(
                    buildBackendErrorMessage(
                        statusCode = statusCode,
                        rawBody = rawBody,
                        fallbackMessage = "Backend reporto error al generar QR"
                    )
                )
            }

            if (backendStatus != null && backendStatus != 1) {
                return QrGenerationResult.Error(
                    buildBackendErrorMessage(
                        statusCode = statusCode,
                        rawBody = rawBody,
                        fallbackMessage = "Backend no confirmo estado exitoso al generar QR"
                    )
                )
            }

            val values = json.optJSONObject("values") ?: json
            val pedidoId = extractIntFrom(
                values,
                "taPedido.tnPedido",
                "pedido.tnPedido",
                "tnPedido"
            )

            val qrBase64 = extractStringFrom(
                values,
                "taQr.tcQrBase64",
                "taQr.qrBase64",
                "qr.tcQrBase64",
                "qr.qrBase64",
                "tcQrBase64",
                "qrBase64"
            )

            val expiration = extractStringFrom(
                values,
                "taQr.ltExpirationDate",
                "taQr.expirationDate",
                "qr.ltExpirationDate",
                "qr.expirationDate",
                "ltExpirationDate",
                "expirationDate"
            )

            if (pedidoId <= 0) {
                return QrGenerationResult.Error("Respuesta sin tnPedido valido. Body: $rawBody")
            }
            if (qrBase64.isBlank()) {
                return QrGenerationResult.Error("Respuesta sin QR base64. Body: $rawBody")
            }

            QrGenerationResult.Success(
                pedidoId = pedidoId,
                qrBase64 = qrBase64,
                expiration = expiration
            )
        } catch (ex: Exception) {
            QrGenerationResult.Error("Fallo de conexion al generar QR: ${ex.message ?: "sin detalle"}")
        } finally {
            connection?.disconnect()
        }
    }

    private fun extractIntFrom(source: JSONObject?, vararg paths: String): Int {
        if (source == null) return 0
        for (path in paths) {
            val value = resolvePath(source, path)
            when (value) {
                is Number -> return value.toInt()
                is String -> value.toIntOrNull()?.let { return it }
            }
        }
        return 0
    }

    private fun extractStringFrom(source: JSONObject?, vararg paths: String): String {
        if (source == null) return ""
        for (path in paths) {
            val value = resolvePath(source, path)
            val text = value?.toString()?.trim().orEmpty()
            if (text.isNotBlank()) return text
        }
        return ""
    }

    private fun resolvePath(source: JSONObject, path: String): Any? {
        val parts = path.split(".")
        var current: Any? = source
        for (part in parts) {
            current = when (current) {
                is JSONObject -> if (current.has(part) && !current.isNull(part)) current.opt(part) else null
                else -> null
            }
            if (current == null) return null
        }
        return current
    }

    private fun buildBackendErrorMessage(statusCode: Int, rawBody: String, fallbackMessage: String): String {
        return try {
            val json = JSONObject(rawBody)
            val message = json.optString("message", fallbackMessage).ifBlank { fallbackMessage }
            val errorsObj = json.optJSONObject("errors")
            if (errorsObj == null || errorsObj.length() == 0) {
                "$message (HTTP $statusCode)"
            } else {
                val details = mutableListOf<String>()
                val keys = errorsObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = errorsObj.opt(key)
                    when (value) {
                        is JSONArray -> {
                            val joined = (0 until value.length()).joinToString("; ") { idx ->
                                value.optString(idx)
                            }
                            details += "$key: $joined"
                        }

                        else -> details += "$key: ${value?.toString().orEmpty()}"
                    }
                }
                "$message\n${details.joinToString("\n")}".trim()
            }
        } catch (_: Exception) {
            if (rawBody.isBlank()) "$fallbackMessage (HTTP $statusCode)" else rawBody
        }
    }

    private fun openQrDialog(result: QrGenerationResult.Success, selections: List<PurchaseSelection>, fromCart: Boolean) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_qr_payment, null)
        val ivQr = view.findViewById<ImageView>(R.id.ivPaymentQr)
        val tvExpiration = view.findViewById<TextView>(R.id.tvQrExpiration)
        val tvQrStatus = view.findViewById<TextView>(R.id.tvQrStatus)
        val progressQr = view.findViewById<ProgressBar>(R.id.progressQrPolling)
        val btnClose = view.findViewById<Button>(R.id.btnCloseQrDialog)

        val bitmap = decodeQrBase64(result.qrBase64)
        if (bitmap == null) {
            Toast.makeText(this, "No se pudo convertir el QR", Toast.LENGTH_LONG).show()
            return
        }

        val minSide = resources.displayMetrics.widthPixels.coerceAtMost(resources.displayMetrics.heightPixels)
        val targetPx = (minSide * 0.72f).toInt().coerceIn(dp(260), dp(520))
        ivQr.layoutParams = ivQr.layoutParams.apply {
            width = targetPx
            height = targetPx
        }
        ivQr.scaleType = ImageView.ScaleType.FIT_CENTER
        ivQr.setImageBitmap(bitmap)
        tvExpiration.text = if (result.expiration.isBlank()) "Expira: -" else "Expira: ${result.expiration}"
        tvQrStatus.text = "Esperando pago..."

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(false)
            .create()

        btnClose.visibility = View.VISIBLE
        btnClose.isEnabled = true
        btnClose.text = "Cancelar"
        var cancelInProgress = false

        btnClose.setOnClickListener {
            if (cancelInProgress) return@setOnClickListener
            qrPollingJob?.cancel()
            val confirmView = LayoutInflater.from(this).inflate(R.layout.dialog_cancel_order_confirm, null)
            val btnNo = confirmView.findViewById<Button>(R.id.btnCancelOrderNo)
            val btnYes = confirmView.findViewById<Button>(R.id.btnCancelOrderYes)
            val confirmDialog = AlertDialog.Builder(this)
                .setView(confirmView)
                .setCancelable(false)
                .create()

            confirmDialog.setOnDismissListener {
                onModalDismissed()
            }
            btnNo.setOnClickListener {
                confirmDialog.dismiss()
                if (!cancelInProgress && dialog.isShowing) {
                    tvQrStatus.text = "Esperando confirmacion de pago..."
                    progressQr.visibility = View.VISIBLE
                    startQrPaymentPolling(dialog, tvQrStatus, progressQr, btnClose, result, selections, fromCart)
                }
            }
            btnYes.setOnClickListener {
                cancelInProgress = true
                btnClose.isEnabled = false
                tvQrStatus.text = "Cancelando pedido..."
                progressQr.visibility = View.VISIBLE
                confirmDialog.dismiss()

                lifecycleScope.launch {
                    val cancelResult = withContext(Dispatchers.IO) {
                        cancelPendingOrder(
                            pedidoId = result.pedidoId,
                            authHeader = authHeader,
                            reason = "CANCELADO_CLIENTE_APK"
                        )
                    }

                    when (cancelResult) {
                        is OrderCancelResult.Success -> {
                            dialog.dismiss()
                            loadCatalog(machineId, authHeader)
                            Toast.makeText(
                                this@KioskCatalogActivity,
                                cancelResult.message.ifBlank { "Pedido cancelado." },
                                Toast.LENGTH_SHORT
                            ).show()
                        }

                        is OrderCancelResult.Error -> {
                            tvQrStatus.text = cancelResult.message.ifBlank { "No se pudo cancelar el pedido." }
                            btnClose.isEnabled = true
                            cancelInProgress = false
                            startQrPaymentPolling(dialog, tvQrStatus, progressQr, btnClose, result, selections, fromCart)
                        }
                    }
                }
            }

            onModalShown()
            confirmDialog.show()
            confirmDialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        onModalShown()
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setOnDismissListener {
            qrPollingJob?.cancel()
            onModalDismissed()
        }

        startQrPaymentPolling(dialog, tvQrStatus, progressQr, btnClose, result, selections, fromCart)
    }

    private fun startQrPaymentPolling(
        dialog: AlertDialog,
        tvQrStatus: TextView,
        progressQr: ProgressBar,
        btnClose: Button,
        result: QrGenerationResult.Success,
        selections: List<PurchaseSelection>,
        fromCart: Boolean
    ) {
        qrPollingJob?.cancel()
        qrPollingJob = lifecycleScope.launch {
            val started = System.currentTimeMillis()
            while (isActive && System.currentTimeMillis() - started <= PAYMENT_TIMEOUT_MS) {
                val pollResult = withContext(Dispatchers.IO) {
                    fetchPaymentStatus(result.pedidoId, authHeader)
                }

                when (pollResult) {
                    is PaymentPollResult.Paid -> {
                        progressQr.visibility = View.GONE
                        tvQrStatus.text = "Pago confirmado"
                        delay(500)
                        dialog.dismiss()
                        showDispenseDialogAndStart(selections, fromCart)
                        return@launch
                    }

                    is PaymentPollResult.Pending -> {
                        tvQrStatus.text = "Esperando confirmacion de pago..."
                    }

                    is PaymentPollResult.Failed -> {
                        progressQr.visibility = View.GONE
                        tvQrStatus.text = pollResult.message
                        btnClose.text = "Cerrar"
                        return@launch
                    }

                    is PaymentPollResult.Error -> {
                        tvQrStatus.text = pollResult.message
                    }
                }

                delay(PAYMENT_POLL_INTERVAL_MS)
            }

            if (isActive) {
                progressQr.visibility = View.GONE
                tvQrStatus.text = "Tiempo de espera agotado"
                dialog.dismiss()
                loadCatalog(machineId, authHeader)
                Toast.makeText(this@KioskCatalogActivity, "QR vencido, vuelve a intentar", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun cancelPendingOrder(
        pedidoId: Int,
        authHeader: String,
        reason: String
    ): OrderCancelResult {
        val endpoint = "https://boxipagobackend.pagofacil.com.bo/api/pedido/$pedidoId/cancelar"
        var connection: HttpURLConnection? = null

        return try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 12_000
                readTimeout = 12_000
                doOutput = true
                setRequestProperty("Authorization", authHeader)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json")
            }

            val payload = JSONObject().apply {
                put("tcMotivo", reason.ifBlank { "CANCELADO_CLIENTE_APK" })
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
                return OrderCancelResult.Error("Sin respuesta al cancelar pedido (HTTP $statusCode)")
            }

            val json = JSONObject(rawBody)
            val backendError = json.optInt("error", -1)
            val backendStatus = json.optInt("status", 0)
            val backendMessage = json.optString("message", "No se pudo cancelar el pedido.")

            return if (statusCode in 200..299 && backendError == 0 && backendStatus == 1) {
                OrderCancelResult.Success(backendMessage)
            } else {
                OrderCancelResult.Error(buildBackendErrorMessage(statusCode, rawBody, backendMessage))
            }
        } catch (ex: Exception) {
            OrderCancelResult.Error("Error cancelando pedido: ${ex.message ?: "sin detalle"}")
        } finally {
            connection?.disconnect()
        }
    }

    private fun fetchPaymentStatus(pedidoId: Int, authHeader: String): PaymentPollResult {
        val endpoint = "https://boxipagobackend.pagofacil.com.bo/api/pedido/$pedidoId/estado-pago"
        var connection: HttpURLConnection? = null

        return try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 12_000
                setRequestProperty("Authorization", authHeader)
                setRequestProperty("Accept", "application/json")
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
                return PaymentPollResult.Error("Sin respuesta de estado pago (HTTP $statusCode)")
            }

            val json = JSONObject(rawBody)
            val backendError = json.optInt("error", -1)
            val backendStatus = json.optInt("status", 0)
            val backendMessage = json.optString("message", "Consultando estado...")

            if (statusCode !in 200..299 || backendError != 0 || backendStatus != 1) {
                return PaymentPollResult.Error(buildBackendErrorMessage(statusCode, rawBody, backendMessage))
            }

            val values = json.optJSONObject("values") ?: JSONObject()
            val tnEstadoPago = values.optInt("tnEstadoPago", Int.MIN_VALUE)
            val tnEstadoPedido = values.optInt("tnEstadoPedido", Int.MIN_VALUE)
            val estadoFallback = values.optInt("estado", 1)
            val effectiveState = when {
                tnEstadoPago != Int.MIN_VALUE -> tnEstadoPago
                tnEstadoPedido != Int.MIN_VALUE -> tnEstadoPedido
                else -> estadoFallback
            }

            return when (effectiveState) {
                2 -> PaymentPollResult.Paid
                3 -> PaymentPollResult.Failed("Pago cancelado")
                4 -> PaymentPollResult.Failed("Pago fallido")
                else -> PaymentPollResult.Pending(backendMessage)
            }
        } catch (ex: Exception) {
            PaymentPollResult.Error("Error consultando estado de pago: ${ex.message ?: "sin detalle"}")
        } finally {
            connection?.disconnect()
        }
    }

    private fun showDispenseDialogAndStart(selections: List<PurchaseSelection>, fromCart: Boolean) {
        val queue = mutableListOf<Int>()
        selections.forEach { selection ->
            val physical = selection.item.physicalCell.takeIf { it in 10..68 }
                ?: mapCellCodeToPhysical(selection.item.codigoCelda)
            if (physical == null) {
                Toast.makeText(this, "No se pudo mapear la celda ${selection.item.codigoCelda}", Toast.LENGTH_LONG).show()
                return
            }
            repeat(selection.quantity) { queue += physical }
        }

        if (queue.isEmpty()) {
            Toast.makeText(this, "No hay celdas para dispensar", Toast.LENGTH_SHORT).show()
            return
        }

        if (!ensureSerialConnection()) {
            Toast.makeText(this, "No se pudo abrir puerto serial /dev/ttyS1", Toast.LENGTH_LONG).show()
            return
        }

        val view = LayoutInflater.from(this).inflate(R.layout.dialog_dispense_progress, null)
        tvDispenseTitle = view.findViewById(R.id.tvDispenseTitle)
        tvDispenseStatus = view.findViewById(R.id.tvDispenseStatus)
        tvDispenseTimer = view.findViewById(R.id.tvDispenseTimer)
        progressDispense = view.findViewById(R.id.progressDispense)
        btnDispenseClose = view.findViewById(R.id.btnDispenseClose)

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(false)
            .create()

        btnDispenseClose?.setOnClickListener {
            dispenseSuccessCloseTimer?.cancel()
            dispenseSuccessCloseTimer = null
            dialog.dismiss()
        }

        onModalShown()
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dispenseDialog = dialog
        dialog.setOnDismissListener {
            dispenseSuccessCloseTimer?.cancel()
            dispenseSuccessCloseTimer = null
            onModalDismissed()
        }

        dispensingQueue = queue
        dispensingCursor = 0
        dispensingInProgress = true
        clearCartOnDispenseFinish = fromCart

        tvDispenseTitle?.text = "Pago realizado"
        tvDispenseStatus?.text = "La maquina esta dispensando tu compra..."
        tvDispenseTimer?.visibility = View.GONE
        progressDispense?.visibility = View.VISIBLE
        btnDispenseClose?.visibility = View.GONE

        startNextDispenseItem()
    }

    private fun ensureSerialConnection(): Boolean {
        if (serial.isOpen()) return true
        serial.open(DEFAULT_PORT, DEFAULT_BAUD, serialListener)
        return serial.isOpen()
    }

    private fun startNextDispenseItem() {
        if (!dispensingInProgress) return
        if (dispensingCursor >= dispensingQueue.size) {
            onDispenseFinished()
            return
        }

        val currentCell = dispensingQueue[dispensingCursor]
        val currentNumber = dispensingCursor + 1
        val total = dispensingQueue.size
        tvDispenseStatus?.text = "Dispensando producto $currentNumber de $total (celda $currentCell)..."
        vendFlow.start(currentCell)
    }

    private fun onDispenseItemDone() {
        if (!dispensingInProgress) return
        dispensingCursor++
        startNextDispenseItem()
    }

    private fun onDispenseError(message: String) {
        dispensingInProgress = false
        runCatching { vendFlow.stop() }
        dispenseSuccessCloseTimer?.cancel()
        dispenseSuccessCloseTimer = null

        tvDispenseTitle?.text = "Incidencia en dispensado"
        tvDispenseStatus?.text = message
        tvDispenseTimer?.visibility = View.GONE
        progressDispense?.visibility = View.GONE
        btnDispenseClose?.visibility = View.VISIBLE
    }

    private fun onDispenseFinished() {
        dispensingInProgress = false
        runCatching { vendFlow.stop() }
        dispenseSuccessCloseTimer?.cancel()
        dispenseSuccessCloseTimer = null

        tvDispenseTitle?.text = "Gracias por su compra"
        tvDispenseStatus?.text = "Dispensado completado correctamente."
        tvDispenseTimer?.visibility = View.VISIBLE
        progressDispense?.visibility = View.GONE
        btnDispenseClose?.visibility = View.VISIBLE

        if (clearCartOnDispenseFinish) {
            cartItems.clear()
            updateCartBadge()
        }

        dispenseSuccessCloseTimer = object : CountDownTimer(DISPENSE_SUCCESS_DIALOG_TIMEOUT_MS, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = ((millisUntilFinished + 999L) / 1000L).coerceAtLeast(0L)
                tvDispenseTimer?.text = "Cerrando en ${seconds}s..."
            }

            override fun onFinish() {
                tvDispenseTimer?.text = "Cerrando en 0s..."
                dispenseDialog?.takeIf { it.isShowing }?.dismiss()
            }
        }.start()

        loadCatalog(machineId, authHeader)
    }

    private fun decodeQrBase64(rawBase64: String): android.graphics.Bitmap? {
        return try {
            val clean = rawBase64.substringAfter("base64,", rawBase64).trim()
            val bytes = Base64.decode(clean, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        }
    }

    private fun loadProductImage(imageUrl: String, imageView: ImageView) {
        imageView.setImageResource(android.R.drawable.ic_menu_gallery)
        imageView.scaleType = ImageView.ScaleType.CENTER_INSIDE
        if (imageUrl.isBlank()) return

        imageView.tag = imageUrl
        imageCache.get(imageUrl)?.let { bitmap ->
            imageView.setImageBitmap(bitmap)
            imageView.scaleType = ImageView.ScaleType.FIT_CENTER
            return
        }

        var shouldStartDownload = false
        synchronized(imageTargetsByUrl) {
            val targets = imageTargetsByUrl.getOrPut(imageUrl) { mutableListOf() }
            targets.add(imageView)
            if (targets.size == 1) shouldStartDownload = true
        }
        if (!shouldStartDownload) return

        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { downloadBitmap(imageUrl, 240) }
            if (bitmap != null) {
                imageCache.put(imageUrl, bitmap)
            }
            val targets = synchronized(imageTargetsByUrl) {
                imageTargetsByUrl.remove(imageUrl).orEmpty()
            }
            targets.forEach { target ->
                if (target.tag == imageUrl && bitmap != null) {
                    target.setImageBitmap(bitmap)
                    target.scaleType = ImageView.ScaleType.FIT_CENTER
                }
            }
        }
    }

    private fun loadPromoImage(imageUrl: String, imageView: ImageView) {
        imageView.setImageDrawable(null)
        imageView.scaleType = ImageView.ScaleType.FIT_CENTER
        if (imageUrl.isBlank()) return

        val tagValue = "promo:$imageUrl"
        imageView.tag = tagValue
        imageCache.get(imageUrl)?.let { bitmap ->
            imageView.setImageBitmap(bitmap)
            imageView.scaleType = ImageView.ScaleType.FIT_CENTER
            applyAdaptiveCarouselHeight(bitmap)
            return
        }

        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { downloadBitmap(imageUrl, 900) }
            if (bitmap != null) {
                imageCache.put(imageUrl, bitmap)
            }
            if (imageView.tag == tagValue && bitmap != null) {
                imageView.setImageBitmap(bitmap)
                imageView.scaleType = ImageView.ScaleType.FIT_CENTER
                applyAdaptiveCarouselHeight(bitmap)
            }
        }
    }

    private fun applyAdaptiveCarouselHeight(bitmap: android.graphics.Bitmap) {
        if (promoAdaptiveHeightApplied) return
        if (bitmap.width <= 0 || bitmap.height <= 0) return

        val containerWidth = promoCarousel.width.takeIf { it > 0 }
            ?: (resources.displayMetrics.widthPixels - dp(24))
        if (containerWidth <= 0) return

        val desiredHeight = (containerWidth * (bitmap.height.toFloat() / bitmap.width.toFloat())).toInt()
        val screenHeight = resources.displayMetrics.heightPixels
        val minHeight = dp(220)
        val maxHeight = (screenHeight * 0.52f).toInt().coerceAtLeast(dp(320))
        val targetHeight = desiredHeight.coerceIn(minHeight, maxHeight)

        val params = promoCarousel.layoutParams ?: return
        if (params.height != targetHeight) {
            params.height = targetHeight
            promoCarousel.layoutParams = params
        }
        promoAdaptiveHeightApplied = true
    }

    private fun downloadBitmap(rawUrl: String, targetSizePx: Int): android.graphics.Bitmap? {
        val primary = rawUrl.trim()
        val alternatives = buildList {
            add(primary)
            if (primary.startsWith("http://", ignoreCase = true)) {
                add(primary.replaceFirst("http://", "https://", ignoreCase = true))
            }
        }

        for (candidate in alternatives) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(candidate).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    instanceFollowRedirects = true
                    doInput = true
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "BoxiPago-Android/1.0")
                }
                connection.connect()
                if (connection.responseCode !in 200..299) continue
                val bytes = connection.inputStream.use { input ->
                    val buffer = ByteArray(8 * 1024)
                    val output = ByteArrayOutputStream()
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
                decodeSampledBitmap(bytes, targetSizePx)?.let { bitmap ->
                    return bitmap
                }
            } catch (_: Exception) {
                // continue with next alternative
            } finally {
                connection?.disconnect()
            }
        }
        return null
    }

    private fun decodeSampledBitmap(data: ByteArray, targetSizePx: Int): android.graphics.Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var inSampleSize = 1
        while (bounds.outWidth / inSampleSize > targetSizePx * 2 || bounds.outHeight / inSampleSize > targetSizePx * 2) {
            inSampleSize *= 2
        }

        val options = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeByteArray(data, 0, data.size, options)
    }

    private fun syncCartWithCatalog(latestCatalog: List<CeldaUi>) {
        if (cartItems.isEmpty()) return
        val byId = latestCatalog.associateBy { it.planogramaCeldaId }
        val iterator = cartItems.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val latest = byId[entry.key]
            if (latest == null || !isCellSellable(latest)) {
                iterator.remove()
                continue
            }
            val adjustedQty = entry.value.quantity.coerceAtMost(latest.stockDisponible)
            if (adjustedQty <= 0) {
                iterator.remove()
            } else {
                entry.value.item = latest
                entry.value.quantity = adjustedQty
            }
        }
        updateCartBadge()
    }

    private fun updateCartBadge() {
        val qty = cartItems.values.sumOf { it.quantity }
        tvCartBadge.text = qty.toString()
        tvCartBadge.visibility = if (qty > 0) View.VISIBLE else View.GONE
    }

    private fun isCellSellable(item: CeldaUi): Boolean {
        return item.vendible && item.stockDisponible > 0 && item.planogramaCeldaId > 0 && item.productoId > 0
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

    private fun parseCellCode(code: String): Pair<String, Int> {
        val match = Regex("^([A-Za-z]+)(\\d+)$").find(code.trim())
        if (match != null) {
            val row = match.groupValues[1].uppercase()
            val column = match.groupValues[2].toIntOrNull() ?: Int.MAX_VALUE
            return row to column
        }
        return code.uppercase() to Int.MAX_VALUE
    }

    private fun applyCarouselHeight() {
        val screenHeight = resources.displayMetrics.heightPixels
        val screenWidth = resources.displayMetrics.widthPixels
        val isLandscape = screenWidth > screenHeight
        val factor = if (isLandscape) 0.22f else 0.30f
        val desired = (screenHeight * factor).toInt()
        val minHeight = if (isLandscape) dp(150) else dp(180)
        val maxHeight = if (isLandscape) dp(320) else dp(420)
        val target = desired.coerceIn(minHeight, maxHeight)
        promoCarousel.layoutParams = promoCarousel.layoutParams.apply {
            height = target
        }
    }

    private fun setupCarouselTouchControls() {
        var downX = 0f
        promoCarousel.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pauseAutoCarousel()
                    downX = event.x
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val deltaX = event.x - downX
                    val threshold = dp(36).toFloat()
                    val handled = when {
                        deltaX < -threshold -> {
                            showNextPromoSlide()
                            true
                        }

                        deltaX > threshold -> {
                            showPreviousPromoSlide()
                            true
                        }

                        else -> true
                    }
                    resumeAutoCarousel()
                    handled
                }

                MotionEvent.ACTION_CANCEL -> {
                    resumeAutoCarousel()
                    true
                }

                else -> true
            }
        }
    }

    private fun showNextPromoSlide() {
        if (useLegacyCarousel) {
            val slideCount = getLegacySlideCount()
            if (slideCount <= 1) return
            carouselIndex = (carouselIndex + 1) % slideCount
            showLegacySlide(carouselIndex)
            carouselHandler.removeCallbacks(carouselTicker)
            carouselHandler.postDelayed(carouselTicker, carouselIntervalMs)
            return
        }

        (promoCarousel as? ViewFlipper)?.let { flipper ->
            flipper.setInAnimation(this, R.anim.carousel_in_right)
            flipper.setOutAnimation(this, R.anim.carousel_out_left)
            flipper.showNext()
        }
    }

    private fun showPreviousPromoSlide() {
        if (useLegacyCarousel) {
            val slideCount = getLegacySlideCount()
            if (slideCount <= 1) return
            carouselIndex = if (carouselIndex - 1 < 0) slideCount - 1 else carouselIndex - 1
            showLegacySlide(carouselIndex)
            carouselHandler.removeCallbacks(carouselTicker)
            carouselHandler.postDelayed(carouselTicker, carouselIntervalMs)
            return
        }

        (promoCarousel as? ViewFlipper)?.let { flipper ->
            flipper.setInAnimation(this, R.anim.carousel_in_left)
            flipper.setOutAnimation(this, R.anim.carousel_out_right)
            flipper.showPrevious()
        }
    }

    private fun pauseAutoCarousel() {
        carouselHandler.removeCallbacks(carouselTicker)
        (promoCarousel as? ViewFlipper)?.stopFlipping()
    }

    private fun resumeAutoCarousel() {
        if (useLegacyCarousel) {
            if (getLegacySlideCount() > 1) {
                carouselHandler.removeCallbacks(carouselTicker)
                carouselHandler.postDelayed(carouselTicker, carouselIntervalMs)
            }
        } else {
            (promoCarousel as? ViewFlipper)?.let { flipper ->
                if (flipper.childCount > 1) flipper.startFlipping()
            }
        }
    }

    private fun showLegacySlide(index: Int) {
        if (promotionalSlides.isNotEmpty()) {
            val slide = promotionalSlides[index % promotionalSlides.size]
            tvPromoTitle?.text = ""
            tvPromoSubtitle?.text = ""
            tvPromoTitle?.visibility = View.GONE
            tvPromoSubtitle?.visibility = View.GONE
            loadLegacyPromoBackground(slide.url)
            return
        }

        val slide = legacySlides[index % legacySlides.size]
        promoCarousel.setBackgroundResource(slide.backgroundRes)
        tvPromoTitle?.text = slide.title
        tvPromoSubtitle?.text = slide.subtitle
        tvPromoTitle?.visibility = View.VISIBLE
        tvPromoSubtitle?.visibility = View.VISIBLE
    }

    private fun loadLegacyPromoBackground(imageUrl: String) {
        if (imageUrl.isBlank()) return
        val tagValue = "legacy-promo:$imageUrl"
        promoCarousel.tag = tagValue

        val cached = imageCache.get(imageUrl)
        if (cached != null) {
            promoCarousel.background = BitmapDrawable(resources, cached)
            return
        }

        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { downloadBitmap(imageUrl, 1200) }
            if (bitmap != null) {
                imageCache.put(imageUrl, bitmap)
            }
            if (promoCarousel.tag == tagValue && bitmap != null) {
                promoCarousel.background = BitmapDrawable(resources, bitmap)
            }
        }
    }

    private fun getLegacySlideCount(): Int {
        return if (promotionalSlides.isNotEmpty()) promotionalSlides.size else legacySlides.size
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

    companion object {
        private const val TAG = "KioskCatalogActivity"
        const val EXTRA_MACHINE_ID = "extra_machine_id"
        const val EXTRA_MACHINE_CODE = "extra_machine_code"
        const val EXTRA_MACHINE_LOCATION = "extra_machine_location"

        private const val PAYMENT_POLL_INTERVAL_MS = 5_000L
        private const val PAYMENT_TIMEOUT_MS = 120_000L

        private const val DEFAULT_PORT = "/dev/ttyS1"
        private const val DEFAULT_BAUD = 9600
        private const val DEFAULT_CUSTOMER_NAME = "Sin nombre"
        private const val DEFAULT_CUSTOMER_PHONE = "9999999"
        private const val DEFAULT_CUSTOMER_CI_NIT = "9999999"
        private const val PRODUCT_DIALOG_TIMEOUT_MS = 60_000L
        private const val DISPENSE_SUCCESS_DIALOG_TIMEOUT_MS = 5_000L
        private const val PLANOGRAM_INACTIVITY_REFRESH_MS = 60_000L
    }
}

private data class LegacySlide(
    val backgroundRes: Int,
    val title: String,
    val subtitle: String
)

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
    val imagenUrl: String
)

private data class CartLine(
    var item: CeldaUi,
    var quantity: Int
)

private data class PurchaseSelection(
    val item: CeldaUi,
    val quantity: Int
)

private enum class PaymentMethodOption(val id: Int, val label: String) {
    QR_BCP(4, "QR")
}

private sealed interface CatalogResult {
    data class Success(
        val celdas: List<CeldaUi>,
        val promotions: List<PromoSlideUi>,
        val backgroundImageUrl: String
    ) : CatalogResult
    data class Error(val message: String) : CatalogResult
}

private sealed interface QrGenerationResult {
    data class Success(
        val pedidoId: Int,
        val qrBase64: String,
        val expiration: String
    ) : QrGenerationResult

    data class Error(val message: String) : QrGenerationResult
}

private sealed interface PaymentPollResult {
    data object Paid : PaymentPollResult
    data class Pending(val message: String) : PaymentPollResult
    data class Failed(val message: String) : PaymentPollResult
    data class Error(val message: String) : PaymentPollResult
}

private sealed interface OrderCancelResult {
    data class Success(val message: String) : OrderCancelResult
    data class Error(val message: String) : OrderCancelResult
}

private sealed interface MachineAccessResult {
    data object Granted : MachineAccessResult
    data class Denied(val message: String) : MachineAccessResult
    data class Error(val message: String) : MachineAccessResult
}
