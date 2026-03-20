# BITACORA - apk-nativa-vending

## 2026-03-19

### Hecho en esta iteracion
- Se creo carpeta raiz `apk-nativa-vending`.
- Se creo scaffold Android/Kotlin multi-modulo minimo.
- Se agregaron modulos:
  - `app`
  - `domain`
  - `data`
  - `integration-backend`
  - `integration-serial`
  - `kiosk-device`
- Se incluyo `docs` con documentacion viva inicial.
- Se definieron contratos base sin inventar protocolo de hardware:
  - `BackendGateway`
  - `PaymentConfirmationSync`
  - `SerialPortGateway`
  - `MachineProtocolAdapter`
  - `DispenseOrchestrator`
  - `KioskController`
- Se fijo bootstrap tecnico inicial:
  - `applicationId`: `com.vending.kiosk`
  - `minSdk`: `26`

### Confirmaciones importantes
- No acoplar este trabajo al proyecto `hola mundo`.
- No usar codigo decompilado como base del nuevo proyecto.
- No implementar logica funcional todavia, solo base de arquitectura.

### Riesgos abiertos
- Protocolo real de placa industrial no definido.
- Contratos backend aun no cerrados con el equipo remoto.
- Politica final de recovery local pendiente de validacion tecnica.

### Proximo foco recomendado
Definir especificacion detallada de modulo `operator-auth + vending-context + kiosk-control` antes de implementar.

## 2026-03-19 (iteracion serial tester)

### Hecho en esta iteracion
- Se integro un tester tecnico serial en la app Kotlin.
- Se migro `integration-serial` a modulo Android Library.
- Se agrego runtime serial:
  - `android_serialport_api.SerialPort` (bridge JNI)
  - `SerialManager`
  - `CommandSet`
  - `VendingFlowController`
  - `HexUtil`
- Se copio libreria nativa `libronyuanserial_port.so` (armeabi-v7a) al modulo serial.
- Se reemplazo la pantalla principal por UI de tester:
  - puerto
  - baud
  - pedido por celdas
  - conectar / iniciar pedido / continuar / stop
  - log en pantalla + log en archivo

### Alcance
- Implementado foco tecnico local de comunicacion y control serial.
- No implementado aun:
  - login operador
  - vending context
  - kiosk lock task
  - backend de pagos/catalogo/reservas

## 2026-03-20 (selector de modos)

### Hecho en esta iteracion
- Se agrego selector inicial de opciones en launcher:
  - Vending Kiosk
  - Vending Tester
  - Vending Calibrator
- Se movio toda la funcionalidad tecnica actual a `VendingTesterActivity`.
- El flujo actual queda:
  - operador abre app
  - ve opciones
  - elige Vending Tester
  - entra a interfaz tester con todas las funciones seriales existentes

### Estado de los otros modos
- `Vending Kiosk`: placeholder (pendiente implementacion funcional)
- `Vending Calibrator`: placeholder (pendiente implementacion funcional)

## 2026-03-20 - Revision tecnica de Zhong Da SDK
- Se realizo lectura completa del paquete `Zhong Da SDK English Version` (Demo, SDK, PDF e inspeccion de JAR decompilado).
- Hallazgo clave: existe `ResetLift` (token `"RESET"`) para reset de elevador/plataforma.
- Se confirmo que no hay evidencia directa (en esta pasada) de comando documentado de reboot total de placa.
- Se genero informe detallado en `docs/ANALISIS_ZHONGDA_SDK.md` con riesgos, limites y propuesta de pruebas controladas.
- Se hizo segunda pasada del PDF del SDK: sin OCR/text extractor disponible, pero se valido el indice interno del PDF (Outlines) con metodos `UBoard`, incluyendo `Board.ResetLift(ResetReplyPara)` y `Code Fault Table`.

