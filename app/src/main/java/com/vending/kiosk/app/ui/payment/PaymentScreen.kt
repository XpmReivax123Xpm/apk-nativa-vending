package com.vending.kiosk.app.ui.payment

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    onInteraction: () -> Unit,
    imageCacheVersion: Int = 0,
    getCachedImageBitmap: (String) -> Bitmap? = { null }
) {
    if (state.step == PaymentStep.Closed) return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.48f))
    ) {
        when (state.step) {
            PaymentStep.Qr -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                DraggableQrPaymentPanel(
                    state = state,
                    onCancel = {
                        onInteraction()
                        onCancel()
                    }
                )
            }

            PaymentStep.MethodSelection -> PaymentMethodSheet(
                state = state,
                modifier = Modifier.align(Alignment.BottomCenter),
                onMethodSelected = { methodId ->
                    onInteraction()
                    onSelectPaymentMethod(methodId)
                    onContinueToCheckout()
                },
                onCancel = {
                    onInteraction()
                    onCancel()
                }
            )

            else -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                PaymentPanel {
                    when (state.step) {
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
                            },
                            imageCacheVersion = imageCacheVersion,
                            getCachedImageBitmap = getCachedImageBitmap
                        )

                        PaymentStep.MethodSelection -> Unit
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
            .widthIn(max = 760.dp),
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
private fun PaymentMethodSheet(
    state: PaymentUiState,
    onMethodSelected: (Int) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 760.dp),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FBFF)),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .width(64.dp)
                    .height(6.dp)
                    .background(Color(0xFFA5A4DC), RoundedCornerShape(3.dp))
            )
            Text(
                text = "Elija el método de pago",
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF10184A),
                fontSize = 32.sp,
                lineHeight = 38.sp,
                fontWeight = FontWeight.Black
            )

            when {
                state.isLoadingPaymentMethods && state.paymentMethods.isEmpty() -> {
                    PaymentLoading("Cargando métodos de pago...")
                }

                state.paymentMethods.isEmpty() -> {
                    PaymentStatus(state.error ?: "No hay métodos de pago disponibles")
                }

                else -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        state.paymentMethods.forEach { method ->
                            PaymentMethodCard(
                                method = method,
                                selected = method.id == state.selectedPaymentMethod?.id,
                                onClick = { onMethodSelected(method.id) }
                            )
                        }
                    }
                    if (state.isLoadingPaymentMethods) {
                        PaymentLoading("Actualizando métodos de pago...")
                    }
                }
            }

            if (state.paymentMethods.isNotEmpty()) state.error?.let { PaymentError(it) }
            PaymentMethodActions(onBack = onCancel)
        }
    }
}

@Composable
private fun PaymentMethodCard(method: PaymentMethod, selected: Boolean, onClick: () -> Unit) {
    val isQrMethod = method.label.contains("qr", ignoreCase = true)
    val borderColor = if (selected) Color(0xFF3B82F6) else Color(0xFFB6D5F5)
    val containerColor = if (selected) Color(0xFFEAF4FF) else Color.White

    Card(
        modifier = Modifier
            .width(280.dp)
            .height(220.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(if (selected) 2.dp else 1.dp, borderColor),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)
        ) {
            Box(
                modifier = Modifier
                    .size(118.dp)
                    .background(Color(0xFFE2F1FF), RoundedCornerShape(100.dp)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(com.vending.kiosk.R.drawable.ic_qr_payment),
                    contentDescription = "Pago con QR",
                    modifier = Modifier.size(98.dp)
                )
            }
            Text(
                text = if (isQrMethod) "QR" else method.label,
                color = Color(0xFF10184A),
                fontSize = 22.sp,
                fontWeight = FontWeight.Black
            )
        }
    }
}

