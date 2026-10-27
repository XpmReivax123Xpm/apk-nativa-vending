package com.vending.kiosk.app.data.backend

import com.vending.kiosk.BuildConfig

/**
 * Base URL of the HTTP backend.
 *
 * El default es el backend YA DESPLEGADO (`backend.boxipago.com.bo`); la APK no habla con el backend
 * viejo de produccion, que atiende otro flujo. Se puede apuntar a cualquier host con
 * `-PvendingBackendUrl=http://<host>:<puerto>` o con `vendingBackendUrl` en `local.properties` (asi se
 * usa el Laravel local), y el build de release lo fuerza al backend de DESPLIEGUE (ver
 * app/build.gradle.kts).
 */
object BackendConfig {
    /**
     * Fallback cuando el build no recibe `BACKEND_BASE_URL`. `10.0.2.2` es como el emulador ve a la PC:
     * el Laravel local se usa pasando `-PvendingBackendUrl` (o `vendingBackendUrl` en `local.properties`).
     */
    const val DEVELOPMENT_BASE_URL = "https://backend.boxipago.com.bo"

    val baseUrl: String = BuildConfig.BACKEND_BASE_URL.trim().ifBlank { DEVELOPMENT_BASE_URL }

    /** The versioned API prefix: `/api/v1`. */
    val apiUrl: String = baseUrl.trimEnd('/') + "/api/v1"
}
