package com.vending.kiosk.app.data.backend

import com.vending.kiosk.BuildConfig

/**
 * Base URL of the HTTP backend.
 *
 * Default value is the production server, so nothing changes on real devices. A
 * simulation build (see docs/SIMULADOR_PC.md) points it at the local mock backend
 * so the whole kiosk flow (login, machines, catalog, QR payment, dispense) can be
 * exercised on the PC without touching the production server nor any real payment
 * gateway; the release build always forces the production URL.
 */
object BackendConfig {
    const val PRODUCTION_BASE_URL = "https://boxipagobackend.pagofacil.com.bo"

    val baseUrl: String = BuildConfig.BACKEND_BASE_URL.trim().ifBlank { PRODUCTION_BASE_URL }
}
