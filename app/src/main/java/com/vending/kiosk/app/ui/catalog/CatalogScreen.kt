package com.vending.kiosk.app.ui.catalog

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vending.kiosk.app.domain.catalog.CatalogItem
import kotlinx.coroutines.delay
import android.graphics.BitmapFactory
import java.io.File

private const val ITEMS_PER_PAGE = 6
private const val PROMOTION_ADVANCE_DELAY_MS = 5_000L

@Composable
fun CatalogScreen(
    state: CatalogUiState,
    cartQuantities: Map<Int, Int> = emptyMap(),
    cartTotalUnits: Int = 0,
    cartTotalAmount: Double = 0.0,
    onIncrementProduct: (CatalogItem) -> Unit = {},
    onDecrementProduct: (CatalogItem) -> Unit = {},
    onCartClick: () -> Unit = {}
) {
    val primaryBlue = Color(0xFF0E3B86)
    val backgroundBlue = Color(0xFF071D3B)
    val cyan = Color(0xFF42D7F5)
    val orange = Color(0xFFF59E0B)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(primaryBlue, backgroundBlue)))
            .padding(horizontal = 20.dp, vertical = 16.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 760.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CatalogHeader(
                machineCode = state.machineCode,
                machineLocation = state.machineLocation,
                cyan = cyan
            )

            Box(modifier = Modifier.weight(1f)) {
                when {
                    state.error != null -> CatalogStatus(message = state.error)
                    state.isLoading -> CatalogLoading(orange = orange)
                    state.items.isEmpty() -> CatalogStatus(message = "No hay productos disponibles")
                    else -> CatalogContent(
                        state = state,
                        cartQuantities = cartQuantities,
                        onIncrementProduct = onIncrementProduct,
                        onDecrementProduct = onDecrementProduct,
                        cyan = cyan,
                        orange = orange
                    )
                }
            }
            CatalogCartBar(
                totalUnits = cartTotalUnits,
                totalAmount = cartTotalAmount,
                onClick = onCartClick,
                cyan = cyan,
                orange = orange
            )
        }
    }
}

@Composable
private fun CatalogHeader(machineCode: String, machineLocation: String, cyan: Color) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xCC1E4FA9))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = machineCode,
                modifier = Modifier.weight(1f),
                color = cyan,
                fontSize = 20.sp,
                fontWeight = FontWeight.Black
            )
            Text(
                text = machineLocation,
                modifier = Modifier.weight(1f),
                color = Color(0xFFD6E9FF),
                textAlign = TextAlign.End,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun CatalogContent(
    state: CatalogUiState,
    cartQuantities: Map<Int, Int>,
    onIncrementProduct: (CatalogItem) -> Unit,
    onDecrementProduct: (CatalogItem) -> Unit,
    cyan: Color,
    orange: Color
) {
    var contentVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        contentVisible = true
    }

    androidx.compose.animation.AnimatedVisibility(
        visible = contentVisible,
        enter = fadeIn(tween(220)),
        exit = fadeOut(tween(120))
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PromotionCarousel(promotions = state.promotions, cyan = cyan)
            ProductPager(
                items = state.items,
                cartQuantities = cartQuantities,
                onIncrementProduct = onIncrementProduct,
                onDecrementProduct = onDecrementProduct,
                cyan = cyan,
                orange = orange
            )
        }
    }
}

