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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.sp
import java.io.File

@Composable
fun DispenseScreen(
    state: DispenseUiState,
    onManualPickupRetry: () -> Unit,
    onPlatformRecoveryRequested: () -> Unit,
    onSuccessCloseRequested: () -> Unit,
    onErrorViewLogsRequested: () -> Unit,
    onErrorViewBitacoraRequested: () -> Unit,
    onRawBitacoraRequested: () -> Unit,
    onErrorSaveMonitoringRequested: () -> Unit
) {
    if (state.surface == DispenseSurface.Hidden) return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.52f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { }
            )
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
                DispenseSurface.Success -> SuccessContent(
                    state = state,
                    onClose = onSuccessCloseRequested
                )
                DispenseSurface.Error -> ErrorContent(
                    error = state.error,
                    onViewLogs = onErrorViewLogsRequested,
                    onViewBitacora = onErrorViewBitacoraRequested,
                    onViewRawBitacora = onRawBitacoraRequested,
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
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "DISPENSANDO...",
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFF0E3B86),
            fontSize = 34.sp,
            lineHeight = 40.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center
        )
        Box(
            modifier = Modifier
                .background(Color(0xFFD9ECFF), RoundedCornerShape(percent = 50))
                .padding(horizontal = 28.dp, vertical = 10.dp)
        ) {
            Text(
                text = "${state.currentIndex} de ${state.totalItems}",
                color = Color(0xFF17427A),
                fontSize = 22.sp,
                lineHeight = 26.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
        ProductImage(state.productImageSource, state.productName, 280.dp)
        Text(
            text = state.productName.ifBlank { "Producto" },
            color = Color(0xFF102F63),
            fontSize = 26.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Text(
            text = state.statusText.ifBlank { "Espere un momento, por favor..." },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            color = Color(0xFF304D71),
            fontSize = 20.sp,
            lineHeight = 26.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun RetrieveContent(state: DispenseUiState) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = state.retrieveTitle.ifBlank { "RETIRE SU PRODUCTO" },
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFF0E3B86),
            fontSize = 34.sp,
            lineHeight = 40.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center
        )
        Text(
            text = state.retrieveMessage.ifBlank { "Por favor, retire su producto." },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            color = Color(0xFF304D71),
            fontSize = 20.sp,
            lineHeight = 26.sp,
            textAlign = TextAlign.Center
        )
        if (state.productName.isNotBlank()) {
            ProductImage(state.productImageSource, state.productName, 280.dp)
            Text(
                text = state.productName,
                color = Color(0xFF102F63),
                fontSize = 26.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
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
private fun SuccessContent(
    state: DispenseUiState,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(150.dp)
                .background(Color(0xFF12C96B), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "✓",
                color = Color.White,
                fontSize = 86.sp,
                lineHeight = 92.sp,
                fontWeight = FontWeight.Black
            )
        }
        Text(
            text = "GRACIAS POR SU\nCOMPRA",
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFF0E3B86),
            fontSize = 34.sp,
            lineHeight = 40.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center
        )
        Text(
            text = state.modalMessage.ifBlank { "Dispensado completado correctamente." },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            color = Color(0xFF304D71),
            fontSize = 20.sp,
            lineHeight = 28.sp,
            textAlign = TextAlign.Center
        )
        state.successSecondsLeft?.let { secondsLeft ->
            Text(
                text = "${secondsLeft}s",
                color = Color(0xFF60738C),
                fontSize = 22.sp,
                lineHeight = 28.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Button(
            onClick = onClose,
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2F75E5))
        ) {
            Text("CERRAR", fontSize = 20.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun ErrorContent(
    error: DispenseErrorUi?,
    onViewLogs: () -> Unit,
    onViewBitacora: () -> Unit,
    onViewRawBitacora: () -> Unit,
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
    Button(onClick = onViewRawBitacora, modifier = Modifier.fillMaxWidth()) { Text("Bitácora en crudo") }
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
    DispenseScreen(state, {}, {}, {}, {}, {}, {}, {})
}