@Composable
private fun PaymentMethodActions(onBack: () -> Unit) {
    OutlinedButton(
        onClick = onBack,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        border = BorderStroke(1.dp, Color(0xFF1768C5)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF1768C5)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Icon(
            painter = painterResource(com.vending.kiosk.R.drawable.ic_arrow_back),
            contentDescription = null,
            modifier = Modifier.size(25.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text("VOLVER", fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CheckoutContent(
    state: PaymentUiState,
    onReturn: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    imageCacheVersion: Int,
    getCachedImageBitmap: (String) -> Bitmap?
) {
    val subtotalAmount = state.items.sumOf { it.unitPrice * it.quantity }
    val totalAmount = state.total

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Confirmar compra",
            color = Color(0xFF10184A),
            fontSize = 34.sp,
            lineHeight = 40.sp,
            fontWeight = FontWeight.Black
        )
        Text(
            text = "Revise su pedido antes de generar el código QR.",
            color = Color(0xFF7C78B6),
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium
        )
        CheckoutPaymentMethodCard(
            methodLabel = state.selectedPaymentMethod?.label ?: "Pago QR"
        )
        CheckoutItemsCard(
            items = state.items,
            imageCacheVersion = imageCacheVersion,
            getCachedImageBitmap = getCachedImageBitmap
        )
        CheckoutSummary(
            subtotalAmount = subtotalAmount,
            totalAmount = totalAmount
        )
        state.error?.let { PaymentError(it) }
        if (state.isCreatingQr) {
            PaymentLoading("Generando código QR...")
        }
        Button(
            onClick = onConfirm,
            enabled = !state.isCreatingQr,
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF3155F5),
                disabledContainerColor = Color(0xFFAFB9E8)
            )
        ) {
            Icon(
                painter = painterResource(com.vending.kiosk.R.drawable.ic_qr_payment),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = if (state.isCreatingQr) "GENERANDO..." else "GENERAR QR",
                fontSize = 20.sp,
                fontWeight = FontWeight.Black
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onReturn,
                enabled = !state.isCreatingQr,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                border = BorderStroke(1.dp, Color(0xFF1768C5)),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color(0xFF1768C5),
                    disabledContentColor = Color(0xFFA8B7D1)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    painter = painterResource(com.vending.kiosk.R.drawable.ic_arrow_back),
                    contentDescription = null,
                    modifier = Modifier.size(25.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("VOLVER", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = onCancel,
                enabled = !state.isCreatingQr,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                border = BorderStroke(1.dp, Color(0xFFE33B5B)),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color(0xFFFFF1F4),
                    contentColor = Color(0xFFE33B5B),
                    disabledContentColor = Color(0xFFC9A9B0),
                    disabledContainerColor = Color(0xFFFFF7F8)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    painter = painterResource(com.vending.kiosk.R.drawable.ic_close),
                    contentDescription = null,
                    modifier = Modifier.size(25.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("CANCELAR", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun CheckoutPaymentMethodCard(methodLabel: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF4FF)),
        border = BorderStroke(1.dp, Color(0xFFB6D5F5))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(86.dp)
                    .background(Color(0xFFDCEEFF), RoundedCornerShape(100.dp)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(com.vending.kiosk.R.drawable.ic_qr_payment),
                    contentDescription = "Pago con QR",
                    modifier = Modifier.size(68.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Método de pago",
                    color = Color(0xFF7C78B6),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFD7E8FF), RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = methodLabel,
                        color = Color(0xFF10184A),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }
    }
}

@Composable
private fun CheckoutItemsCard(
    items: List<CartItem>,
    imageCacheVersion: Int,
    getCachedImageBitmap: (String) -> Bitmap?
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 92.dp, max = 320.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFC9DEF5))
    ) {
        if (items.isEmpty()) {
            PaymentStatus("No hay productos en el pedido")
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                itemsIndexed(items, key = { _, item -> item.planogramCellId }) { index, item ->
                    CheckoutLine(
                        item = item,
                        imageCacheVersion = imageCacheVersion,
                        getCachedImageBitmap = getCachedImageBitmap,
                        showDivider = index < items.lastIndex
                    )
                }
            }
        }
    }
}

@Composable
private fun CheckoutLine(
    item: CartItem,
    imageCacheVersion: Int,
    getCachedImageBitmap: (String) -> Bitmap?,
    showDivider: Boolean
) {
    val bitmap = remember(item.primaryImageUrl, imageCacheVersion) {
        getCachedImageBitmap(item.primaryImageUrl)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CheckoutThumbnail(bitmap = bitmap, label = item.name)
            Spacer(Modifier.width(10.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            ) {
                Text(
                    text = item.name,
                    color = Color(0xFF10184A),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.cellCode.ifBlank { "Celda ${item.physicalCell}" },
                    color = Color(0xFF7C78B6),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            CheckoutMetricDivider()
            CheckoutMetric("Precio unit.", money(item.unitPrice), 88.dp)
            CheckoutMetricDivider()
            CheckoutMetric("Cantidad", item.quantity.toString(), 64.dp)
            CheckoutMetricDivider()
            CheckoutMetric(
                label = "Total",
                value = money(item.unitPrice * item.quantity),
                width = 88.dp,
                emphasize = true
            )
        }
        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color(0xFFDCE6F5))
            )
        }
    }
}

@Composable
private fun CheckoutThumbnail(bitmap: Bitmap?, label: String) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .background(Color(0xFFEAF2FC), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = label,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp),
                contentScale = ContentScale.Fit
            )
        } else {
            Text("—", color = Color(0xFF7C78B6), fontSize = 20.sp)
        }
    }
}

@Composable
private fun CheckoutMetricDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(48.dp)
            .background(Color(0xFFDCE6F5))
    )
}

@Composable
private fun CheckoutMetric(
    label: String,
    value: String,
    width: Dp,
    emphasize: Boolean = false
) {
    Column(
        modifier = Modifier.width(width),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            color = Color(0xFF7C78B6),
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            color = Color(0xFF10184A),
            fontSize = if (emphasize) 18.sp else 16.sp,
            fontWeight = if (emphasize) FontWeight.Black else FontWeight.Medium,
            maxLines = 1
        )
    }
}

@Composable
private fun CheckoutSummary(subtotalAmount: Double, totalAmount: Double) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFC9DEF5))
    ) {
        Column(modifier = Modifier.padding(6.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Subtotal", color = Color(0xFF7C78B6), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(money(subtotalAmount), color = Color(0xFF7C78B6), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFDFF3FF), RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Total", color = Color(0xFF10184A), fontSize = 24.sp, fontWeight = FontWeight.Black)
                Text(money(totalAmount), color = Color(0xFF3155F5), fontSize = 30.sp, fontWeight = FontWeight.Black)
            }
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
    quantity = quantity
)

private val previewMethods = listOf(PaymentMethod(1, "Pago QR"))

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
