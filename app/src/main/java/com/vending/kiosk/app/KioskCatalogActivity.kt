package com.vending.kiosk.app

import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewFlipper
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.vending.kiosk.R
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
import java.net.HttpURLConnection
import java.net.URL

class KioskCatalogActivity : AppCompatActivity() {

    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvCartBadge: TextView
    private lateinit var promoCarousel: View
    private var tvPromoTitle: TextView? = null
    private var tvPromoSubtitle: TextView? = null
    private lateinit var contentContainer: LinearLayout

    private val authSessionManager by lazy { AuthSessionManager(this) }

    private var useLegacyCarousel = false
    private val carouselHandler = Handler(Looper.getMainLooper())
    private val carouselIntervalMs = 5_000L
    private var carouselIndex = 0
    private val legacySlides = listOf(
        LegacySlide(R.drawable.bg_catalog_promo_1, "Promociones", "Espacio para ofertas y anuncios"),
        LegacySlide(R.drawable.bg_catalog_promo_2, "Nuevos productos", "Carrusel preparado para imagenes"),
        LegacySlide(R.drawable.bg_catalog_promo_3, "Avisos", "Descuentos, mantenimiento y novedades")
    )

    private var machineId: Int = 0
    private var authHeader: String = ""
    private var catalogItems: List<CeldaUi> = emptyList()
    private val cartItems = linkedMapOf<Int, CartLine>()

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
    private var progressDispense: ProgressBar? = null
    private var btnDispenseClose: Button? = null

