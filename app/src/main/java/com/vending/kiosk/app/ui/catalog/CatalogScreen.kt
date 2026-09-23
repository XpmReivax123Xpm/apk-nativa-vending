package com.vending.kiosk.app.ui.catalog

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vending.kiosk.R
import com.vending.kiosk.app.domain.catalog.CatalogItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import android.graphics.Bitmap

internal const val ITEMS_PER_PAGE = 9
private const val PROMOTION_ADVANCE_DELAY_MS = 5_000L

@Composable
fun CatalogScreen(
    state: CatalogUiState,
    cartQuantities: Map<Int, Int> = emptyMap(),
    cartTotalUnits: Int = 0,
    cartTotalAmount: Double = 0.0,
    onIncrementProduct: (CatalogItem) -> Unit = {},
    onDecrementProduct: (CatalogItem) -> Unit = {},
    onCartClick: () -> Unit = {},
    onPayClick: () -> Unit = {},
    imageCacheVersion: Int = 0,
    getCachedImageBitmap: (String) -> Bitmap? = { null },
    onCatalogPageChanged: (Int) -> Unit = {}
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
                        orange = orange,
                        imageCacheVersion = imageCacheVersion,
                        getCachedImageBitmap = getCachedImageBitmap,
                        onCatalogPageChanged = onCatalogPageChanged
                    )
                }
            }
            CatalogCartBar(
                totalUnits = cartTotalUnits,
                totalAmount = cartTotalAmount,
                onCartClick = onCartClick,
                onPayClick = onPayClick,
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
    orange: Color,
    imageCacheVersion: Int,
    getCachedImageBitmap: (String) -> Bitmap?,
    onCatalogPageChanged: (Int) -> Unit
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
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PromotionCarousel(promotions = LocalPromotions.items, cyan = cyan)
            ProductPager(
                modifier = Modifier.weight(1f),
                items = state.items,
                cartQuantities = cartQuantities,
                onIncrementProduct = onIncrementProduct,
                onDecrementProduct = onDecrementProduct,
                cyan = cyan,
                orange = orange,
                imageCacheVersion = imageCacheVersion,
                getCachedImageBitmap = getCachedImageBitmap,
                onCatalogPageChanged = onCatalogPageChanged
            )
        }
    }
}

@Composable
private fun PromotionCarousel(promotions: List<LocalPromotion>, cyan: Color) {
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
            .height(320.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF123A70))
    ) {
        if (promotions.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Promociones locales no configuradas",
                    color = cyan,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            AnimatedContent(
                targetState = safePromotionIndex,
                transitionSpec = {
                    fadeIn(tween(180)) togetherWith fadeOut(tween(140))
                },
                label = "promotionCarousel"
            ) { index ->
                LocalPromotionImage(
                    promotion = promotions[index],
                    contentDescription = "Promoción local ${index + 1}"
                )
            }
        }
    }
}

