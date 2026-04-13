# Reporte técnico de diagnóstico (sin cambios de código)

## Arquitectura encontrada

### Secciones principales identificadas

- **Vending Kiosk**
  - Activity de entrada al flujo kiosk: `app/src/main/java/com/vending/kiosk/app/KioskLoginActivity.kt`
  - Activity de selección de máquina: `app/src/main/java/com/vending/kiosk/app/KioskMachinesActivity.kt`
  - Activity de planograma/productos + pago + intento de lock: `app/src/main/java/com/vending/kiosk/app/KioskCatalogActivity.kt`
  - Layouts principales:
    - `app/src/main/res/layout/activity_kiosk_login.xml`
    - `app/src/main/res/layout/activity_kiosk_machines.xml`
    - `app/src/main/res/layout/activity_kiosk_catalog.xml`
    - `app/src/main/res/layout/activity_kiosk_catalog_legacy.xml`
    - `app/src/main/res/layout/item_catalog_cell.xml`
    - modales asociados: `dialog_payment_method.xml`, `dialog_checkout_qr.xml`, `dialog_generating_qr.xml`, `dialog_qr_payment.xml`, `dialog_product_detail.xml`, `dialog_cart.xml`, `dialog_unlock_pin.xml`

- **Vending Tester**
  - Activity: `app/src/main/java/com/vending/kiosk/app/VendingTesterActivity.kt`
  - Layout: `app/src/main/res/layout/activity_vending_tester.xml`

- **Vending Calibrator**
  - Activity: `app/src/main/java/com/vending/kiosk/app/VendingCalibratorActivity.kt`
  - Layout: `app/src/main/res/layout/activity_vending_calibrator.xml`

### Hallazgos estructurales

- No hay `Fragment` en `app/src/main/java` para este flujo.
- No hay `Composable` / Jetpack Compose.
- Navegación basada en Activities + `Intent`.

---

## Flujo exacto de Vending Kiosk

1. **Selector principal** (`MainActivity`)
   - Archivo: `app/src/main/java/com/vending/kiosk/app/MainActivity.kt`
   - `btnVendingKiosk` abre `KioskLoginActivity`.

2. **Login**
   - Archivo: `app/src/main/java/com/vending/kiosk/app/KioskLoginActivity.kt`
   - `attemptLogin()` -> `doLogin()` a `POST /api/login`.
   - En éxito navega a `KioskMachinesActivity`.

3. **Selección de máquina**
   - Archivo: `app/src/main/java/com/vending/kiosk/app/KioskMachinesActivity.kt`
   - `fetchMachines()` a `GET /api/maquinas`.
   - `renderMachines()` arma tarjetas y al tocar una activa abre `KioskCatalogActivity` con extras:
     - `extra_machine_id`
     - `extra_machine_code`
     - `extra_machine_location`

4. **Planograma / productos / pago (pantalla crítica)**
   - Archivo: `app/src/main/java/com/vending/kiosk/app/KioskCatalogActivity.kt`
   - `initializeScreen()` decide layout:
     - normal: `activity_kiosk_catalog.xml`
     - legacy (Android <= 7.1.2 API 25): `activity_kiosk_catalog_legacy.xml`
   - `loadCatalog()` -> `fetchCatalog()` con `GET /api/maquinas/{id}/planograma`.
   - `renderCatalog()` infla `item_catalog_cell.xml` y muestra productos vendibles.

---

## Implementación actual del bloqueo

Toda la implementación de lock está en `KioskCatalogActivity`:

1. **Activación de lock al entrar al planograma**
   - `initializeScreen()` llama:
     - `setupUnlockGestureOnMachineTitle()`
     - `enterKioskMode()`
   - Referencia: `app/src/main/java/com/vending/kiosk/app/KioskCatalogActivity.kt` (líneas ~214-215)

2. **`enterKioskMode()`**
   - `kioskLocked = true`
   - `window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)`
   - `applyImmersiveKioskUi()`
   - intenta `startLockTask()` dentro de `runCatching`
   - Referencia: `KioskCatalogActivity.kt` líneas ~321-329

