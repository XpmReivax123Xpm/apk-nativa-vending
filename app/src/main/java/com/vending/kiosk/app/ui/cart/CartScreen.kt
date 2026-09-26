package com.vending.kiosk.app.ui.cart

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vending.kiosk.app.domain.cart.CartItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import java.util.Locale

private val CART_MINIMUM_LIST_HEIGHT = 180.dp
private val CART_BASE_LIST_HEIGHT = 360.dp
private val CART_MAXIMUM_LIST_HEIGHT = 620.dp

private fun snapCartHeight(
    currentHeightPx: Float,
    velocity: Float,
    minimumHeightPx: Float,
    baseHeightPx: Float,
    maximumHeightPx: Float
): Float {
    val snapPoints = listOf(minimumHeightPx, baseHeightPx, maximumHeightPx)
    val velocityThresholdPx = 900f

    return when {
        velocity < -velocityThresholdPx -> {
            snapPoints.firstOrNull { it > currentHeightPx + 1f } ?: maximumHeightPx
        }
        velocity > velocityThresholdPx -> {
            snapPoints.lastOrNull { it < currentHeightPx - 1f } ?: minimumHeightPx
        }
        else -> snapPoints.minByOrNull { abs(it - currentHeightPx) } ?: baseHeightPx
    }
}

