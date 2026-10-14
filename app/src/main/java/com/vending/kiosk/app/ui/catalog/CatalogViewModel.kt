package com.vending.kiosk.app.ui.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vending.kiosk.app.data.backend.CatalogGatewayException
import com.vending.kiosk.app.data.backend.HttpVendingBackendGateway
import com.vending.kiosk.app.data.backend.MachineAuthGateway
import com.vending.kiosk.app.data.backend.MachineLoginResult
import com.vending.kiosk.app.data.images.CatalogImageCache
import com.vending.kiosk.app.data.session.AuthSessionManager
import com.vending.kiosk.app.domain.catalog.CatalogItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

private const val CATALOG_BITMAP_TARGET_SIZE_PX = 480
private const val CART_THUMBNAIL_TARGET_SIZE_PX = 192

data class CatalogUiState(
    val machineId: Int = 0,
    val machineCode: String = "",
    val machineLocation: String = "",
    val items: List<CatalogItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val sessionLost: Boolean = false,
    val imageCacheVersion: Int = 0,
    val cartImageCacheVersion: Int = 0
)

class CatalogViewModel(
    private val vendingBackendGateway: HttpVendingBackendGateway,
    private val catalogImageCache: CatalogImageCache,
    private val authSessionManager: AuthSessionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(CatalogUiState())
    val uiState: StateFlow<CatalogUiState> = _uiState.asStateFlow()
    private val cartImagesBeingPreloaded = ConcurrentHashMap.newKeySet<String>()
    private var catalogPreloadJob: Job? = null
    private var lastCatalogPage: Int? = null

    fun configureMachine(machineId: Int, machineCode: String, machineLocation: String) {
        _uiState.value = _uiState.value.copy(
            machineId = machineId,
            machineCode = machineCode,
            machineLocation = machineLocation
        )
    }

    fun loadCatalog() {
        val currentState = _uiState.value
        if (currentState.isLoading) return

        _uiState.value = currentState.copy(isLoading = true, error = null, sessionLost = false)

        viewModelScope.launch {
            try {
                when (val result = withContext(Dispatchers.IO) { loadCatalogData() }) {
                    is CatalogLoadResult.Success -> {
                        catalogPreloadJob?.cancel()
                        catalogPreloadJob = null
                        lastCatalogPage = null
                        _uiState.value = _uiState.value.copy(
                            items = result.items,
                            error = null,
                            sessionLost = false
                        )
                        onCatalogPageChanged(0)
                    }

                    CatalogLoadResult.SessionLost -> _uiState.value = _uiState.value.copy(
                        error = "Sesion de maquina expirada",
                        sessionLost = true
                    )

                    is CatalogLoadResult.Failure -> _uiState.value = _uiState.value.copy(
                        error = result.message
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = exception.message ?: "No se pudo cargar el catalogo"
                )
            } finally {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    fun getCachedImageBitmap(localPath: String) = catalogImageCache.getCachedBitmap(localPath)

    fun getCachedCartImageBitmap(localPath: String) =
        catalogImageCache.getCachedCartThumbnail(localPath, CART_THUMBNAIL_TARGET_SIZE_PX)

    fun preloadCartImages(localPaths: Collection<String>) {
        val pathsToPreload = localPaths
            .filter { it.isNotBlank() && catalogImageCache.isLocalImagePath(it) }
            .distinct()
            .filter { path ->
                catalogImageCache.getCachedCartThumbnail(path, CART_THUMBNAIL_TARGET_SIZE_PX) == null &&
                    cartImagesBeingPreloaded.add(path)
            }
        if (pathsToPreload.isEmpty()) return

        viewModelScope.launch {
            try {
                val loadedPaths = withContext(Dispatchers.IO) {
                    catalogImageCache.preloadLocalCartThumbnails(
                        localPaths = pathsToPreload,
                        targetSizePx = CART_THUMBNAIL_TARGET_SIZE_PX
                    )
                }
                if (loadedPaths.isNotEmpty()) {
                    val currentState = _uiState.value
                    _uiState.value = currentState.copy(
                        cartImageCacheVersion = currentState.cartImageCacheVersion + 1
                    )
                }
            } finally {
                pathsToPreload.forEach { path -> cartImagesBeingPreloaded.remove(path) }
            }
        }
    }

    fun onCatalogPageChanged(pageIndex: Int) {
        val items = _uiState.value.items
        if (items.isEmpty()) return

        val pageCount = (items.size + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE
        val currentPage = pageIndex.coerceIn(0, pageCount - 1)
        val previousPage = lastCatalogPage
        if (previousPage == currentPage && catalogPreloadJob?.isActive == true) return

        catalogPreloadJob?.cancel()
        lastCatalogPage = currentPage

        // Keep only the current page and the page in the swipe direction hot.
        val adjacentPage = when {
            previousPage == null -> currentPage + 1
            currentPage > previousPage -> currentPage + 1
            currentPage < previousPage -> currentPage - 1
            else -> null
        }?.takeIf { it in 0 until pageCount }
        val pagesToPreload = buildList {
            adjacentPage?.let(::add)
            add(currentPage)
        }
        val localImagePaths = pagesToPreload
            .flatMap { page ->
                val firstItemIndex = page * ITEMS_PER_PAGE
                items.drop(firstItemIndex).take(ITEMS_PER_PAGE).map { it.primaryImageUrl }
            }
            .filter { it.isNotBlank() && catalogImageCache.isLocalImagePath(it) }
            .distinct()
        val pathsToPreload = localImagePaths.filter { path ->
            !catalogImageCache.isBitmapPrepared(path, CATALOG_BITMAP_TARGET_SIZE_PX)
        }
        if (pathsToPreload.isEmpty()) return

        catalogPreloadJob = viewModelScope.launch {
            val loadedPaths = withContext(Dispatchers.IO) {
                catalogImageCache.preloadLocalImages(
                    localPaths = pathsToPreload,
                    targetSizePx = CATALOG_BITMAP_TARGET_SIZE_PX
                )
            }
            currentCoroutineContext().ensureActive()
            if (loadedPaths.isNotEmpty()) {
                val currentState = _uiState.value
                _uiState.value = currentState.copy(
                    imageCacheVersion = currentState.imageCacheVersion + 1
                )
            }
        }
    }

    /**
     * El planograma lo pide el TOKEN, no la URL: la maquina del kiosko no manda su id.
     */
    private suspend fun loadCatalogData(): CatalogLoadResult {
        if (resolveValidAuthHeader().isNullOrBlank()) {
            return CatalogLoadResult.SessionLost
        }

        val catalogData = try {
            vendingBackendGateway.fetchCatalogData()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: CatalogGatewayException) {
            if (!exception.unauthorized) {
                return CatalogLoadResult.Failure(exception.message)
            }

            if (resolveValidAuthHeader(forceRefresh = true).isNullOrBlank()) {
                return CatalogLoadResult.SessionLost
            }

            try {
                vendingBackendGateway.fetchCatalogData()
            } catch (retryException: CancellationException) {
                throw retryException
            } catch (retryException: CatalogGatewayException) {
                if (retryException.unauthorized) {
                    return CatalogLoadResult.SessionLost
                }
                return CatalogLoadResult.Failure(retryException.message)
            } catch (retryException: Exception) {
                return CatalogLoadResult.Failure(
                    retryException.message ?: "No se pudo cargar el catalogo"
                )
            }
        } catch (exception: Exception) {
            return CatalogLoadResult.Failure(exception.message ?: "No se pudo cargar el catalogo")
        }

        val resolvedItems = catalogData.items.mapIndexedNotNull { index, item ->
            if (!item.isVendible) return@mapIndexedNotNull null

            val slotBase = when {
                item.productId > 0 -> "product_${item.productId}"
                else -> "cell_${item.planogramCellId.takeIf { it != 0 } ?: index}"
            }
            item.copy(
                primaryImageUrl = catalogImageCache.resolveImageSourceForCache(
                    slot = "${slotBase}_principal",
                    incomingId = item.imageId,
                    remoteUrl = item.primaryImageUrl,
                    targetSizePx = 480
                )
            )
        }
        return CatalogLoadResult.Success(resolvedItems)
    }

    private fun resolveValidAuthHeader(forceRefresh: Boolean = false): String? {
        if (!forceRefresh) {
            authSessionManager.getAuthorizationHeader()?.let { return it }
        }

        return when (MachineAuthGateway.refreshSessionWithStoredMachineCredentials(authSessionManager)) {
            is MachineLoginResult.Success -> authSessionManager.getAuthorizationHeader()
            is MachineLoginResult.Error -> null
        }
    }
}

private sealed interface CatalogLoadResult {
    data class Success(
        val items: List<CatalogItem>
    ) : CatalogLoadResult

    data class Failure(val message: String) : CatalogLoadResult

    data object SessionLost : CatalogLoadResult
}