    private val carouselTicker = object : Runnable {
        override fun run() {
            try {
                if (!useLegacyCarousel || tvPromoTitle == null || tvPromoSubtitle == null) return
                showLegacySlide(carouselIndex % legacySlides.size)
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
                    tvDispenseStatus?.text = "Enviando comando a maquina..."
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
                    tvDispenseStatus?.text = "Retira el producto. Preparando siguiente item ($current/${dispensingQueue.size})..."
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
        useLegacyCarousel = layoutRes == R.layout.activity_kiosk_catalog_legacy

        tvTitle = findViewById(R.id.tvCatalogTitle)
        tvSubtitle = findViewById(R.id.tvCatalogSubtitle)
        tvStatus = findViewById(R.id.tvCatalogStatus)
        tvCartBadge = findViewById(R.id.tvCartBadge)
        promoCarousel = findViewById(R.id.vfPromoCarousel)
        contentContainer = findViewById(R.id.llCatalogContainer)

        if (useLegacyCarousel) {
            tvPromoTitle = findViewById(R.id.tvPromoTitle)
            tvPromoSubtitle = findViewById(R.id.tvPromoSubtitle)
        } else {
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
        val machineCode = intent.getStringExtra(EXTRA_MACHINE_CODE).orEmpty()
        val machineLocation = intent.getStringExtra(EXTRA_MACHINE_LOCATION).orEmpty()

        if (machineId <= 0) {
            throw IllegalStateException("Maquina invalida")
        }

        tvTitle.text = machineCode
        tvSubtitle.text = machineLocation

        authHeader = authSessionManager.getAuthorizationHeader().orEmpty()
        if (authHeader.isBlank()) {
            throw IllegalStateException("Sesion expirada. Inicia sesion nuevamente.")
        }

        loadCatalog(machineId, authHeader)
    }

    override fun onResume() {
        super.onResume()
        if (useLegacyCarousel && tvPromoTitle != null && tvPromoSubtitle != null) {
            carouselHandler.removeCallbacks(carouselTicker)
            carouselHandler.postDelayed(carouselTicker, carouselIntervalMs)
        } else {
            (promoCarousel as? ViewFlipper)?.startFlipping()
        }
    }

    override fun onPause() {
        carouselHandler.removeCallbacks(carouselTicker)
        (promoCarousel as? ViewFlipper)?.stopFlipping()
        super.onPause()
    }

    override fun onStop() {
        carouselHandler.removeCallbacks(carouselTicker)
        super.onStop()
    }

    override fun onDestroy() {
        qrPollingJob?.cancel()
        carouselHandler.removeCallbacks(carouselTicker)
        if (::vendFlow.isInitialized) {
            runCatching { vendFlow.stop() }
        }
        runCatching { serial.close() }
        super.onDestroy()
    }

    private fun setupDispenseRuntime() {
        vendFlow = VendingFlowController(serial, serialListener, vendingUi)
    }

    private fun setupCartBadge() {
        updateCartBadge()
        tvCartBadge.setOnClickListener { showCartDialog() }
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
            val ordenadas = celdas.sortedWith(
                compareBy<CeldaUi> { parseCellCode(it.codigoCelda).first }
                    .thenBy { parseCellCode(it.codigoCelda).second }
                    .thenBy { it.codigoCelda }
            )
            CatalogResult.Success(ordenadas)
        } catch (ex: Exception) {
            CatalogResult.Error("Fallo de conexion: ${ex.message ?: "sin detalle"}")
        } finally {
            connection?.disconnect()
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
                physicalCell = physicalCell
            )
        }

        return celdas
    }

    private fun renderCatalog(celdas: List<CeldaUi>) {
        runCatching {
            val columns = 3
            val rows = 6
            val itemsPerPage = columns * rows
            val pageWidth = resources.displayMetrics.widthPixels - dp(24)

            celdas.chunked(itemsPerPage).forEachIndexed { pageIndex, pageItems ->
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

                            val available = isCellSellable(item)
                            card.findViewById<TextView>(R.id.tvCellState).apply {
                                if (available) {
                                    visibility = View.GONE
                                } else {
                                    visibility = View.VISIBLE
                                    text = "No disponible"
                                    setBackgroundResource(R.drawable.bg_status_disconnected)
                                    setTextColor(resources.getColor(R.color.badge_disconnected_text, theme))
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
        val tvStock = view.findViewById<TextView>(R.id.tvDialogProductStock)
        val tvQty = view.findViewById<TextView>(R.id.tvDialogQty)
        val btnMinus = view.findViewById<Button>(R.id.btnQtyMinus)
        val btnPlus = view.findViewById<Button>(R.id.btnQtyPlus)
        val btnAddCart = view.findViewById<Button>(R.id.btnAddCart)
        val btnBuyNow = view.findViewById<Button>(R.id.btnBuyNow)
        val ivPreview = view.findViewById<ImageView>(R.id.ivProductPreview)

        tvCode.text = "${item.codigoCelda} - ${item.producto}"
        tvName.text = "Precio unitario: ${if (item.precio > 0) "Bs ${formatPrice(item.precio)}" else "Sin precio"}"
        tvStock.text = "Stock disponible: ${item.stockDisponible}"
        ivPreview.setImageResource(android.R.drawable.ic_menu_gallery)

        var qty = 1
        tvQty.text = qty.toString()

        btnMinus.setOnClickListener {
            if (qty > 1) {
                qty--
                tvQty.text = qty.toString()
            }
        }

        btnPlus.setOnClickListener {
            if (qty < item.stockDisponible) {
                qty++
                tvQty.text = qty.toString()
            } else {
                Toast.makeText(this, "No puedes superar el stock", Toast.LENGTH_SHORT).show()
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        btnAddCart.setOnClickListener {
            addToCart(item, qty)
            dialog.dismiss()
        }

        btnBuyNow.setOnClickListener {
            dialog.dismiss()
            openCheckoutDialog(listOf(PurchaseSelection(item, qty)), fromCart = false)
        }

        dialog.show()
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

        val lines = cartItems.values.joinToString("\n") {
            "${it.item.codigoCelda} - ${it.item.producto} x${it.quantity}"
        }
        val total = cartItems.values.sumOf { it.item.precio * it.quantity }
        val summary = "$lines\n\nTotal: Bs ${formatPrice(total)}"

        AlertDialog.Builder(this)
            .setTitle("Carrito")
            .setMessage(summary)
            .setPositiveButton("Comprar") { _, _ ->
                val selections = cartItems.values.map { PurchaseSelection(it.item, it.quantity) }
                openCheckoutDialog(selections, fromCart = true)
            }
            .setNeutralButton("Vaciar") { _, _ ->
                cartItems.clear()
                updateCartBadge()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun openCheckoutDialog(selections: List<PurchaseSelection>, fromCart: Boolean) {
        if (selections.isEmpty()) return

        val view = LayoutInflater.from(this).inflate(R.layout.dialog_checkout_qr, null)
        val tvSummary = view.findViewById<TextView>(R.id.tvCheckoutSummary)
        val tvMethod = view.findViewById<TextView>(R.id.tvCheckoutMethod)
        val etName = view.findViewById<EditText>(R.id.etCheckoutName)
        val etPhone = view.findViewById<EditText>(R.id.etCheckoutPhone)
        val etCi = view.findViewById<EditText>(R.id.etCheckoutCi)
        val tvError = view.findViewById<TextView>(R.id.tvCheckoutError)
        val progress = view.findViewById<ProgressBar>(R.id.progressCheckout)
        val btnCancel = view.findViewById<Button>(R.id.btnCheckoutCancel)
        val btnGenerate = view.findViewById<Button>(R.id.btnCheckoutGenerate)

        val lines = selections.joinToString("\n") {
            "${it.item.codigoCelda} - ${it.item.producto} x${it.quantity}"
        }
        val total = selections.sumOf { it.item.precio * it.quantity }

        tvSummary.text = "$lines\n\nTotal: Bs ${formatPrice(total)}"
        tvMethod.text = "Metodo de pago: QR BCP"

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnGenerate.setOnClickListener {
            val name = etName.text?.toString()?.trim().orEmpty()
            val phone = etPhone.text?.toString()?.trim().orEmpty()
            val ci = etCi.text?.toString()?.trim().orEmpty()

            if (name.isBlank() || phone.isBlank() || ci.isBlank()) {
                tvError.visibility = View.VISIBLE
                tvError.text = "Completa nombre, telefono y CI"
                return@setOnClickListener
            }

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

            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    createOrderAndGenerateQr(
                        machineId = machineId,
                        authHeader = authHeader,
                        customerName = name,
                        customerPhone = phone,
                        customerCi = ci,
                        items = selections
                    )
                }

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

        dialog.show()
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
                put("tnPaymentMethodId", QR_PAYMENT_METHOD_ID)
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

        btnClose.setOnClickListener {
            qrPollingJob?.cancel()
            dialog.dismiss()
        }

        dialog.show()

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
                        tvQrStatus.text = pollResult.message.ifBlank { "Esperando confirmacion de pago..." }
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
            val lbPagado = values.optBoolean("lbPagado", false)
            val tnEstadoPedido = values.optInt("tnEstadoPedido", values.optInt("estado", 1))

            return when {
                lbPagado || tnEstadoPedido == 2 -> PaymentPollResult.Paid
                tnEstadoPedido == 3 -> PaymentPollResult.Failed("Pago cancelado")
                tnEstadoPedido == 4 -> PaymentPollResult.Failed("Pago fallido")
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
        progressDispense = view.findViewById(R.id.progressDispense)
        btnDispenseClose = view.findViewById(R.id.btnDispenseClose)

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(false)
            .create()

        btnDispenseClose?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        dispenseDialog = dialog

        dispensingQueue = queue
        dispensingCursor = 0
        dispensingInProgress = true
        clearCartOnDispenseFinish = fromCart

        tvDispenseTitle?.text = "Pago realizado"
        tvDispenseStatus?.text = "La maquina esta dispensando tu compra..."
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

        tvDispenseTitle?.text = "Incidencia en dispensado"
        tvDispenseStatus?.text = message
        progressDispense?.visibility = View.GONE
        btnDispenseClose?.visibility = View.VISIBLE
    }

    private fun onDispenseFinished() {
        dispensingInProgress = false
        runCatching { vendFlow.stop() }

        tvDispenseTitle?.text = "Gracias por su compra"
        tvDispenseStatus?.text = "Dispensado completado correctamente."
        progressDispense?.visibility = View.GONE
        btnDispenseClose?.visibility = View.VISIBLE

        if (clearCartOnDispenseFinish) {
            cartItems.clear()
            updateCartBadge()
        }

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
        tvCartBadge.text = "Carrito ($qty)"
        tvCartBadge.alpha = if (qty > 0) 1f else 0.6f
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
            carouselIndex = (carouselIndex + 1) % legacySlides.size
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
            carouselIndex = if (carouselIndex - 1 < 0) legacySlides.lastIndex else carouselIndex - 1
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
            carouselHandler.removeCallbacks(carouselTicker)
            carouselHandler.postDelayed(carouselTicker, carouselIntervalMs)
        } else {
            (promoCarousel as? ViewFlipper)?.startFlipping()
        }
    }

    private fun showLegacySlide(index: Int) {
        val slide = legacySlides[index % legacySlides.size]
        promoCarousel.setBackgroundResource(slide.backgroundRes)
        tvPromoTitle?.text = slide.title
        tvPromoSubtitle?.text = slide.subtitle
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
        const val EXTRA_MACHINE_ID = "extra_machine_id"
        const val EXTRA_MACHINE_CODE = "extra_machine_code"
        const val EXTRA_MACHINE_LOCATION = "extra_machine_location"

        private const val QR_PAYMENT_METHOD_ID = 4
        private const val PAYMENT_POLL_INTERVAL_MS = 5_000L
        private const val PAYMENT_TIMEOUT_MS = 120_000L

        private const val DEFAULT_PORT = "/dev/ttyS1"
        private const val DEFAULT_BAUD = 9600
    }
}

private data class LegacySlide(
    val backgroundRes: Int,
    val title: String,
    val subtitle: String
)

private data class CeldaUi(
    val planogramaCeldaId: Int,
    val productoId: Int,
    val codigoCelda: String,
    val producto: String,
    val precio: Double,
    val stockDisponible: Int,
    val vendible: Boolean,
    val physicalCell: Int
)

private data class CartLine(
    var item: CeldaUi,
    var quantity: Int
)

private data class PurchaseSelection(
    val item: CeldaUi,
    val quantity: Int
)

private sealed interface CatalogResult {
    data class Success(val celdas: List<CeldaUi>) : CatalogResult
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
