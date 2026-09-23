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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CatalogUiState(
    val machineId: Int = 0,
    val machineCode: String = "",
    val machineLocation: String = "",
    val items: List<CatalogItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val sessionLost: Boolean = false
)

class CatalogViewModel(
    private val vendingBackendGateway: HttpVendingBackendGateway,
    private val catalogImageCache: CatalogImageCache,
    private val authSessionManager: AuthSessionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(CatalogUiState())
    val uiState: StateFlow<CatalogUiState> = _uiState.asStateFlow()

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

        if (currentState.machineId <= 0) {
            _uiState.value = currentState.copy(error = "Identificador de maquina invalido")
            return
        }

        val machineId = currentState.machineId
        _uiState.value = currentState.copy(isLoading = true, error = null, sessionLost = false)

        viewModelScope.launch {
            try {
                when (val result = withContext(Dispatchers.IO) { loadCatalogData(machineId) }) {
                    is CatalogLoadResult.Success -> _uiState.value = _uiState.value.copy(
                        items = result.items.filter { it.isVendible },
                        error = null,
                        sessionLost = false
                    )

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

    private suspend fun loadCatalogData(machineId: Int): CatalogLoadResult {
        if (resolveValidAuthHeader().isNullOrBlank()) {
            return CatalogLoadResult.SessionLost
        }

        val catalogData = try {
            vendingBackendGateway.fetchCatalogData(machineId.toLong())
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
                vendingBackendGateway.fetchCatalogData(machineId.toLong())
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

        val resolvedItems = catalogData.items.mapIndexed { index, item ->
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
                ),
                secondaryImageUrl = catalogImageCache.resolveImageSourceForCache(
                    slot = "${slotBase}_secondary",
                    incomingId = item.secondaryImageId,
                    remoteUrl = item.secondaryImageUrl,
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