@Composable
private fun PromotionCarousel(promotions: List<String>, cyan: Color) {
    var promotionIndex by remember { mutableIntStateOf(0) }
    val safePromotionIndex = promotionIndex.coerceIn(0, (promotions.size - 1).coerceAtLeast(0))

    LaunchedEffect(promotions) {
        promotionIndex = safePromotionIndex
    }
    LaunchedEffect(promotions.size) {
        if (promotions.size > 1) {
            while (true) {
                delay(PROMOTION_ADVANCE_DELAY_MS)
                promotionIndex = (promotionIndex + 1) % promotions.size
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(128.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF123A70))
    ) {
        if (promotions.isEmpty()) {
            CatalogImageSource(source = null, label = "Promociones no disponibles", accent = cyan)
        } else {
            AnimatedContent(
                targetState = safePromotionIndex,
                transitionSpec = {
                    fadeIn(tween(180)) togetherWith fadeOut(tween(140))
                },
                label = "promotionCarousel"
            ) { index ->
                CatalogImageSource(
                    source = promotions[index],
                    label = "Promoción ${index + 1}",
                    accent = cyan,
                    contentScale = ContentScale.Crop
                )
            }
        }
    }
}

@Composable
private fun ProductPager(
    items: List<CatalogItem>,
    cartQuantities: Map<Int, Int>,
    onIncrementProduct: (CatalogItem) -> Unit,
    onDecrementProduct: (CatalogItem) -> Unit,
    cyan: Color,
    orange: Color
) {
    val pageCount = ((items.size + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE).coerceAtLeast(1)
    var currentPage by remember { mutableIntStateOf(0) }
    val safePage = currentPage.coerceIn(0, pageCount - 1)

    LaunchedEffect(items, pageCount) {
        currentPage = safePage
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PageArrow(
            symbol = "‹",
            enabled = safePage > 0,
            onClick = { currentPage = safePage - 1 }
        )
        AnimatedContent(
            targetState = safePage,
            modifier = Modifier.weight(1f),
            transitionSpec = {
                fadeIn(tween(160)) togetherWith fadeOut(tween(120))
            },
            label = "catalogPage"
        ) { page ->
            ProductGrid(
                items = items.drop(page * ITEMS_PER_PAGE).take(ITEMS_PER_PAGE),
                cartQuantities = cartQuantities,
                onIncrementProduct = onIncrementProduct,
                onDecrementProduct = onDecrementProduct,
                cyan = cyan,
                orange = orange
            )
        }
        PageArrow(
            symbol = "›",
            enabled = safePage < pageCount - 1,
            onClick = { currentPage = safePage + 1 }
        )
    }
}

@Composable
private fun PageArrow(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(36.dp)) {
        Text(
            text = symbol,
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.28f),
            fontSize = 30.sp,
            fontWeight = FontWeight.Light
        )
    }
}

@Composable
private fun ProductGrid(
    items: List<CatalogItem>,
    cartQuantities: Map<Int, Int>,
    onIncrementProduct: (CatalogItem) -> Unit,
    onDecrementProduct: (CatalogItem) -> Unit,
    cyan: Color,
    orange: Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(2) { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                repeat(3) { column ->
                    val item = items.getOrNull(row * 3 + column)
                    if (item == null) {
                        Spacer(Modifier.weight(1f))
                    } else {
                        ProductCard(
                            item = item,
                            quantity = cartQuantities[item.planogramCellId] ?: 0,
                            onIncrement = { onIncrementProduct(item) },
                            onDecrement = { onDecrementProduct(item) },
                            cyan = cyan,
                            orange = orange,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProductCard(
    item: CatalogItem,
    quantity: Int,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    cyan: Color,
    orange: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .aspectRatio(0.78f),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FBFF)),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        border = if (quantity > 0) BorderStroke(2.dp, cyan) else null
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = item.cellCode,
                modifier = Modifier.padding(start = 10.dp, top = 8.dp),
                color = Color(0xFF17427A),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            CatalogImageSource(
                source = item.primaryImageUrl,
                label = "Imagen no disponible",
                accent = cyan,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                contentScale = ContentScale.Crop
            )
            Text(
                text = if (item.unitPrice > 0.0) "Bs ${"%.2f".format(item.unitPrice)}" else "Sin precio",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 9.dp),
                color = if (item.unitPrice > 0.0) orange else Color(0xFF60738C),
                textAlign = TextAlign.Center,
                fontSize = 17.sp,
                fontWeight = FontWeight.Black
            )
            ProductQuantityControl(
                quantity = quantity,
                onIncrement = onIncrement,
                onDecrement = onDecrement,
                accent = cyan
            )
        }
    }
}