## 2026-03-20 - Prueba controlada de saturacion + ResetLift en Vending Tester
- Se ajusto `POLL_DRIVER_MS` a `200ms` para pruebas de saturacion de polling en `VendingFlowController`.
- Se agrego comando dedicado `buildResetLift(address=1)` en `CommandSet` (escritura Modbus register `0x1002`, valor `0x0001`, con CRC).
- Se agrego boton en la UI del tester: `RESET LIFT (volver a base)`.
- Al presionar `RESET LIFT`, la app:
  - detiene flujo de venta en curso,
  - limpia cola/continuacion,
  - envia solo el comando de reset de lift,
  - actualiza prompt/log para verificar retorno a base.
- No se agregaron comandos de calibracion ni cambios de configuracion de placa.
- Build local por terminal: bloqueado por entorno (`JAVA_HOME`/`java` no disponible en PATH).
- Prueba en campo completada: se forzo aborto/atasco con polling agresivo; `STOP` + `ResetLift` recupero plataforma a estado base.
- Se restauraron los tiempos estables de polling en runtime (`driver=950ms`, `io vend=1200ms`, `io pickup=420ms`; retries `140/200/220ms`).

## 2026-03-20 - Integracion de Vending Calibrator real
- La opcion `Vending Calibrator` del menu principal ya abre una pantalla funcional (`VendingCalibratorActivity`) en lugar del placeholder.
- Se integraron recursos del calibrador (layout y badges de estado) en la app principal.
- Se integro el SDK oficial de calibracion via `integration-serial/libs/usdk_v1.0.jar` (se descarto el codigo jadx para evitar errores decompilados).
- Se integraron librerias nativas requeridas por el SDK (`libserial_port.so` para `armeabi-v7a` y `arm64-v8a`).
- El calibrador ahora permite: OPEN/CLOSE, estado de plataforma, estado de sensores, carga de posiciones guardadas, guardado de Y por filas y prueba de posicion por cm.
- Build `assembleDebug` exitoso luego de la integracion.

## 2026-03-20 - Mejora estetica del selector de modos
- Se rediseño `activity_main` (pantalla inicial de 3 opciones) con enfoque kiosk visual: fondo degradado, tarjeta de cabecera, jerarquia de texto y botones tipo tarjeta por modo.
- Se agregaron drawables dedicados para identidad visual del selector:
  - `bg_mode_selector_screen`
  - `bg_mode_header_card`
  - `bg_mode_button_kiosk`
  - `bg_mode_button_tester`
  - `bg_mode_button_calibrator`
- Se agregaron textos auxiliares en `strings.xml` para subtitulo y tip operativo.
- Build `assembleDebug` exitoso despues del cambio visual.

## 2026-03-20 - Rediseno visual de Vending Tester
- Se rehizo la interfaz de `VendingTesterActivity` con estilo tecnico/kiosk:
  - cabecera visual
  - panel de conexion y pedido
  - botones por jerarquia de accion
  - consola de log estilizada
- Se agregaron drawables nuevos para tester:
  - `bg_tester_screen`
  - `bg_tester_header`
  - `bg_tester_panel`
  - `bg_btn_connect`
  - `bg_btn_start`
  - `bg_btn_neutral`
  - `bg_btn_stop`
  - `bg_tester_log`
- Se ajusto para evitar tint automatico de Material en botones (`backgroundTint` en null) y mantener los colores definidos.

## 2026-03-20 - Reglas UI/operacion en Vending Tester
- `Continuar con el siguiente pedido`:
  - estado apagado cuando esta deshabilitado
  - estado encendido (mismo estilo de `Conectar`) cuando se habilita
- `/dev/ttyS1` y `9600` quedaron bloqueados para operador:
  - no focus
  - no edicion
  - sin cursor/seleccion
- `RESET LIFT`:
  - apagado por defecto
  - solo habilitado cuando aparece error de aborto con `status 0000`
  - al enviarse reset vuelve a estado apagado
- Build de validacion generado para vending despues de los cambios.