@Composable
private fun LocalPromotionImage(
    promotion: LocalPromotion,
    contentDescription: String
) {
    Image(
        painter = painterResource(id = promotion.imageRes),
        contentDescription = contentDescription,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProductPager(
    modifier: Modifier = Modifier,
    items: List<CatalogItem>,
    cartQuantities: Map<Int, Int>,
    onIncrementProduct: (CatalogItem) -> Unit,
    onDecrementProduct: (CatalogItem) -> Unit,
    cyan: Color,
    orange: Color,
    imageCacheVersion: Int,
    getCachedImageBitmap: (String) -> Bitmap?,
    onCatalogPageChanged: (Int) -> Unit
) {
    val pageCount = ((items.size + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE).coerceAtLeast(1)
    val pagerState = rememberPagerState(pageCount = { pageCount })
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(items, pageCount) {
        val lastPage = (pageCount - 1).coerceAtLeast(0)
        if (pagerState.currentPage > lastPage) {
            pagerState.scrollToPage(lastPage)
        }
    }

    LaunchedEffect(pagerState, items, pageCount) {
        snapshotFlow { pagerState.currentPage }
            .collect { pageIndex -> onCatalogPageChanged(pageIndex) }
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PageArrow(
            symbol = "‹",
            enabled = pagerState.currentPage > 0,
            onClick = {
                coroutineScope.launch {
                    pagerState.animateScrollToPage(pagerState.currentPage - 1)
                }
            }
        )
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            userScrollEnabled = pageCount > 1
        ) { page ->
            ProductGrid(
                modifier = Modifier.fillMaxSize(),
                items = items.drop(page * ITEMS_PER_PAGE).take(ITEMS_PER_PAGE),
                cartQuantities = cartQuantities,
                onIncrementProduct = onIncrementProduct,
                onDecrementProduct = onDecrementProduct,
                cyan = cyan,
                orange = orange,
                imageCacheVersion = imageCacheVersion,
                getCachedImageBitmap = getCachedImageBitmap
            )
        }
        PageArrow(
            symbol = "›",
            enabled = pagerState.currentPage < pageCount - 1,
            onClick = {
                coroutineScope.launch {
                    val nextPage = (pagerState.currentPage + 1).coerceAtMost(pageCount - 1)
                    pagerState.animateScrollToPage(nextPage)
                }
            }
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
    modifier: Modifier = Modifier,
    items: List<CatalogItem>,
    cartQuantities: Map<Int, Int>,
    onIncrementProduct: (CatalogItem) -> Unit,
    onDecrementProduct: (CatalogItem) -> Unit,
    cyan: Color,
    orange: Color,
    imageCacheVersion: Int,
    getCachedImageBitmap: (String) -> Bitmap?
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        repeat(3) { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                repeat(3) { column ->
                    val item = items.getOrNull(row * 3 + column)
                    if (item == null) {
                        Spacer(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                    } else {
                        ProductCard(
                            item = item,
                            quantity = cartQuantities[item.planogramCellId] ?: 0,
                            onIncrement = { onIncrementProduct(item) },
                            onDecrement = { onDecrementProduct(item) },
                            cyan = cyan,
                            orange = orange,
                            imageCacheVersion = imageCacheVersion,
                            getCachedImageBitmap = getCachedImageBitmap,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
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
    imageCacheVersion: Int,
    getCachedImageBitmap: (String) -> Bitmap?,
    modifier: Modifier = Modifier
) {
    val isSelected = quantity > 0

    Card(
        modifier = modifier.clickable(onClick = onIncrement),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) Color(0xFFFFF4E5) else Color(0xFFF8FBFF)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        border = if (isSelected) {
            BorderStroke(6.dp, Color(0xFFFF6D00))
        } else {
            null
        }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                CatalogImageSource(
                    source = item.primaryImageUrl,
                    label = "Imagen no disponible",
                    accent = cyan,
                    imageCacheVersion = imageCacheVersion,
                    getCachedImageBitmap = getCachedImageBitmap,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                Text(
                    text = item.cellCode,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(Color.White, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    color = Color(0xFF17427A),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ProductQuantityControl(
                    quantity = quantity,
                    onDecrement = onDecrement,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (item.unitPrice > 0.0) "Bs ${"%.2f".format(item.unitPrice)}" else "Sin precio",
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(start = 8.dp),
                    color = if (item.unitPrice > 0.0) orange else Color(0xFF60738C),
                    textAlign = TextAlign.End,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }
    }
}

@Composable
private fun ProductQuantityControl(
    quantity: Int,
    onDecrement: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.height(52.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (quantity > 0) {
            IconButton(
                onClick = onDecrement,
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = "Disminuir cantidad" }
            ) {
                Box(
                    modifier = Modifier
                        .width(24.dp)
                        .height(5.dp)
                        .background(Color(0xFF17427A), CircleShape)
                )
            }
            Text(
                text = quantity.toString(),
                color = Color(0xFF17427A),
                fontWeight = FontWeight.Black,
                fontSize = 22.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 40.dp)
            )
        }
    }
}

@Composable
private fun CatalogCartBar(
    totalUnits: Int,
    totalAmount: Double,
    onCartClick: () -> Unit,
    onPayClick: () -> Unit,
    orange: Color
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(104.dp)
            .clickable(onClick = onCartClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(58.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_shopping_cart),
                    contentDescription = "CARRITO",
                    modifier = Modifier.size(50.dp)
                )
                if (totalUnits > 0) {
                    Text(
                        text = totalUnits.toString(),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .background(Color(0xFFE91E4D), CircleShape)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(52.dp)
                    .background(Color(0xFFD7DCE8))
            )
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "TOTAL:",
                    color = Color(0xFF7A86A7),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = "%.2f Bs".format(totalAmount),
                    modifier = Modifier.weight(1f),
                    color = Color(0xFF16213E),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black
                )
            }
            Box(
                modifier = Modifier
                    .width(200.dp)
                    .height(72.dp)
                    .pointerInput(totalUnits) {
                        if (totalUnits <= 0) {
                            detectTapGestures(onTap = {})
                        }
                    }
            ) {
                Button(
                    onClick = onPayClick,
                    enabled = totalUnits > 0,
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = orange,
                        contentColor = Color.White,
                        disabledContainerColor = Color(0xFFD9DEE8),
                        disabledContentColor = Color(0xFF7A86A7)
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "PAGAR",
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .background(Color.White, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "›",
                                color = if (totalUnits > 0) orange else Color(0xFF9AA3B5),
                                fontSize = 36.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogImageSource(
    source: String?,
    label: String,
    accent: Color,
    imageCacheVersion: Int,
    getCachedImageBitmap: (String) -> Bitmap?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    val bitmap = remember(source, imageCacheVersion) {
        source
            ?.takeIf { it.isNotBlank() }
            ?.let(getCachedImageBitmap)
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
    physicalCell = index
)

@Preview(showBackground = true, widthDp = 540, heightDp = 960)
@Composable
private fun CatalogScreenEmptyCartPreview() {
    CatalogScreen(
        state = CatalogUiState(
            machineCode = "MQ-1001",
            machineLocation = "Edificio Central",
            items = (1..6).map(::previewItem)
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
            items = (1..8).map(::previewItem)
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
