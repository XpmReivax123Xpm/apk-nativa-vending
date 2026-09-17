package com.vending.kiosk.app.ui.dispense

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import java.io.File

@Composable
fun DispenseScreen(
    state: DispenseUiState,
    onManualPickupRetry: () -> Unit,
    onPlatformRecoveryRequested: () -> Unit,
    onSuccessCloseRequested: () -> Unit,
    onErrorViewLogsRequested: () -> Unit,
    onErrorViewBitacoraRequested: () -> Unit,
    onErrorSaveMonitoringRequested: () -> Unit
) {
    if (state.surface == DispenseSurface.Hidden) return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.52f))
            .clickable { }
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        DispensePanel(scrollable = state.surface == DispenseSurface.Error) {
            when (state.surface) {
                DispenseSurface.Dispensing -> DispensingContent(state)
                DispenseSurface.Retrieve -> RetrieveContent(state)
                DispenseSurface.IoTimeout -> IoTimeoutContent(state)
                DispenseSurface.ProlongedWait -> ProlongedWaitContent(state, onManualPickupRetry)
                DispenseSurface.PlatformStuck -> PlatformStuckContent(state, onPlatformRecoveryRequested)
                DispenseSurface.PlatformRecovering -> PlatformRecoveringContent(state)
                DispenseSurface.Success -> SuccessContent(state, onSuccessCloseRequested)
                DispenseSurface.Error -> ErrorContent(
                    error = state.error,
                    onViewLogs = onErrorViewLogsRequested,
                    onViewBitacora = onErrorViewBitacoraRequested,
                    onSaveMonitoring = onErrorSaveMonitoringRequested
                )
                DispenseSurface.Hidden -> Unit
            }
        }
    }
}

@Composable
private fun DispensePanel(scrollable: Boolean, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FBFF)),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = 24.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            content()
        }
    }
}

@Composable
private fun DispensingContent(state: DispenseUiState) {
    SurfaceTitle("DISPENSANDO...")
    Text(
        text = "${state.currentIndex} de ${state.totalItems}",
        color = Color(0xFF17427A),
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.titleLarge
    )
    ProductImage(state.productImageSource, state.productName, 240.dp)
    Text(state.productName.ifBlank { "Producto" }, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
    StatusText(state.statusText)
}

@Composable
private fun RetrieveContent(state: DispenseUiState) {
    SurfaceTitle(state.retrieveTitle.ifBlank { "RETIRE SU PRODUCTO" })
    Text(state.retrieveMessage, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
    if (state.productName.isNotBlank()) {
        ProductImage(state.productImageSource, state.productName, 160.dp)
        Text(state.productName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun IoTimeoutContent(state: DispenseUiState) {
    SurfaceIcon("!")
    SurfaceTitle("TIEMPO DE ESPERA")
    StatusText(state.modalMessage.ifBlank { "La puerta no responde aun. Seguimos esperando confirmacion de apertura." })
}

@Composable
private fun ProlongedWaitContent(state: DispenseUiState, onManualPickupRetry: () -> Unit) {
    SurfaceTitle("ESPERE UN MOMENTO")
    StatusText(state.modalMessage)
    when (state.manualRetryState) {
        ManualRetryUiState.Available -> Button(onClick = onManualPickupRetry, modifier = Modifier.fillMaxWidth()) {
            Text("Reintento manual")
        }
        ManualRetryUiState.InProgress -> Loading("Reintentando...")
        ManualRetryUiState.AlreadyInProgress -> StatusText("Ya estamos reintentando. Por favor espere.")
        ManualRetryUiState.UnableToStart -> StatusText("No se pudo iniciar el reintento manual.")
    }
}

@Composable
private fun PlatformStuckContent(state: DispenseUiState, onPlatformRecoveryRequested: () -> Unit) {
    SurfaceIcon("!")
    SurfaceTitle("PLATAFORMA ATORADA")
    StatusText(state.modalMessage.ifBlank { "Se detecto plataforma atorada. Presiona el boton para volver a base." })
    Button(onClick = onPlatformRecoveryRequested, modifier = Modifier.fillMaxWidth()) {
        Text("Arreglar plataforma atorada")
    }
}

@Composable
private fun PlatformRecoveringContent(state: DispenseUiState) {
    SurfaceTitle("RECUPERANDO PLATAFORMA")
    Loading(state.modalMessage.ifBlank { "Recuperando plataforma. Por favor espere..." })
}

@Composable
private fun SuccessContent(state: DispenseUiState, onSuccessCloseRequested: () -> Unit) {
    SurfaceIcon("✓", Color(0xFF16803C))
    SurfaceTitle("GRACIAS POR SU COMPRA")
    StatusText(state.modalMessage.ifBlank { "Dispensado completado correctamente." })
    state.successSecondsLeft?.let { secondsLeft ->
        Text("${secondsLeft}s", color = Color(0xFF60738C), fontWeight = FontWeight.Bold)
    }
    Button(onClick = onSuccessCloseRequested, modifier = Modifier.fillMaxWidth()) {
        Text("CERRAR")
    }
}

@Composable
private fun ErrorContent(
    error: DispenseErrorUi?,
    onViewLogs: () -> Unit,
    onViewBitacora: () -> Unit,
    onSaveMonitoring: () -> Unit
) {
    val displayError = error ?: DispenseErrorUi("No se pudo completar la dispensacion.")
    SurfaceIcon(if (displayError.isProductCrushed) "!" else "×", Color(0xFFB3261E))
    SurfaceTitle(if (displayError.isProductCrushed) "PRODUCTO APLASTADO" else "TUVIMOS UNA INCIDENCIA")
    StatusText(displayError.message)
    displayError.code?.takeIf { it.isNotBlank() }?.let { Text("Código: $it", color = Color(0xFF60738C)) }
    ProductSection("Productos entregados:", displayError.deliveredProducts)
    ProductSection("Productos pendientes:", displayError.pendingProducts)
    ProductSection("Productos en Revisión:", displayError.reviewProducts)
    Button(onClick = onViewLogs, modifier = Modifier.fillMaxWidth()) { Text("Ver logs") }
    Button(onClick = onViewBitacora, modifier = Modifier.fillMaxWidth()) { Text("Ver bitácora") }
    Button(
        onClick = onSaveMonitoring,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16803C))
    ) { Text("Guardar información") }
}

@Composable
private fun ProductSection(title: String, products: List<DispenseProductSummary>) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = Color(0xFF17427A), fontWeight = FontWeight.Bold)
        if (products.isEmpty()) {
            Text("- Ninguno", color = Color(0xFF60738C))
        } else {
            products.forEach { product ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProductImage(product.imageSource, product.name, 42.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("${product.name.ifBlank { "Producto sin nombre" }} x${product.quantity}")
                }
            }
        }
    }
}