3. **Ocultamiento UI sistema (immersive)**
   - `applyImmersiveKioskUi()` usa:
     - `SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION`
     - `SYSTEM_UI_FLAG_HIDE_NAVIGATION`
     - `SYSTEM_UI_FLAG_FULLSCREEN`
     - `SYSTEM_UI_FLAG_IMMERSIVE_STICKY`
   - Referencia: `KioskCatalogActivity.kt` líneas ~341-357

4. **Bloqueo parcial de teclas/app switch dentro de la activity**
   - `dispatchKeyEvent()` intercepta:
     - `KEYCODE_BACK`
     - `KEYCODE_APP_SWITCH`
     - `KEYCODE_MENU`
   - sólo cuando `kioskLocked == true`
   - Referencia: `KioskCatalogActivity.kt` líneas ~266-274

5. **Bloqueo de back**
   - `onBackPressed()` muestra toast y no sale cuando `kioskLocked`
   - Referencia: `KioskCatalogActivity.kt` líneas ~286-291

6. **No se encontraron estas piezas en código**
   - `onKeyDown`: **no implementado**
   - `onUserLeaveHint`: **no implementado**
   - `WindowInsets`: **no uso explícito**
   - `setShowWhenLocked` / `setTurnScreenOn`: **no uso**
   - `FLAG_FULLSCREEN`: **no por Window flags** (sí por `systemUiVisibility`)

---

## Mecanismo actual de desbloqueo por PIN

1. **Gesto de desbloqueo (long press 2s en nombre de máquina)**
   - `setupUnlockGestureOnMachineTitle()` pone `OnTouchListener` en `tvCatalogTitle`.
   - En `ACTION_DOWN` lanza `postDelayed(..., 2000L)`.
   - En `ACTION_UP`/`ACTION_CANCEL` cancela si no completó.
   - Si completa, llama `showUnlockPinDialog()`.
   - Referencia: `KioskCatalogActivity.kt` líneas ~294-319

2. **Modal de PIN**
   - Layout: `app/src/main/res/layout/dialog_unlock_pin.xml`
   - Campo `etUnlockPin`, botones `btnUnlockCancel` / `btnUnlockConfirm`.

3. **Validación backend**
   - `validateMachineAccess(machineCode, pin)`
   - Endpoint: `POST https://boxipagobackend.pagofacil.com.bo/api/maquinas/acceso`
   - Payload: `tcCodigoMaquina`, `tcPin`
   - Acepta si `error==0 && status==1 && values.tnAcceso==1`
   - Referencia: `KioskCatalogActivity.kt` líneas ~418-472

4. **Salida de lock**
   - En éxito llama `exitKioskMode()`:
     - `kioskLocked = false`
     - `stopLockTask()` si `lockTaskStarted`
     - `systemUiVisibility = VISIBLE`
   - Referencia: `KioskCatalogActivity.kt` líneas ~331-339

---

## AndroidManifest.xml (revisión solicitada)

Archivo: `app/src/main/AndroidManifest.xml`

1. **Launcher activity**
   - `com.vending.kiosk.app.MainActivity` con `MAIN` + `LAUNCHER`.

2. **`android:lockTaskMode` en activities**
   - **No existe** en ninguna activity.

3. **Receiver de device admin**
   - **No existe**.

4. **Permiso `BIND_DEVICE_ADMIN`**
   - **No existe**.

5. **Componentes orientados a Device Owner / kiosk administrado**
   - **No existen** en manifest (ni admin receiver, ni policies XML, ni wiring de DPM).

---

## Limitaciones técnicas del enfoque actual

### Lo que sí logra hoy

- Mantiene la app en modo inmersivo dentro de `KioskCatalogActivity`.
- Bloquea `back`, `app switch` y `menu` en eventos que pasan por la activity.
- Intenta fijar la tarea con `startLockTask()`.
- Agrega una salida controlada por gesto + PIN backend.

### Lo que NO puede garantizar hoy

- No puede bloquear de forma dura botones/superficies **OEM del sistema** (barra digital del fabricante, overlays propietarios, atajos flotantes).
- No puede bloquear confiablemente:
  - botón screenshot del sistema OEM
  - volumen
  - encendido/apagado
  - paneles propietarios fuera del proceso de la app
