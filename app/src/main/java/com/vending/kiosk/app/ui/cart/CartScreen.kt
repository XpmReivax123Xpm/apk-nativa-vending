package com.vending.kiosk.app.ui.cart

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.vending.kiosk.app.domain.cart.CartItem
import java.io.File
import java.util.Locale

@Composable
fun CartScreen(
    state: CartUiState,
    onIncrement: (planogramCellId: Int) -> Unit,
    onDecrement: (planogramCellId: Int) -> Unit,
    onRemove: (planogramCellId: Int) -> Unit,
    onClear: () -> Unit,
    onBuy: () -> Unit,
    onClose: () -> Unit,
    onUserInteraction: () -> Unit = {}
) {
    val panelBlue = Color(0xFFF8FBFF)
    val darkBlue = Color(0xFF0E3B86)
    val cyan = Color(0xFF42D7F5)
    val orange = Color(0xFFF59E0B)
    val hasItems = state.items.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(panelBlue, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .widthIn(max = 760.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .width(42.dp)
                .height(4.dp)
                .background(Color(0xFFB5C5D9), RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "CARRITO",
            modifier = Modifier.fillMaxWidth(),
            color = darkBlue,
            style = MaterialTheme.typography.headlineSmall,
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
                .height(360.dp)
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
                            onRemove = {
                                onUserInteraction()
                                onRemove(item.planogramCellId)
                            },
                            accent = cyan,
                            priceColor = orange
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
            accent = cyan
        )
    }
}

@Composable
private fun CartLine(
    item: CartItem,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    onRemove: () -> Unit,
    accent: Color,
    priceColor: Color
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CartImageSource(
                source = item.primaryImageUrl,
                label = "Imagen no disponible",
                accent = accent,
                modifier = Modifier.size(74.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    color = Color(0xFF17427A),
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = price(item.unitPrice),
                    color = priceColor,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDecrement, modifier = Modifier.size(32.dp)) {
                        Text("−", color = Color(0xFF17427A), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        text = item.quantity.toString(),
                        modifier = Modifier.width(28.dp),
                        color = Color(0xFF17427A),
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Black
                    )
                    IconButton(onClick = onIncrement, modifier = Modifier.size(32.dp)) {
                        Text("+", color = accent, fontSize = 22.sp, fontWeight = FontWeight.Black)
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = price(item.unitPrice * item.quantity),
                    color = Color(0xFF17427A),
                    fontWeight = FontWeight.Black,
                    style = MaterialTheme.typography.bodyMedium
                )
                TextButton(onClick = onRemove, contentPadding = ButtonDefaults.TextButtonContentPadding) {
                    Text("ELIMINAR", color = Color(0xFF9A3A3A), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun CartFooter(
    totalAmount: Double,
    hasItems: Boolean,
    onBuy: () -> Unit,
    onClose: () -> Unit,
    onClear: () -> Unit,
    darkBlue: Color,
    accent: Color
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Total", color = darkBlue, fontWeight = FontWeight.Bold)
            Text(price(totalAmount), color = darkBlue, fontSize = 22.sp, fontWeight = FontWeight.Black)
        }
        Button(
            onClick = onBuy,
            enabled = hasItems,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = darkBlue, disabledContainerColor = Color(0xFFB5C5D9))
        ) {
            Text("COMPRAR", fontWeight = FontWeight.Black)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onClose) {
                Text("VOLVER", color = darkBlue, fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = onClear, enabled = hasItems) {
                Text("LIMPIAR", color = if (hasItems) Color(0xFF9A3A3A) else Color(0xFF9BAABD), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun CartImageSource(source: String, label: String, accent: Color, modifier: Modifier = Modifier) {
    val bitmap = remember(source) {
        source
            .takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf { it.isAbsolute && it.isFile }
            ?.let { file -> runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull() }
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
                contentScale = ContentScale.Crop
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
