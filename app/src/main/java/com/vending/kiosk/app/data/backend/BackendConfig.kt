package com.vending.kiosk.app.data.backend

import com.vending.kiosk.BuildConfig

/**
 * Base URL of the HTTP backend.
 *
 * El default es el backend de DESARROLLO (el Laravel que corre en esta PC): mientras se integra, la
 * APK no habla con el backend viejo de produccion, que atiende otro flujo. Se puede apuntar a
 * cualquier host con `-PvendingBackendUrl=http://<host>:<puerto>` o con `vendingBackendUrl` en
 * `local.properties`, y el build de release lo fuerza al backend de DESPLIEGUE (ver
 * app/build.gradle.kts).
 */
object BackendConfig {
    /**
     * El backend LOCAL de desarrollo. `10.0.2.2` es como el emulador ve a la PC; en una tablet de la
     * red se pasa la IP de la PC con `-PvendingBackendUrl` (o en `local.properties`).
     */
    const val DEVELOPMENT_BASE_URL = "http://10.0.2.2:8000"

    val baseUrl: String = BuildConfig.BACKEND_BASE_URL.trim().ifBlank { DEVELOPMENT_BASE_URL }

    /** The versioned API prefix: `/api/v1`. */
    val apiUrl: String = baseUrl.trimEnd('/') + "/api/v1"
}