@Composable
fun CartScreen(
    state: CartUiState,
    onIncrement: (planogramCellId: Int) -> Unit,
    onDecrement: (planogramCellId: Int) -> Unit,
    onRemove: (planogramCellId: Int) -> Unit,
    onClear: () -> Unit,
    onBuy: () -> Unit,
    onClose: () -> Unit,
    onUserInteraction: () -> Unit = {},
    imageCacheVersion: Int = 0,
    getCachedImageBitmap: (String) -> Bitmap? = { null },
    onPreloadImages: (Collection<String>) -> Unit = {}
) {
    val panelBlue = Color.White
    val darkBlue = Color(0xFF10184A)
    val primaryBlue = Color(0xFF1768C5)
    val hasItems = state.items.isNotEmpty()
    val density = LocalDensity.current
    val minimumListHeightPx = with(density) { CART_MINIMUM_LIST_HEIGHT.toPx() }
    val baseListHeightPx = with(density) { CART_BASE_LIST_HEIGHT.toPx() }
    val maximumListHeightPx = with(density) { CART_MAXIMUM_LIST_HEIGHT.toPx() }
    val scope = rememberCoroutineScope()
    val heightAnimation = remember(density) { Animatable(baseListHeightPx) }
    var dragHeightPx by remember(density) {
        mutableFloatStateOf(baseListHeightPx)
    }
    var isDragging by remember { mutableStateOf(false) }
    var snapJob by remember { mutableStateOf<Job?>(null) }
    val listHeightPx = if (isDragging) dragHeightPx else heightAnimation.value
    val listHeight = with(density) { listHeightPx.toDp() }

    LaunchedEffect(state.items) {
        onPreloadImages(state.items.map { it.primaryImageUrl })
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(panelBlue, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .widthIn(max = 760.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .width(72.dp)
                .height(24.dp)
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { dragAmount ->
                        dragHeightPx = (dragHeightPx - dragAmount)
                            .coerceIn(minimumListHeightPx, maximumListHeightPx)
                    },
                    onDragStarted = {
                        snapJob?.cancel()
                        dragHeightPx = heightAnimation.value
                        isDragging = true
                        onUserInteraction()
                    },
                    onDragStopped = { velocity ->
                        val currentHeightPx = dragHeightPx
                        val targetHeightPx = snapCartHeight(
                            currentHeightPx = currentHeightPx,
                            velocity = velocity,
                            minimumHeightPx = minimumListHeightPx,
                            baseHeightPx = baseListHeightPx,
                            maximumHeightPx = maximumListHeightPx
                        )
                        snapJob = scope.launch {
                            heightAnimation.snapTo(currentHeightPx)
                            isDragging = false
                            heightAnimation.animateTo(
                                targetValue = targetHeightPx,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                )
                            )
                            snapJob = null
                        }
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(42.dp)
                    .height(4.dp)
                    .background(Color(0xFFA5A4DC), RoundedCornerShape(2.dp))
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Carrito",
            modifier = Modifier.fillMaxWidth(),
            color = darkBlue,
            fontSize = 32.sp,
            lineHeight = 38.sp,
            fontWeight = FontWeight.Black
        )
        if (state.operationNotApplied) {
            Text(
                text = "No se pudo aplicar el último cambio",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                color = Color(0xFF9A5A00),
                style = MaterialTheme.typography.labelMedium
            )
        }
        Spacer(Modifier.height(10.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .height(listHeight)
        ) {
            if (hasItems) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(state.items, key = { it.planogramCellId }) { item ->
                        CartLine(
                            item = item,
                            onIncrement = {
                                onUserInteraction()
                                onIncrement(item.planogramCellId)
                            },
                            onDecrement = {
                                onUserInteraction()
                                onDecrement(item.planogramCellId)
                            },
                            primaryBlue = primaryBlue,
                            imageCacheVersion = imageCacheVersion,
                            getCachedImageBitmap = getCachedImageBitmap
                        )
                    }
                }
            } else {
                Text(
                    text = "Tu carrito está vacío",
                    modifier = Modifier.align(Alignment.Center),
                    color = Color(0xFF60738C),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        CartFooter(
            subtotalAmount = state.totalAmount,
            totalAmount = state.totalAmount,
            hasItems = hasItems,
            onBuy = {
                onUserInteraction()
                onBuy()
            },
            onClose = {
                onUserInteraction()
                onClose()
            },
            onClear = {
                onUserInteraction()
                onClear()
            },
            darkBlue = darkBlue,
            primaryBlue = primaryBlue
        )
    }
}

@Composable
private fun CartLine(
    item: CartItem,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    primaryBlue: Color,
    imageCacheVersion: Int,
    getCachedImageBitmap: (String) -> Bitmap?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, Color(0xFFDDE8F5))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CartImageSource(
                source = item.primaryImageUrl,
                label = "Imagen no disponible",
                accent = primaryBlue,
                imageCacheVersion = imageCacheVersion,
                getCachedImageBitmap = getCachedImageBitmap,
                modifier = Modifier.size(width = 88.dp, height = 62.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = item.name,
                modifier = Modifier.weight(1f),
                color = Color(0xFF10184A),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 18.sp
            )
            Spacer(Modifier.width(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                QuantityButton(
                    symbol = "−",
                    onClick = onDecrement,
                    color = primaryBlue
                )
                Text(
                    text = item.quantity.toString(),
                    modifier = Modifier.width(28.dp),
                    color = Color(0xFF10184A),
                    textAlign = TextAlign.Center,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                QuantityButton(
                    symbol = "+",
                    onClick = onIncrement,
                    color = primaryBlue
                )
            }
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(50.dp)
                    .background(Color(0xFFDDE2F1))
            )
            Spacer(Modifier.width(10.dp))
            Column(
                modifier = Modifier.width(78.dp),
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = "Total",
                    color = Color(0xFF7C78B6),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = price(item.unitPrice * item.quantity),
                    color = Color(0xFF10184A),
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun QuantityButton(
    symbol: String,
    onClick: () -> Unit,
    color: Color
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(44.dp)
            .background(color, CircleShape)
    ) {
        Text(
            text = symbol,
            color = Color.White,
            fontSize = 28.sp,
            lineHeight = 28.sp,
            fontWeight = FontWeight.Normal
        )
    }
}

@Composable
private fun CartFooter(
    subtotalAmount: Double,
    totalAmount: Double,
    hasItems: Boolean,
    onBuy: () -> Unit,
    onClose: () -> Unit,
    onClear: () -> Unit,
    darkBlue: Color,
    primaryBlue: Color
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SummaryDivider()
        SummaryRow(
            label = "Subtotal",
            amount = subtotalAmount,
            color = Color(0xFF7C78B6),
            amountSize = 20.sp
        )
        SummaryDivider()
        SummaryRow(
            label = "Total",
            amount = totalAmount,
            color = darkBlue,
            amountSize = 30.sp,
            emphasize = true
        )
        Button(
            onClick = onBuy,
            enabled = hasItems,
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF20BC6B),
                disabledContainerColor = Color(0xFFB9DCC8)
            )
        ) {
            Icon(
                painter = painterResource(com.vending.kiosk.R.drawable.ic_shopping_cart),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text("Comprar", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onClose,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                border = BorderStroke(1.dp, primaryBlue),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = primaryBlue),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    painter = painterResource(com.vending.kiosk.R.drawable.ic_arrow_back),
                    contentDescription = null,
                    modifier = Modifier.size(25.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Volver", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = onClear,
                enabled = hasItems,
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
                    painter = painterResource(com.vending.kiosk.R.drawable.ic_delete),
                    contentDescription = null,
                    modifier = Modifier.size(25.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Limpiar", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SummaryRow(
    label: String,
    amount: Double,
    color: Color,
    amountSize: androidx.compose.ui.unit.TextUnit,
    emphasize: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = color,
            fontSize = if (emphasize) 24.sp else 18.sp,
            fontWeight = if (emphasize) FontWeight.Black else FontWeight.Medium
        )
        Text(
            text = price(amount),
            color = color,
            fontSize = amountSize,
            fontWeight = if (emphasize) FontWeight.Black else FontWeight.Bold
        )
    }
}

@Composable
private fun SummaryDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color(0xFFD5DCF0))
    )
}

@Composable
private fun CartImageSource(
    source: String,
    label: String,
    accent: Color,
    imageCacheVersion: Int,
    getCachedImageBitmap: (String) -> Bitmap?,
    modifier: Modifier = Modifier
) {
    val bitmap = remember(source, imageCacheVersion) {
        getCachedImageBitmap(source)
    }

    Box(
        modifier = modifier.background(Color(0xFFEAF2FC), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = label,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        } else {
            Text(
                text = "Image\nunavailable",
                modifier = Modifier.padding(6.dp),
                color = accent.copy(alpha = 0.82f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

private fun price(value: Double): String = String.format(Locale.US, "%.2f Bs", value)

private fun previewCartItem(index: Int, quantity: Int) = CartItem(
    planogramCellId = index,
    productId = index,
    cellCode = "A-${index.toString().padStart(2, '0')}",
    name = "Product $index",
    unitPrice = 4.5 + index,
    availableStock = 10,
    isVendible = true,
    physicalCell = index,
    primaryImageUrl = "",
    quantity = quantity
)

@Preview(showBackground = true, widthDp = 540, heightDp = 760)
@Composable
private fun CartScreenPreview() {
    CartScreen(
        state = CartUiState(
            items = (1..12).map { previewCartItem(it, (it % 3) + 1) },
            totalUnits = 24,
            totalAmount = 198.0
        ),
        onIncrement = {},
        onDecrement = {},
        onRemove = {},
        onClear = {},
        onBuy = {},
        onClose = {}
    )
}

@Preview(showBackground = true, widthDp = 540, heightDp = 760)
@Composable
private fun CartScreenEmptyPreview() {
    CartScreen(
        state = CartUiState(),
        onIncrement = {},
        onDecrement = {},
        onRemove = {},
        onClear = {},
        onBuy = {},
        onClose = {}
    )
}
