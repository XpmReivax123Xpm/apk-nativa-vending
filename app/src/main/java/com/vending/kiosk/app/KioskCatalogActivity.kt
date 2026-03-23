package com.vending.kiosk.app

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.vending.kiosk.R
import kotlinx.coroutines.Dispatchers
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
    private lateinit var promoCarousel: View
    private var tvPromoTitle: TextView? = null
    private var tvPromoSubtitle: TextView? = null
    private lateinit var contentContainer: LinearLayout
    private val authSessionManager by lazy { AuthSessionManager(this) }
    private var useLegacyCarousel = false
    private val carouselHandler = Handler(Looper.getMainLooper())
    private var carouselIndex = 0
    private val legacySlides = listOf(
        LegacySlide(R.drawable.bg_catalog_promo_1, "Promociones", "Espacio para ofertas y anuncios"),
        LegacySlide(R.drawable.bg_catalog_promo_2, "Nuevos productos", "Carrusel preparado para imagenes"),
        LegacySlide(R.drawable.bg_catalog_promo_3, "Avisos", "Descuentos, mantenimiento y novedades")
    )
    private val carouselTicker = object : Runnable {
        override fun run() {
            try {
                if (!useLegacyCarousel || tvPromoTitle == null || tvPromoSubtitle == null) return
                val slide = legacySlides[carouselIndex % legacySlides.size]
                promoCarousel.setBackgroundResource(slide.backgroundRes)
                tvPromoTitle?.text = slide.title
                tvPromoSubtitle?.text = slide.subtitle
                carouselIndex++
                carouselHandler.postDelayed(this, 3200L)
            } catch (_: Throwable) {
                // Failsafe Android 7.1.2: si algo falla en la animacion, detenemos ticker y dejamos slide actual.
                carouselHandler.removeCallbacks(this)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { initializeScreen() }
            .onFailure { error -> showSafeFallback(error) }
    }

    private fun initializeScreen() {
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
        promoCarousel = findViewById(R.id.vfPromoCarousel)
        if (useLegacyCarousel) {
            tvPromoTitle = findViewById(R.id.tvPromoTitle)
            tvPromoSubtitle = findViewById(R.id.tvPromoSubtitle)
        }
        contentContainer = findViewById(R.id.llCatalogContainer)
        applyCarouselHeight()

        val machineId = intent.getIntExtra(EXTRA_MACHINE_ID, 0)
        val machineCode = intent.getStringExtra(EXTRA_MACHINE_CODE).orEmpty()
        val machineLocation = intent.getStringExtra(EXTRA_MACHINE_LOCATION).orEmpty()

        if (machineId <= 0) {
            throw IllegalStateException("Maquina invalida")
        }

        tvTitle.text = machineCode
        tvSubtitle.text = machineLocation

        val authHeader = authSessionManager.getAuthorizationHeader()
        if (authHeader.isNullOrBlank()) {
            throw IllegalStateException("Sesion expirada. Inicia sesion nuevamente.")
        }

        loadCatalog(machineId, authHeader)
    }

    override fun onResume() {
        super.onResume()
        if (useLegacyCarousel && tvPromoTitle != null && tvPromoSubtitle != null) {
            carouselHandler.removeCallbacks(carouselTicker)
            carouselTicker.run()
        }
    }

    override fun onPause() {
        carouselHandler.removeCallbacks(carouselTicker)
        super.onPause()
    }

    override fun onStop() {
        carouselHandler.removeCallbacks(carouselTicker)
        super.onStop()
    }

    override fun onDestroy() {
        carouselHandler.removeCallbacks(carouselTicker)
        super.onDestroy()
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
                    if (result.celdas.isEmpty()) {
                        tvStatus.visibility = View.VISIBLE
                        tvStatus.text = "Sin productos disponibles (0 celdas recibidas)"
                    } else {
                        tvStatus.visibility = View.GONE
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
            val ordenadas = celdas.sortedWith(compareBy<CeldaUi> { it.codigoCelda.length }.thenBy { it.codigoCelda })
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

            val vendible = esActiva && estadoCeldaActiva && producto != null && productoActivo

            celdas += CeldaUi(
                codigoCelda = celda.optString("tcCodigo", "--"),
                producto = if (nombreProducto.isBlank()) "Sin producto" else nombreProducto,
                precio = precio,
                stockDisponible = stockDisponible,
                vendible = vendible
            )
        }

        return celdas
    }

    private fun renderCatalog(celdas: List<CeldaUi>) {
        runCatching {
            var rowLayout: LinearLayout? = null

            celdas.forEachIndexed { index, item ->
                if (index % 2 == 0) {
                    rowLayout = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).also { it.bottomMargin = dp(12) }
                    }
                    contentContainer.addView(rowLayout)
                }

                val card = layoutInflater.inflate(R.layout.item_catalog_cell, rowLayout, false)
                card.findViewById<TextView>(R.id.tvCellCode).text = item.codigoCelda
                card.findViewById<TextView>(R.id.tvCellProduct).text = item.producto
                card.findViewById<TextView>(R.id.tvCellPrice).text =
                    if (item.precio > 0.0) "Bs ${"%.2f".format(item.precio)}" else "Sin precio"
                card.findViewById<TextView>(R.id.tvCellStock).text = "Stock: ${item.stockDisponible}"
                card.findViewById<TextView>(R.id.tvCellState).apply {
                    if (item.vendible) {
                        text = "Disponible"
                        setBackgroundResource(R.drawable.bg_status_connected)
                        setTextColor(resources.getColor(R.color.badge_connected_text, theme))
                    } else {
                        text = "No vendible"
                        setBackgroundResource(R.drawable.bg_status_disconnected)
                        setTextColor(resources.getColor(R.color.badge_disconnected_text, theme))
                    }
                }

                val margin = dp(6)
                val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index % 2 == 0) {
                        setMargins(0, 0, margin, 0)
                    } else {
                        setMargins(margin, 0, 0, 0)
                    }
                }
                card.layoutParams = params
                rowLayout?.addView(card)
            }

            if (celdas.size % 2 == 1) {
                val spacer = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
                }
                rowLayout?.addView(spacer)
            }
        }.onFailure { error ->
            tvStatus.visibility = View.VISIBLE
            tvStatus.text = "Error de render: ${error.message ?: "sin detalle"}"
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun applyCarouselHeight() {
        val screenHeight = resources.displayMetrics.heightPixels
        val desired = (screenHeight * 0.30f).toInt()
        val minHeight = dp(180)
        val maxHeight = dp(420)
        val target = desired.coerceIn(minHeight, maxHeight)
        promoCarousel.layoutParams = promoCarousel.layoutParams.apply {
            height = target
        }
    }

    companion object {
        const val EXTRA_MACHINE_ID = "extra_machine_id"
        const val EXTRA_MACHINE_CODE = "extra_machine_code"
        const val EXTRA_MACHINE_LOCATION = "extra_machine_location"
    }
}

private data class LegacySlide(
    val backgroundRes: Int,
    val title: String,
    val subtitle: String
)

private data class CeldaUi(
    val codigoCelda: String,
    val producto: String,
    val precio: Double,
    val stockDisponible: Int,
    val vendible: Boolean
)

private sealed interface CatalogResult {
    data class Success(val celdas: List<CeldaUi>) : CatalogResult
    data class Error(val message: String) : CatalogResult
}