@Composable
private fun ProductQuantityControl(
    quantity: Int,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    accent: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (quantity > 0) {
            IconButton(onClick = onDecrement, modifier = Modifier.size(30.dp)) {
                Text(text = "−", color = Color(0xFF17427A), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                text = quantity.toString(),
                color = Color(0xFF17427A),
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 24.dp)
            )
        }
        IconButton(onClick = onIncrement, modifier = Modifier.size(30.dp)) {
            Text(text = "+", color = accent, fontSize = 22.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun CatalogCartBar(
    totalUnits: Int,
    totalAmount: Double,
    onClick: () -> Unit,
    cyan: Color,
    orange: Color
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xE615437D)),
        border = BorderStroke(1.dp, cyan.copy(alpha = 0.6f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(contentAlignment = Alignment.TopEnd) {
                    Text(text = "🛒", fontSize = 24.sp)
                    if (totalUnits > 0) {
                        Text(
                            text = totalUnits.toString(),
                            modifier = Modifier
                                .background(orange, RoundedCornerShape(10.dp))
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
                Spacer(Modifier.size(10.dp))
                Text(
                    text = "Cart",
                    color = Color(0xFFD6E9FF),
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = "%.2f Bs".format(totalAmount),
                color = orange,
                fontSize = 20.sp,
                fontWeight = FontWeight.Black
            )
        }
    }
}

@Composable
private fun CatalogImageSource(
    source: String?,
    label: String,
    accent: Color,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    val bitmap = remember(source) {
        source
            ?.takeIf { it.isNotBlank() }
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
                contentScale = contentScale
            )
        } else {
            Text(
                text = if (source.isNullOrBlank()) label else "$label\nVista previa no disponible",
                modifier = Modifier.padding(8.dp),
                color = accent.copy(alpha = 0.82f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun CatalogLoading(orange: Color) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = orange, modifier = Modifier.size(34.dp))
            Spacer(Modifier.height(12.dp))
            Text("Cargando catálogo...", color = Color.White)
        }
    }
}

@Composable
private fun CatalogStatus(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            color = Color(0xFFDFF0FF),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

private fun previewItem(index: Int) = CatalogItem(
    planogramCellId = index,
    productId = index,
    cellCode = "A-${index.toString().padStart(2, '0')}",
    name = "Producto $index",
    unitPrice = 5.0 + index,
    availableStock = 4,
    isVendible = true,
    primaryImageUrl = "",
    secondaryImageUrl = "",
    physicalCell = index
)

@Preview(showBackground = true, widthDp = 540, heightDp = 960)
@Composable
private fun CatalogScreenEmptyCartPreview() {
    CatalogScreen(
        state = CatalogUiState(
            machineCode = "MQ-1001",
            machineLocation = "Edificio Central",
            items = (1..6).map(::previewItem),
            promotions = listOf("promo-1", "promo-2")
        )
    )
}

@Preview(showBackground = true, widthDp = 540, heightDp = 960)
@Composable
private fun CatalogScreenSelectedProductsPaginationPreview() {
    CatalogScreen(
        state = CatalogUiState(
            machineCode = "MQ-1002",
            machineLocation = "Sucursal Norte",
            items = (1..8).map(::previewItem),
            promotions = listOf("promo-1")
        ),
        cartQuantities = mapOf(1 to 1, 3 to 2, 7 to 3),
        cartTotalUnits = 6,
        cartTotalAmount = 57.0
    )
}

@Preview(showBackground = true, widthDp = 540, heightDp = 960)
@Composable
private fun CatalogScreenLoadingPreview() {
    CatalogScreen(
        state = CatalogUiState(machineCode = "MQ-1003", machineLocation = "Depósito", isLoading = true)
    )
}

@Preview(showBackground = true, widthDp = 540, heightDp = 960)
@Composable
private fun CatalogScreenEmptyPreview() {
    CatalogScreen(
        state = CatalogUiState(machineCode = "MQ-1004", machineLocation = "Sucursal Sur")
    )
}