- Sin Device Owner + políticas DPM no hay kiosk "hard lock" administrado a nivel dispositivo.

### Dependencias de Android estándar

- `startLockTask()` en modo no administrado depende del comportamiento de screen pinning/usuario/dispositivo.
- `systemUiVisibility` (immersive) puede ser revertido temporalmente por gestos del sistema.
- Interceptar teclas en `dispatchKeyEvent()` sólo cubre eventos entregados a la activity, no todo el stack del sistema/OEM.

### Impacto probable de barra OEM personalizada

- Barras digitales OEM suelen correr con privilegios de sistema y pueden ignorar controles de una app normal.
- En Android 7.1.2 con personalización de fabricante, la persistencia de immersive suele ser menos robusta.
- Si la ROM expone botón digital de power/screenshot sobre la UI, la app no lo puede deshabilitar por sí sola.

---

## Archivos exactos que habrá que modificar después

> Solo listado de impacto probable para la siguiente fase (sin propuesta de código todavía).

1. `app/src/main/AndroidManifest.xml`
   - Para declarar configuración kiosk administrada (si se decide Device Owner / lockTaskMode).

2. `app/src/main/java/com/vending/kiosk/app/KioskCatalogActivity.kt`
   - Endurecimiento de estrategia de lock/unlock y manejo de pérdida de foco/salida.

3. `app/src/main/java/com/vending/kiosk/app/MainActivity.kt`
   - Si se requiere gateo del flujo para forzar modo kiosko desde entrada.

4. `app/src/main/res/layout/activity_kiosk_catalog.xml`
5. `app/src/main/res/layout/activity_kiosk_catalog_legacy.xml`
6. `app/src/main/res/layout/dialog_unlock_pin.xml`
   - Ajustes de UX de bloqueo/salida (si se redefine operación).

7. (Si se implementa administración real del dispositivo)
   - Nuevo receiver de admin (clase Kotlin/Java en `app/src/main/java/...`)
   - Nuevo XML de políticas de device admin en `app/src/main/res/xml/...` (hoy no existe carpeta `xml`).

---

## Riesgos por barra digital OEM / fabricante

- **Riesgo alto** de bypass del lock visual/app-only en dispositivos con barra OEM propia.
- **Riesgo operativo**: operador puede abrir acciones del sistema (captura, panel OEM, volumen, power menu) aunque la app esté en immersive.
- **Riesgo de inconsistencia**: comportamiento distinto entre equipos del mismo Android base por personalización de firmware.
- **Riesgo de falsa sensación de bloqueo**: la app muestra estado kiosk pero no controla completamente el shell del sistema.

---

## Resumen de hallazgo técnico central

El lock actual está implementado correctamente **a nivel app/activity** en `KioskCatalogActivity`, pero no está respaldado por infraestructura de **Device Owner / DevicePolicyManager / lockTaskMode administrado** en manifest/políticas. Por eso el bloqueo es parcial y no puede dominar controles OEM del sistema en Android 7.1.2.

---

## Addendum 2026-04-13 (actualizacion de flujo QR)

- Flujo kiosk de compra simplificado:
  - `Comprar ahora` y `Comprar` desde carrito pasan directo a checkout QR.
  - El modal intermedio de seleccion de metodo queda omitido en el flujo principal actual.
- Mensajeria de estado en polling QR ajustada:
  - pendiente: `Esperando confirmacion de pago...`
  - exito: `Pago confirmado`
- Modal de exito de dispensado:
  - incorpora autocierre en 5 segundos con contador visible.
- Cancelacion en modal QR:
  - se agrego accion `Cancelar` con confirmacion previa (`Si/No`).
  - `Si`: ejecuta `POST /api/pedido/{tnPedido}/cancelar` con motivo `CANCELADO_CLIENTE_APK`.
  - `No`: retorna a estado de espera y polling activo.
  - se implemento dialogo custom para mantener consistencia visual kiosk (sin popup nativo oscuro).
