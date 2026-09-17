package com.vending.kiosk.app.ui.payment

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vending.kiosk.app.domain.cart.CartItem
import com.vending.kiosk.integration.backend.models.CreateOrderQrResponse
import com.vending.kiosk.integration.backend.models.PaymentMethod
import java.util.Locale

@Composable
fun PaymentScreen(
    state: PaymentUiState,
    onSelectPaymentMethod: (Int) -> Unit,
    onContinueToCheckout: () -> Unit,
    onReturnToMethodSelection: () -> Unit,
    onConfirmCheckout: () -> Unit,
    onCancel: () -> Unit,
    onInteraction: () -> Unit
) {
    if (state.step == PaymentStep.Closed) return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.48f))
            .padding(20.dp)
    ) {
        when (state.step) {
            PaymentStep.Qr -> DraggableQrPaymentPanel(
                state = state,
                onCancel = {
                    onInteraction()
                    onCancel()
                }
            )

            else -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                PaymentPanel {
                    when (state.step) {
                        PaymentStep.MethodSelection -> MethodSelectionContent(
                            state = state,
                            onSelectPaymentMethod = { methodId ->
                                onInteraction()
                                onSelectPaymentMethod(methodId)
                            },
                            onContinue = {
                                onInteraction()
                                onContinueToCheckout()
                            },
                            onCancel = {
                                onInteraction()
                                onCancel()
                            }
                        )

                        PaymentStep.Checkout -> CheckoutContent(
                            state = state,
                            onReturn = {
                                onInteraction()
                                onReturnToMethodSelection()
                            },
                            onConfirm = {
                                onInteraction()
                                onConfirmCheckout()
                            },
                            onCancel = {
                                onInteraction()
                                onCancel()
                            }
                        )

                        PaymentStep.Qr, PaymentStep.Closed -> Unit
                        PaymentStep.Completed -> CompletedContent(state, onCancel)
                    }
                }
            }
        }
    }
}

@Composable
private fun PaymentPanel(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 680.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FBFF)),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun DraggableQrPaymentPanel(
    state: PaymentUiState,
    onCancel: () -> Unit
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var panelSize by remember { mutableStateOf(IntSize.Zero) }
    val positionKey = state.qrOrder?.let { it.orderId to it.qrBase64 }
    var panelOffset by remember(positionKey) { mutableStateOf(IntOffset.Zero) }
    var isPositionInitialized by remember(positionKey) { mutableStateOf(false) }

    LaunchedEffect(positionKey, containerSize, panelSize) {
        if (containerSize != IntSize.Zero && panelSize != IntSize.Zero) {
            val maxX = (containerSize.width - panelSize.width).coerceAtLeast(0)
            val maxY = (containerSize.height - panelSize.height).coerceAtLeast(0)
            panelOffset = if (isPositionInitialized) {
                IntOffset(panelOffset.x.coerceIn(0, maxX), panelOffset.y.coerceIn(0, maxY))
            } else {
                IntOffset(maxX / 2, maxY)
            }
            isPositionInitialized = true
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
    ) {
        val qrSize = minOf(maxWidth, maxHeight)
            .times(0.72f)
            .coerceIn(260.dp, 520.dp)

        Card(
            modifier = Modifier
                .offset { panelOffset }
                .onSizeChanged { panelSize = it }
                .widthIn(max = 680.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FBFF)),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                QrDragHandle { dragAmount ->
                    val maxX = (containerSize.width - panelSize.width).coerceAtLeast(0)
                    val maxY = (containerSize.height - panelSize.height).coerceAtLeast(0)
                    panelOffset = IntOffset(
                        x = (panelOffset.x + dragAmount.x.toInt()).coerceIn(0, maxX),
                        y = (panelOffset.y + dragAmount.y.toInt()).coerceIn(0, maxY)
                    )
                }
                QrContent(state = state, qrSize = qrSize, onCancel = onCancel)
            }
        }
    }
}

@Composable
private fun QrDragHandle(onDrag: (Offset) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(18.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(32.dp)
                .height(4.dp)
                .background(Color(0xFFB7C3D1), RoundedCornerShape(2.dp))
        )
    }
}