@Composable
private fun ProductImage(source: String?, name: String, size: androidx.compose.ui.unit.Dp) {
    val bitmap = remember(source) {
        source
            ?.takeIf { !it.startsWith("http://", true) && !it.startsWith("https://", true) }
            ?.let(::File)
            ?.takeIf(File::isFile)
            ?.let { BitmapFactory.decodeFile(it.absolutePath) }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = name.ifBlank { "Product image" },
            modifier = Modifier.size(size),
            contentScale = ContentScale.Fit
        )
    } else {
        Box(
            modifier = Modifier.size(size).background(Color(0xFFE2E8F0), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("Producto", color = Color(0xFF60738C), textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun SurfaceTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF0E3B86),
        fontWeight = FontWeight.Black,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.headlineSmall
    )
}

@Composable
private fun SurfaceIcon(symbol: String, color: Color = Color(0xFF0E3B86)) {
    Text(symbol, color = color, fontWeight = FontWeight.Black, style = MaterialTheme.typography.displayMedium)
}

@Composable
private fun StatusText(text: String) {
    Text(text, modifier = Modifier.fillMaxWidth(), color = Color(0xFF60738C), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun Loading(message: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(message, color = Color(0xFF17427A), style = MaterialTheme.typography.bodyLarge)
    }
}

private val previewError = DispenseErrorUi(
    message = "Se detecto un producto aplastado durante la dispensacion",
    code = "PRODUCT_CRUSHED",
    isProductCrushed = true,
    deliveredProducts = listOf(DispenseProductSummary("Agua", 1)),
    pendingProducts = listOf(DispenseProductSummary("Jugo", 1)),
    reviewProducts = listOf(DispenseProductSummary("Galletas", 1))
)

@Preview(showBackground = true, widthDp = 540, heightDp = 900)
@Composable
private fun DispensingPreview() = PreviewScreen(DispenseUiState(DispenseSurface.Dispensing, 1, 3, "Agua mineral", statusText = "Espere un momento, por favor..."))

@Preview(showBackground = true, widthDp = 540, heightDp = 900)
@Composable
private fun RetrievePreview() = PreviewScreen(DispenseUiState(DispenseSurface.Retrieve, productName = "Agua mineral", retrieveTitle = "RETIRE SU PRODUCTO", retrieveMessage = "Por favor retire su producto."))

@Preview(showBackground = true, widthDp = 540, heightDp = 900)
@Composable
private fun ProlongedWaitPreview() = PreviewScreen(DispenseUiState(DispenseSurface.ProlongedWait, modalMessage = "La puerta aun no confirma apertura."))

@Preview(showBackground = true, widthDp = 540, heightDp = 900)
@Composable
private fun PlatformStuckPreview() = PreviewScreen(DispenseUiState(DispenseSurface.PlatformStuck, modalMessage = "Se detecto plataforma atorada."))

@Preview(showBackground = true, widthDp = 540, heightDp = 900)
@Composable
private fun SuccessPreview() = PreviewScreen(DispenseUiState(DispenseSurface.Success, modalMessage = "Dispensado completado correctamente.", successSecondsLeft = 5))

@Preview(showBackground = true, widthDp = 540, heightDp = 900)
@Composable
private fun ErrorPreview() = PreviewScreen(DispenseUiState(DispenseSurface.Error, error = previewError))

@Composable
private fun PreviewScreen(state: DispenseUiState) {
    DispenseScreen(state, {}, {}, {}, {}, {}, {})
}