@Composable
private fun MethodSelectionContent(
    state: PaymentUiState,
    onSelectPaymentMethod: (Int) -> Unit,
    onContinue: () -> Unit,
    onCancel: () -> Unit
) {
    PaymentTitle("Método de pago", "Seleccione cómo desea realizar el pago.")

    when {
        state.isLoadingPaymentMethods && state.paymentMethods.isEmpty() -> PaymentLoading("Cargando métodos de pago...")
        state.paymentMethods.isEmpty() -> PaymentStatus(state.error ?: "No hay métodos de pago disponibles")
        else -> {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                state.paymentMethods.forEach { method ->
                    PaymentMethodRow(
                        method = method,
                        selected = method.id == state.selectedPaymentMethod?.id,
                        onClick = { onSelectPaymentMethod(method.id) }
                    )
                }
            }
            if (state.isLoadingPaymentMethods) {
                PaymentLoading("Actualizando métodos de pago...")
            }
        }
    }

    if (state.paymentMethods.isNotEmpty()) state.error?.let { PaymentError(it) }
    MethodSelectionActions(onContinue = onContinue, onCancel = onCancel)
}

@Composable
private fun PaymentMethodRow(method: PaymentMethod, selected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) Color(0xFFE2F6FC) else Color.White
        ),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Spacer(Modifier.width(8.dp))
            Text(
                text = method.label,
                color = Color(0xFF17427A),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
private fun MethodSelectionActions(onContinue: () -> Unit, onCancel: () -> Unit) {
    Button(
        onClick = onContinue,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0E3B86))
    ) {
        Text("CONTINUAR", fontWeight = FontWeight.Black)
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = onCancel) {
            Text("VOLVER", color = Color(0xFF0E3B86), fontWeight = FontWeight.Bold)
        }
        TextButton(onClick = onCancel) {
            Text("CANCELAR", color = Color(0xFF9A3A3A), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CheckoutContent(
    state: PaymentUiState,
    onReturn: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    PaymentTitle("Confirmar compra", "Revise su pedido antes de generar el código QR.")
    Text(
        text = "Método: ${state.selectedPaymentMethod?.label ?: "No seleccionado"}",
        color = Color(0xFF17427A),
        fontWeight = FontWeight.Bold
    )
    LazyColumn(modifier = Modifier.height(220.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.items, key = { it.planogramCellId }) { item -> CheckoutLine(item) }
        if (state.items.isEmpty()) {
            item { PaymentStatus("No hay productos en el pedido") }
        }
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Total", color = Color(0xFF17427A), fontWeight = FontWeight.Bold)
        Text(
            text = money(state.total),
            color = Color(0xFFF59E0B),
            fontWeight = FontWeight.Black,
            style = MaterialTheme.typography.titleLarge
        )
    }
    state.error?.let { PaymentError(it) }
    if (state.isCreatingQr) {
        PaymentLoading("Generando código QR...")
    }
    Button(
        onClick = onConfirm,
        enabled = !state.isCreatingQr,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0E3B86))
    ) {
        Text(if (state.isCreatingQr) "GENERANDO..." else "GENERAR QR", fontWeight = FontWeight.Black)
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = onReturn, enabled = !state.isCreatingQr) {
            Text("VOLVER", color = Color(0xFF0E3B86), fontWeight = FontWeight.Bold)
        }
        TextButton(onClick = onCancel, enabled = !state.isCreatingQr) {
            Text("CANCELAR", color = Color(0xFF9A3A3A), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CheckoutLine(item: CartItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(item.name, color = Color(0xFF17427A), fontWeight = FontWeight.Bold, maxLines = 2)
                Text("Cantidad: ${item.quantity}", color = Color(0xFF60738C), style = MaterialTheme.typography.bodySmall)
            }
            Text(money(item.unitPrice * item.quantity), color = Color(0xFFF59E0B), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun QrContent(state: PaymentUiState, qrSize: Dp, onCancel: () -> Unit) {
    val qrBase64 = state.qrOrder?.qrBase64.orEmpty()
    val bitmap = remember(qrBase64) {
        qrBase64.takeIf { it.isNotBlank() }?.let { encoded ->
            runCatching {
                val payload = encoded.substringAfter("base64,", encoded)
                val bytes = Base64.decode(payload, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        }
    }

    PaymentTitle("Pago con QR", "Escanee el código para completar el pago.")
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Código QR de pago",
                modifier = Modifier
                    .size(qrSize)
                    .padding(8.dp),
                contentScale = ContentScale.Fit
            )
        } else {
            PaymentStatus("No se pudo mostrar el código QR")
        }
    }
    state.statusMessage?.let { PaymentStatus(it) }
    state.qrOrder?.expiration?.takeIf { it.isNotBlank() }?.let { expiration ->
        Text("Expira: $expiration", color = Color(0xFF60738C), style = MaterialTheme.typography.bodyMedium)
    }
    state.error?.let { PaymentError(it) }
    if (state.isCancellingOrder) PaymentLoading("Cancelando pedido...")
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(
            onClick = onCancel,
            enabled = !state.isCancellingOrder
        ) {
            Text("CANCELAR", color = Color(0xFF9A3A3A), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CompletedContent(state: PaymentUiState, onClose: () -> Unit) {
    PaymentTitle("Pago finalizado", state.statusMessage ?: "La operación de pago finalizó.")
    PaymentStatus("Puede continuar con la siguiente operación.")
    TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
        Text("CERRAR", color = Color(0xFF0E3B86), fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PaymentTitle(title: String, subtitle: String) {
    Text(title, color = Color(0xFF0E3B86), fontWeight = FontWeight.Black, style = MaterialTheme.typography.headlineSmall)
    Text(subtitle, color = Color(0xFF60738C), style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun PaymentLoading(message: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Color(0xFFF59E0B), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(message, color = Color(0xFF17427A), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PaymentStatus(message: String) {
    Text(message, modifier = Modifier.fillMaxWidth(), color = Color(0xFF60738C), textAlign = TextAlign.Center)
}

@Composable
private fun PaymentError(message: String) {
    Text(message, modifier = Modifier.fillMaxWidth(), color = Color(0xFFB3261E), textAlign = TextAlign.Center)
}

private fun money(value: Double): String = String.format(Locale.US, "%.2f Bs", value)

private fun previewCartItem(index: Int, quantity: Int) = CartItem(
    planogramCellId = index,
    productId = index,
    cellCode = "A-${index.toString().padStart(2, '0')}",
    name = "Producto $index",
    unitPrice = 4.5 + index,
    availableStock = 10,
    isVendible = true,
    physicalCell = index,
    primaryImageUrl = "",
    secondaryImageUrl = "",
    quantity = quantity
)

private val previewMethods = listOf(PaymentMethod(1, "Pago QR"), PaymentMethod(2, "Billetera móvil"))

@Preview(showBackground = true, widthDp = 540, heightDp = 760)
@Composable
private fun PaymentMethodSelectionPreview() {
    PaymentScreen(
        state = PaymentUiState(
            step = PaymentStep.MethodSelection,
            paymentMethods = previewMethods,
            selectedPaymentMethod = previewMethods.first()
        ),
        onSelectPaymentMethod = {}, onContinueToCheckout = {}, onReturnToMethodSelection = {},
        onConfirmCheckout = {}, onCancel = {}, onInteraction = {}
    )
}

@Preview(showBackground = true, widthDp = 540, heightDp = 760)
@Composable
private fun PaymentCheckoutPreview() {
    PaymentScreen(
        state = PaymentUiState(
            step = PaymentStep.Checkout,
            items = listOf(previewCartItem(1, 2), previewCartItem(2, 1), previewCartItem(3, 3)),
            total = 45.5,
            selectedPaymentMethod = previewMethods.first()
        ),
        onSelectPaymentMethod = {}, onContinueToCheckout = {}, onReturnToMethodSelection = {},
        onConfirmCheckout = {}, onCancel = {}, onInteraction = {}
    )
}

@Preview(showBackground = true, widthDp = 540, heightDp = 760)
@Composable
private fun PaymentQrUnavailablePreview() {
    PaymentScreen(
        state = PaymentUiState(
            step = PaymentStep.Qr,
            qrOrder = CreateOrderQrResponse(orderId = 1, qrBase64 = "invalid", expiration = "", details = emptyList()),
            statusMessage = "Esperando confirmación de pago..."
        ),
        onSelectPaymentMethod = {}, onContinueToCheckout = {}, onReturnToMethodSelection = {},
        onConfirmCheckout = {}, onCancel = {}, onInteraction = {}
    )
}

@Preview(showBackground = true, widthDp = 540, heightDp = 760)
@Composable
private fun PaymentMethodLoadingErrorPreview() {
    PaymentScreen(
        state = PaymentUiState(
            step = PaymentStep.MethodSelection,
            isLoadingPaymentMethods = true,
            error = "No se pudieron cargar los métodos de pago"
        ),
        onSelectPaymentMethod = {}, onContinueToCheckout = {}, onReturnToMethodSelection = {},
        onConfirmCheckout = {}, onCancel = {}, onInteraction = {}
    )
}
