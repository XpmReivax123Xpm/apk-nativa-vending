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
- `@`: placeholder (pendiente implementacion funcional)

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
- Se rediseÃ±o `activity_main` (pantalla inicial de 3 opciones) con enfoque kiosk visual: fondo degradado, tarjeta de cabecera, jerarquia de texto y botones tipo tarjeta por modo.
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

## 2026-03-20 - Inicio de login real para Vending Kiosk (backend BP)
- Se reemplazo el placeholder de `Vending Kiosk` por navegacion real hacia `KioskLoginActivity`.
- Se implemento consumo HTTP real de `POST /api/login` contra `http://192.168.0.9:8001/api/login`.
- Request JSON implementado con campos exactos:
  - `tcCorreo`
  - `tcPassword`
- Parseo de respuesta implementado con validacion de:
  - `error`
  - `status`
  - `message`
  - `values.accessToken`
  - `values.tokenType`
  - `values.expiresInMinutes`
- Se implemento almacenamiento local de sesion (`AuthSessionManager`):
  - token
  - token type
  - expiracion calculada en epoch millis
- Se agrego helper para encabezado `Authorization: Bearer <token>` cuando la sesion sigue vigente.
- Se agregaron permisos y config de red para entorno LAN HTTP:
  - `INTERNET`
  - `usesCleartextTraffic=true`
- Estado actual de este modulo:
  - login funcional en codigo y listo para pruebas en dispositivo con backend LAN activo.
  - build local en esta maquina bloqueado por entorno (`JAVA_HOME`/`java` no disponible).

## 2026-03-20 - Validacion de login en dispositivo (vending)
- Se compilo APK debug con JDK de Android Studio en sesion local (`JAVA_HOME` temporal en terminal, sin cambios globales del sistema).
- Se instalo por ADB en celular/tablet de pruebas con `adb install -r`.
- Resultado funcional validado en campo:
  - Operador entra a `Vending Kiosk`.
  - Se muestra pantalla de login.
  - Login exitoso contra backend BP (`POST /api/login`).
  - Sesion/token guardados correctamente tras autenticacion.
- Estado: primer objetivo del modulo `Vending Kiosk` completado (conexion y autenticacion backend confirmadas).

## 2026-03-21 - Pulido visual/UX de login kiosk (branding BoxiPago)
- Se reemplazo cabecera inicial por logo real `BoxiPago (2).png` integrado como recurso local (`@drawable/boxipago_logo`).
- Se ajusto el formulario para mantener paleta corporativa y eliminar contenedores oscuros no deseados.
- Se corrigio tinte de controles para evitar acentos rosados/purpura heredados de tema por defecto.
- Se redefinio paleta global en `themes` (day/night) para estabilidad visual de la app completa.

## 2026-03-21 - Animaciones de entrada en Vending Kiosk
- Se implemento secuencia de intro al abrir login:
  - logo aparece con `fade in`
  - logo sube suavemente a posicion final
  - formulario (`correo`, `contrasena`, `ingresar`) aparece despues con `fade + translate`
- Se agregaron contenedores identificados para orquestar animacion: `logoContainer` y `loginContainer`.

## 2026-03-21 - Mensajeria de login refinada
- Durante autenticacion ahora muestra: `Ingresando...`.
- Mensaje de exito simplificado a: `ConexiÃ³n exitosa`.
- Se retiro toast tecnico: `Login correcto y token guardado`.
- Indicador de carga (`ProgressBar`) mantiene comportamiento, pero con color de paleta (sin rosado).

## 2026-03-21 - Build y despliegue
- Multiples ciclos `assembleDebug` exitosos tras cada ajuste.
- Instalaciones por ADB exitosas (`adb install -r`) en dispositivo de pruebas.

## 2026-03-21 - Calibrator: ajustes UX/operacion y pendientes
- Se actualizo nomenclatura de accion:
  - `OPEN` -> `CONECTAR`
  - `CLOSE` -> `DESCONECTAR`
- Se implemento estado visual de conexion:
  - Conectado: `CONECTAR` deshabilitado, `DESCONECTAR` habilitado en rojo.
  - Desconectado: `DESCONECTAR` deshabilitado, `CONECTAR` habilitado.
- Se removio de la UI el boton `ESTADO SENSORES` para enfocar calibracion de altura de plataforma.
- Se simplifico mensajeria de prueba:
  - sin advertencia larga de rango recomendado
  - texto de apoyo: `Probando X.XX cm`
- Se mejoro la traduccion de mensajes de placa en `Estado plataforma`:
  - se agregaron mapeos adicionales (incluye variantes de carril/canal invalido)
  - fallback para evitar exponer chino en UI: `Falla reportada por placa (sin traduccion exacta).`

### Pendientes abiertos (acordados)
- Consolidar tabla final de traducciones tecnico-funcionales para todos los codigos/fallos reales de placa.
- Revisar si se reintroduce diagnostico de sensores en una vista tecnica separada (no en flujo principal de calibracion).

## 2026-03-23 - Kiosk: maquinas + catalogo + compatibilidad Android 7.1.2
- Se implemento flujo post-login:
  - Login exitoso abre pantalla `Maquinas asignadas`.
  - Consumo de `GET /api/maquinas` con `Authorization: Bearer <token>`.
  - Render de lista de maquinas y estado (activa/inactiva).
- Se implemento apertura de catalogo por maquina activa:
  - Navegacion a `KioskCatalogActivity` al seleccionar maquina.
  - Consumo de `GET /api/maquinas/{tnMaquina}/planograma`.
  - Parseo desde `values.celdas` (con tolerancia a variantes de payload).
- Se aplico rediseno de UI de catalogo:
  - codigo de maquina arriba izquierda
  - ubicacion arriba derecha
  - bloque de carrusel/promos
  - productos en grilla 2 columnas con tarjeta casi cuadrada y espacio reservado para imagen.
- Se atendio estabilidad en tablet vending Android `7.1.2`:
  - separacion de layout `legacy` para API 25.
  - fallback de seguridad en catalogo ante error de inflado.
  - ajuste de recurso incompatible en Android 7 (`gradient angle` de `30` a `45`).
  - carrusel legacy en modo seguro (sin `ViewFlipper`) con rotacion por `Handler`.
- Se cambio backend base de IP LAN a dominio productivo:
  - `https://boxipagobackend.pagofacil.com.bo/`
  - aplicado en login, listado de maquinas y planograma.
- Resultado actual:
  - flujo kiosk llega a catalogo y carga celdas.
  - continuidad de ajustes visuales pendiente segun validacion en vending real.

## 2026-03-24 - Incidente de edicion y nuevo protocolo de trabajo
- Incidente registrado:

## 2026-04-20 - Actualizacion de planograma (cache local por ID + imagen secundaria)

### Hecho en esta iteracion
- Se actualizo el parseo de planograma para soportar la nueva estructura de imagenes:
  - `taProducto.taImagenPrincipal`
  - `taProducto.taImagenesSecundarias`
  - `taPresentacionArchivos`
  - `taFondoUiPrincipal`
- Se implemento cache local en disco para imagenes de planograma en `cacheDir/planograma_images`.
- Se agrego control condicional por identificador de archivo:
  - productos por `tnProductoArchivo`
  - presentacion/fondo por `tnPresentacionArchivo`
- Regla aplicada:
  - si el ID recibido no cambia, se reutiliza la imagen local.
  - si cambia, se elimina la anterior y se descarga la nueva.

### Ajuste funcional de imagenes principal/secundaria
- Grilla del planograma mantiene imagen principal.
- Modal de detalle con carrito vacio (`showProductDialog`) ahora muestra imagen secundaria del producto seleccionado (fallback a principal si no existe secundaria).
- Modal fusionado con carrito (`showProductWithCartDialog`) muestra:
  - imagen secundaria para el producto actualmente seleccionado,
  - imagen principal para los items listados dentro del carrito.

### Estado
- Implementacion aplicada y validada por usuario en ejecucion local.
  - En una iteracion de cambios sobre `KioskCatalogActivity.kt`, se aplico una edicion defectuosa que comprometio el archivo.
  - Impacto: perdida de estabilidad en esa rama de trabajo.
- Accion de recuperacion ejecutada:
  - Se restauro el proyecto al commit estable `b6d3f42` (`a punto de comenzar con las ventas xd`).
  - Se limpio el arbol de trabajo para eliminar residuos de la iteracion fallida.
- Regla de colaboracion acordada con el usuario (vigente desde hoy):
  - El usuario queda a cargo exclusivo de `git commit`.
  - Antes de editar: declarar exactamente que archivos se tocarán.
  - Despues de editar: reportar `git diff --name-only` + resumen corto.
  - Aplicar cambios pequenos y verificables (sin bloques masivos de alto riesgo).
  - Si falla una iteracion, rollback solo del bloque afectado al ultimo estado estable.
  - Evitar ediciones grandes no solicitadas y no repetir cambios destructivos.

## 2026-03-24 - Kiosk QR/pago: integracion minima + ajustes de operacion
- Se implemento flujo de compra desde catalogo:
  - modal de producto con cantidad (`+`/`-`) limitada por stock
  - acciones `Comprar ahora` y `Agregar carrito`
  - carrito operativo para compra multiple
- Se implemento checkout QR BCP:
  - captura de datos cliente (nombre, telefono, CI)
  - `POST /api/pedido/crear-y-generar-qr`
  - render de QR desde base64 en modal
- Se integro polling de estado de pago cada 5s (timeout 120s) con `GET /api/pedido/{tnPedido}/estado-pago`.
- Se integro dispensado secuencial post-pago reutilizando runtime serial (`VendingFlowController`) y avance automatico al siguiente item al completar ciclo (`onDone`, evento asociado a 2do click / D2).
- Ajustes UX recientes:
  - QR ampliado para mejor lectura en tablet vending.
  - Se oculto boton `Cancelar` en modal QR para evitar percances.
  - Al vencer timeout de pago, el modal QR se cierra automaticamente y se refresca catalogo.

### Nota de contrato/pago (pendiente de cierre con backend)
- Se detecto diferencia entre respuesta esperada y parsing de confirmacion en algunos escenarios.
- Para el entorno actual, la regla objetivo acordada es confirmar con `tnEstadoPedido == 2`.
- Queda pendiente cerrar la lectura final de estados para no depender de campos legacy.

## 2026-03-25 - UI pagos: paleta unificada + modal de metodo de pago
- Se alinearon modales de compra/pago a la paleta acordada:
  - fondo `#FFFFFF`
  - primario `#0965AF`
  - acento `#F28E1B`
- Se ajustaron modales:
  - `dialog_product_detail`
  - `dialog_checkout_qr`
  - `dialog_qr_payment`
  - `dialog_dispense_progress`
- Se forzo fondo transparente de ventana (`AlertDialog`) para eliminar bloque oscuro heredado del tema en Android 7.1.2.
- Se implemento paso intermedio para escalabilidad de pagos:
  - nuevo modal `Selecciona metodo de pago` antes del formulario de checkout.
  - por ahora se habilita solo `QR BCP`.
  - flujo preparado para agregar nuevos metodos sin rehacer checkout.
- Se compilo `assembleDebug` y se valido instalacion por ADB (`adb install -r`) en dispositivo de pruebas.

## 2026-03-25 - Catalogo: UX de carrito y ajuste visual de modal
- Se movio el acceso al carrito a formato flotante superior derecho para no quitar espacio al carrusel.
- Se reemplazo el chip de texto por icono SVG de carrito, dejando solo contador en burbuja cuando hay items.
- Se reemplazo el modal simple de carrito por modal grande y util para operacion:
  - lista de productos del carrito,
  - placeholder de imagen por item,
  - cantidad editable por item (`+` / `-`),
  - total recalculado en vivo,
  - acciones `Vaciar`, `Cerrar`, `Comprar`.
- Se corrigio visibilidad de controles `+/-` en carrito usando iconos vectoriales dedicados.
- Se mantuvo compatibilidad de UI para Android 7.1.2 (layout normal + legacy).

## 2026-03-26 - Catalogo: ajustes visuales para tablet vending Android 7.1.2
- Se ajusto la tarjeta de producto del planograma a esquinas rectas para evitar artefactos visuales en Android `7.1.2`.
- Se reemplazaron fondos redondeados por variantes rectas en la celda de catalogo.
- Se simplifico el render de imagen para evitar doble redondeo (UI + bitmap) y reducir deformaciones.
- Se cambio el escalado de imagen de producto a `FIT_CENTER` para evitar efecto de zoom excesivo.
- Se ocultaron del planograma las celdas no vendibles/no disponibles, mostrando solo productos comprables.
- Se ejecutaron compilaciones `assembleDebug` exitosas para validacion en vending.

## 2026-03-26 - Pago QR: compatibilidad temporal de estados backend
- Se detecto divergencia de contrato en confirmacion de pago entre escenarios de compra.
- Se implemento compatibilidad temporal en polling para aceptar estado desde:
  - `values.tnEstadoPago`
  - `values.tnEstadoPedido`
  - fallback `values.estado`
- Regla de interpretacion vigente en APK:
  - `2`: pagado
  - `3`: cancelado
  - `4`: fallido
- Se mantiene pendiente de cierre con backend un contrato unico y estable para evitar ambiguedad en multiples items.

## 2026-03-26 - Kiosk lock en planograma + salida por PIN
- Se implemento entrada en modo kiosk al abrir `KioskCatalogActivity`:
  - intento de `startLockTask()`
  - ocultamiento de barras del sistema en modo inmersivo
  - re-aplicacion periodica de flags para minimizar escape visual en Android `7.1.2`
  - bloqueo de `back`, `app switch` y `menu` desde la activity
- Se implemento gesto de seguridad para salida:
  - mantener presionado 2 segundos sobre el codigo/nombre de maquina (arriba izquierda)
  - abre modal de desbloqueo por PIN
- Se implemento validacion de PIN contra backend:
  - `POST /api/maquinas/acceso`
  - payload: `tcCodigoMaquina`, `tcPin`
  - desbloquea solo cuando `tnAcceso == 1`
- Se agrego teclado propio modal para evitar depender del teclado del sistema en:
  - nombre
  - telefono
  - CI/NIT
  - PIN de desbloqueo
- Se realizaron compilaciones `assembleDebug` exitosas e instalaciones por ADB en dispositivo conectado.

## 2026-03-26 - Checkout QR: modal bloqueante durante generacion
- Se detecto riesgo UX/operativo: al tocar fuera del modal de checkout durante carga, se podia cerrar y permitir interacciones de fondo.
- Se ajusto flujo de `Generar QR` para mostrar un modal dedicado de progreso:
  - texto `Generando QR...`
  - indicador circular de carga
  - fondo no cancelable por toque externo
  - sin boton de cierre manual
- El modal de progreso se mantiene visible hasta recibir respuesta de backend (exito/error), evitando que el operador interactue con la pantalla de catalogo en ese tramo.
- Se valido compilacion `assembleDebug` y despliegue por ADB en dispositivo conectado.

## 2026-03-28 - Infraestructura kiosk administrado + integracion en planograma
- Se agrego infraestructura base para kiosk administrado:
  - `KioskDeviceAdminReceiver`
  - `KioskPolicyManager`
  - `res/xml/device_admin_receiver.xml`
  - `AndroidManifest` con receiver de admin + `lockTaskMode="if_whitelisted"` en `MainActivity` y `KioskCatalogActivity`.
- Se conecto `KioskPolicyManager` al flujo real de `KioskCatalogActivity` sin romper PIN/long-press:
  - validacion de `Device Owner`
  - intento de allowlist (`setLockTaskPackages`) cuando corresponde
  - `startLockTask` solo si el paquete esta permitido
  - fallback visual cuando no existe provisioning de kiosk administrado.
- Se agregaron logs de diagnostico para lock task (estado device owner, allowlist, permiso lock task, exito/fallo de entrada).
- Se mantuvo salida actual por PIN y se preservo `stopLockTask` solo cuando hubo lock task real.

## 2026-03-28 - Ajustes UX modal de producto (planograma)
- Se agrego cierre manual visible en la parte superior (boton de cerrar fuera del cuerpo del modal).
- Se agrego temporizador de autocierre de 60s visible en cabecera del modal.
- El temporizador se reinicia con interacciones del usuario dentro del modal.
- Se reforzo compatibilidad visual para Android 7.1.2 (OEMs que pisan estilos) forzando estilos en runtime para timer y boton de cierre.
- Se realizaron compilaciones `assembleDebug` e instalaciones ADB de validacion en dispositivo conectado.

## 2026-03-31 - Kiosk Catalog: ajuste visual de cabecera superior
- Se continuo el ajuste estetico del `KioskCatalog` para aislar la cabecera (maquina + ubicacion) del fondo de pantalla.
- Se corrigio la franja superior para que sea realmente independiente del fondo degradado y ocupe todo el ancho de la pantalla.
- Se aplico el ajuste en ambos layouts activos:
  - `app/src/main/res/layout/activity_kiosk_catalog.xml`
  - `app/src/main/res/layout/activity_kiosk_catalog_legacy.xml`
- Se mantuvo compatibilidad Android `7.1.2` (sin introducir Compose ni cambios de logica funcional).
- Se ejecutaron compilaciones `assembleDebug` exitosas y despliegues ADB en dispositivo conectado para validacion visual.
- APK debug generado para vending:
  - `app/build/outputs/apk/debug/app-debug.apk`

## 2026-04-01 - Kiosk Catalog: media dinamica desde planograma (carrusel + fondo)
- Se integro consumo de `taPresentacionArchivos` desde la API de planograma para poblar el carrusel promocional con imagenes reales del backend.
- Reglas aplicadas para promociones:
  - `tnEstado == 1`
  - `tcUsoTipo == "PROMOCIONAL"` (cuando el campo esta presente)
  - `tcMimeType` tipo imagen (cuando el campo esta presente)
  - `tcUrl` no vacio
  - orden por `tnOrdenVisual` y luego `tnPresentacionArchivo`
- Se mantuvo fallback a slides locales cuando no hay promociones validas.
- Se integro consumo de `taFondoUiPrincipal` para pintar el fondo del planograma con imagen remota (`FONDO_PLANOGRAMA`).
- Reglas aplicadas para fondo UI:
  - `tnEstado == 1`
  - `tcUsoTipo == "FONDO_PLANOGRAMA"` (cuando el campo esta presente)
  - `tcMimeType` tipo imagen (cuando el campo esta presente)
  - `tcUrl` no vacio
- Si falla o no llega fondo valido, se mantiene fallback al drawable local `bg_kiosk_catalog_screen_hot`.
- Se reutilizo cache de bitmaps en runtime para evitar descargas repetidas.
- Archivo principal ajustado:
  - `app/src/main/java/com/vending/kiosk/app/KioskCatalogActivity.kt`
- Validacion tecnica:
  - compilaciones `assembleDebug` exitosas
  - instalacion por ADB exitosa en dispositivo conectado.

## 2026-04-01 - Timers de seguridad en modales de pago
- Se agrego temporizador de 60s en el modal `Selecciona metodo de pago`.
- Se agrego temporizador de 60s en el modal de checkout previo a `Generar QR`.
- Comportamiento implementado en ambos:
  - contador visible
  - reinicio del contador por interaccion del usuario
  - autocierre al llegar a `0s`
  - refresh de catalogo al autocierre (`loadCatalog(machineId, authHeader)`)
- Ajuste visual aplicado para Android `7.1.2`/OEM:
  - temporizador fuera del cuerpo del modal
  - color blanco y sin caja de fondo.
- Archivos impactados:
  - `app/src/main/java/com/vending/kiosk/app/KioskCatalogActivity.kt`
  - `app/src/main/res/layout/dialog_payment_method.xml`
  - `app/src/main/res/layout/dialog_checkout_qr.xml`

## 2026-04-01 - Carrusel definitivo (tipo 3) y retiro de laboratorio temporal
- Se retiro el flujo temporal de laboratorio de carruseles:
  - eliminada la pantalla de pruebas de carruseles
  - eliminado el boton temporal de acceso desde `KioskCatalog`
  - removido el registro de la activity temporal del `AndroidManifest`
- Se dejo como estrategia definitiva el carrusel tipo 3:
  - `ViewFlipper` con transiciones `fade in/out`
  - aplicado tambien en layout legacy para Android `7.1.2`
- Se ajusto render de imagen promocional para mejorar visualizacion completa:
  - `CENTER_CROP` -> `FIT_CENTER`
  - ajuste adaptativo de altura del carrusel segun proporcion real de la imagen promocional (primer frame valido) con limites por pantalla.
- Archivos impactados:
  - `app/src/main/java/com/vending/kiosk/app/KioskCatalogActivity.kt`
  - `app/src/main/res/layout/activity_kiosk_catalog_legacy.xml`
  - `app/src/main/res/layout/activity_kiosk_catalog.xml`
  - `app/src/main/AndroidManifest.xml`
- Se generaron APKs `assembleDebug` exitosas tras los ajustes.

## 2026-04-02 - Coordinacion de timers: pausa de inactividad global cuando hay modales
- Se corrigio colision operativa entre:
  - timer global de inactividad del planograma (60s, con refresh + vaciado de carrito),
  - timers locales de modales (producto, carrito, metodo de pago, checkout, etc.).
- Problema observado:
  - podia dispararse el refresh global mientras el usuario seguia operando dentro de un modal.
- Solucion implementada en `KioskCatalogActivity`:
  - contador de modales activos (`activeModalCount`),
  - pausa del scheduler global de inactividad mientras `activeModalCount > 0`,
  - reanudacion automatica del timer global al cerrar el ultimo modal,
  - guard adicional en `inactivityRunnable` para no ejecutar refresh si hay modal visible.
- Integracion aplicada a los dialogs del flujo kiosk:
  - desbloqueo PIN,
  - detalle de producto,
  - carrito,
  - seleccion de metodo de pago,
  - checkout previo a QR,
  - modal bloqueante de `Generando QR`,
  - modal de QR de pago,
  - modal de progreso de dispensado.
- Resultado:
  - no hay refresh global inesperado durante interaccion modal,
  - se mantiene el refresh de seguridad al volver al planograma sin modales.
- Validacion tecnica:
  - `assembleDebug` exitoso.
  - instalacion ADB exitosa en dispositivo conectado.

## 2026-04-02 - Planograma: celdas simplificadas (solo codigo visible)
- Se ajusto la vista de celdas del planograma para mostrar unicamente el codigo en la grilla.
- Se oculto la capa de informacion inferior dentro de cada celda (precio, nombre y stock) en la pantalla principal.
- El detalle completo se conserva en el modal al tocar una celda (sin cambios de logica de compra).
- Archivos impactados:
  - `app/src/main/res/layout/item_catalog_cell.xml`
  - `app/src/main/java/com/vending/kiosk/app/KioskCatalogActivity.kt`
- Validacion tecnica:
  - `assembleDebug` exitoso.

## 2026-04-06 - Runtime serial: ajuste de timeouts en `VendingFlowController`
- Se ajusto el timeout de driver de `45s` a `60s` para reducir falsos positivos de timeout en ciclos lentos de vending real.
- Se actualizo mensaje asociado a timeout de driver para reflejar `60s`.
- Se elimino el timeout de espera del segundo click (`IO_WAIT_TIMEOUT_MS = 180s`), dejando espera indefinida en etapa de retiro hasta recibir la secuencia IO esperada.
- Alcance:
  - impacta tanto `VendingTesterActivity` como `KioskCatalogActivity`, ya que ambos reutilizan `VendingFlowController`.
- Archivo impactado:
  - `integration-serial/src/main/kotlin/com/vending/kiosk/integration/serial/runtime/VendingFlowController.kt`
- Validacion tecnica:
  - `assembleDebug` exitoso.

## 2026-04-13 - Flujo QR directo + mensajes de polling + autocierre de exito
- Se simplifico el flujo de compra en kiosk para reducir pasos:
  - `Comprar ahora` ahora abre directo el checkout QR.
  - `Comprar` desde carrito ahora abre directo el checkout QR.
  - se omite el modal intermedio `Selecciona metodo de pago` en el flujo principal actual.
- Se ajusto mensajeria del estado de pago en modal QR:
  - en estado pendiente ya no muestra texto backend variable,
  - se muestra siempre `Esperando confirmacion de pago...`.
  - al confirmar pago se mantiene `Pago confirmado`.
- Se agrego autocierre al modal de exito de dispensado:
  - modal `Gracias por su compra` ahora muestra contador y se cierra en `5s`.
  - en error de dispensado se mantiene cierre manual (sin autocierre).
- Archivos impactados:
  - `app/src/main/java/com/vending/kiosk/app/KioskCatalogActivity.kt`
  - `app/src/main/res/layout/dialog_dispense_progress.xml`
- Validacion tecnica:
  - compilaciones `assembleDebug` exitosas.
  - instalaciones ADB exitosas en dispositivo conectado.

## 2026-04-13 - Cancelacion de pedido desde modal QR + confirmacion visual unificada
- Se habilito opcion de `Cancelar` dentro del modal de QR generado (etapa de pago).
- Se integro cancelacion contra backend:
  - `POST /api/pedido/{tnPedido}/cancelar`
  - `Authorization: Bearer <token>`
  - `Accept: application/json`
  - `Content-Type: application/json`
  - body enviado: `{ "tcMotivo": "CANCELADO_CLIENTE_APK" }`
- Se agrego confirmacion previa antes de cancelar:
  - pregunta: `Estas seguro de cancelar el pedido?`
  - acciones: `Si` / `No`
  - `Si`: ejecuta API de cancelacion.
  - `No`: no cancela y se reanuda polling de pago.
- Se reemplazo el dialogo nativo oscuro por modal custom con estetica kiosk (fondo claro + botones estilo de la app) para mantener consistencia visual.
- Archivos impactados:
  - `app/src/main/java/com/vending/kiosk/app/KioskCatalogActivity.kt`
  - `app/src/main/res/layout/dialog_cancel_order_confirm.xml`
- Validacion tecnica:
  - `assembleDebug` exitoso.
  - instalacion ADB exitosa en dispositivo conectado.

## 2026-04-14 - Mejora estetica del modal de detalle de producto
- Se rediseño el modal de detalle de producto tomando como referencia visual de operacion:
  - nueva jerarquia visual con titulo `Detalles del Producto`.
  - bloque principal de imagen y nombre del producto con mayor protagonismo.
  - chip de `Casilla XX`.
  - bloque de precio/stock y total mas legible.
  - botones de accion inferiores (`Agregar carrito` / `Comprar ahora`) mantenidos en paleta kiosk.
- Ajuste funcional UX:
  - total del producto se recalcula en vivo al cambiar cantidad (`+/-`).
- Correccion visual puntual:
  - controles de cantidad migrados a `ImageButton` con iconos (`ic_minus_white` / `ic_plus_white`) para centrar correctamente los simbolos y evitar deformacion del `+` por tipografia OEM.
- Archivos impactados:
  - `app/src/main/res/layout/dialog_product_detail.xml`
  - `app/src/main/java/com/vending/kiosk/app/KioskCatalogActivity.kt`
  - `app/src/main/res/drawable/bg_chip_small_dark.xml`
- Validacion tecnica:
  - `assembleDebug` exitoso.
  - instalacion ADB exitosa en dispositivo conectado.

## 2026-04-14 - Reintegracion de seleccion de metodo de pago en flujo principal
- Se revierte la omision temporal del selector de metodo para volver al flujo con paso intermedio.
- Flujo actualizado:
  - `Comprar ahora` -> `Selecciona metodo de pago` -> checkout QR.
  - `Comprar` desde carrito -> `Selecciona metodo de pago` -> checkout QR.
- Se conservaron sin cambios:
  - temporizador de 60s del modal de metodo de pago.
  - flujo QR con polling, confirmacion y cancelacion de pedido.
- Validacion tecnica:
  - `assembleDebug` exitoso.
  - instalacion ADB exitosa en dispositivo conectado.

## 2026-04-14 - Modal condicional detalle/fusion segun estado de carrito
- Se ajusto la regla de apertura al tocar una celda vendible del planograma:
  - si `carrito` esta vacio -> abre modal tradicional `Detalles del Producto`.
  - si `carrito` tiene items -> abre modal fusionado `Detalle + Carrito`.
- Modal fusionado incorporado:
  - panel izquierdo: detalle del producto seleccionado + selector de cantidad + `Agregar carrito`.
  - panel derecho: carrito editable (sumar/restar/quitar), total unidades, total a pagar, `Comprar ahora` y `Vaciar carrito`.
- Si se vacia completamente el carrito desde el modal fusionado, el flujo vuelve automaticamente al modal de detalle tradicional.
- Archivos impactados:
  - `app/src/main/java/com/vending/kiosk/app/KioskCatalogActivity.kt`
  - `app/src/main/res/layout/dialog_product_cart_fusion.xml`
  - `app/src/main/res/layout/item_cart_line_fusion.xml`
- Validacion tecnica:
  - `assembleDebug` exitoso.
  - instalacion ADB exitosa en dispositivo conectado.

## 2026-04-15 - Autenticacion por maquina con PIN + refresh automatico de token
- Se incorporo login operativo por maquina al seleccionar vending:
  - modal de PIN en lista de maquinas.
  - autenticacion via `POST /api/maquinas/login` con `tcCodigoMaquina` + `tcPin`.
- Se agrego persistencia de credenciales de maquina en sesion local:
  - `machineId`, `machineCode`, `machinePin`.
- Se implemento renovacion automatica de sesion cuando token expira/401:
  - relogin usando credenciales guardadas de maquina.
  - reaplicado en catalogo (`planograma`) y flujo QR (`crear pedido`, `polling`, `cancelar pedido`).
  - fallback: si no se puede renovar, se informa sesion expirada y se requiere volver a seleccionar maquina.
- Archivos impactados:
  - `app/src/main/java/com/vending/kiosk/app/AuthSessionManager.kt`
  - `app/src/main/java/com/vending/kiosk/app/MachineAuthGateway.kt` (nuevo)
  - `app/src/main/java/com/vending/kiosk/app/KioskMachinesActivity.kt`
  - `app/src/main/java/com/vending/kiosk/app/KioskCatalogActivity.kt`
  - `app/src/main/res/layout/dialog_machine_login_pin.xml` (nuevo)
- Validacion tecnica:
  - `assembleDebug` exitoso.
  - instalacion ADB exitosa en dispositivo conectado.

## 2026-04-15 - Modal de dispensado: progreso por item + producto actual
- Se actualizo el modal de progreso de dispensado para alinear UX operativa en tablet vending:
  - titulo principal fijo: `Dispensando productos...`.
  - contador visual de fase de dispensado por item en formato `X de Y`.
  - nombre del producto actual en curso.
  - imagen del producto/celda actualmente dispensado.
- Ajuste de logica de cola de dispensado:
  - la cola interna ahora conserva `celda fisica + producto` por cada unidad a despachar.
  - en cada avance (`onDone`) el modal actualiza automaticamente `X de Y`, nombre e imagen del siguiente item.
- Se elimino redundancia de texto:
  - el bloque inferior ya no repite el mismo mensaje del titulo.
- Archivos impactados:
  - `app/src/main/java/com/vending/kiosk/app/KioskCatalogActivity.kt`
  - `app/src/main/res/layout/dialog_dispense_progress.xml`
- Validacion tecnica:
  - pendiente de compilacion/validacion en Android Studio por el usuario (sin build en esta iteracion).

## 2026-04-15 - Ajuste visual del reloj de arena en modal de dispensado
- Se reemplazo el icono previo del modal por recurso de imagen real del proyecto:
  - `@drawable/reloj_de_arena` (`reloj_de_arena.png`).
- Ubicacion del reloj:
  - parte superior del modal, sobre el titulo principal, siguiendo referencia visual de operacion.
- Se removio el icono inferior de pruebas para evitar duplicidad visual.
- Archivo impactado:
  - `app/src/main/res/layout/dialog_dispense_progress.xml`
- Validacion tecnica:
  - pendiente de compilacion/validacion en Android Studio por el usuario (sin build en esta iteracion).

## 2026-04-16 - Modal de dispensado: ajuste fino de escalas visuales
- Se realizaron ajustes finos de tamano para mejorar legibilidad en tablet vending:
  - reloj de arena superior incrementado para mejor presencia visual.
  - texto de titulo y progreso (`X de Y`) reajustados para lectura a distancia.
  - texto de estado `Espera un momento, por favor...` incrementado.
  - nombre de producto y miniatura de producto aumentados para mejorar identificacion.
- Se mantuvo sin cambios la logica de dispensado y la actualizacion dinamica por item.
- Archivo impactado:
  - `app/src/main/res/layout/dialog_dispense_progress.xml`
- Validacion tecnica:
  - pendiente de compilacion/validacion en Android Studio por el usuario (sin build en esta iteracion).

## 2026-04-16 - Nuevo modal de retiro post `driver done` (espera de D2)
- Se incorporo un segundo modal para la fase de retiro del producto:
  - se muestra solo cuando el flujo serial reporta `onNeedRetrieve` (fin de fase driver).
  - mensaje orientado a operador/cliente: producto listo + instruccion de retiro.
  - la continuidad al siguiente item ocurre al evento `onDone` (D2).
- Se agrego layout dedicado:
  - `app/src/main/res/layout/dialog_dispense_retrieve.xml` (nuevo).
- Integracion en flujo:
  - apertura en `onNeedRetrieve`.
  - cierre al detectar `onDone`, en error o al finalizar flujo.

## 2026-04-16 - Ajuste anti-parpadeo del texto largo en transicion de modal
- Se mantuvo el texto tecnico largo de estado (no eliminado), pero se ajusto el orden de ejecucion para que no sea visible durante la transicion:
  - primero se abre el modal de retiro.
  - luego se actualiza el texto del modal de progreso via `post { ... }`, quedando detras del nuevo modal.
- Objetivo cubierto:
  - evitar que se vea por milisegundos el cambio de texto justo antes del modal de retiro.
- Archivo impactado:
  - `app/src/main/java/com/vending/kiosk/app/KioskCatalogActivity.kt`

## 2026-04-16 - Modal de exito separado (`Gracias por su compra`)
- Se separo el estado de exito final en un layout propio para no reutilizar el modal de progreso:
  - nuevo archivo `app/src/main/res/layout/dialog_dispense_success.xml`.
- Se actualizo `onDispenseFinished()` para:
  - cerrar modal de progreso,
  - abrir modal de exito dedicado,
  - mantener refresh de catalogo al cierre de flujo (`loadCatalog(machineId, authHeader)`).
- Se agrego imagen de confirmacion en cabecera del modal de exito:
  - `@drawable/check` (`check.png`).

## 2026-04-16 - Temporizador 5s en modal de exito (cabecera externa)
- El modal de exito ahora usa contador visible en cabecera superior (fuera del cuerpo), estilo consistente con otros modales con timer.
- Regla operativa:
  - cuenta regresiva `5s -> 0s`,
  - al llegar a `0s` ejecuta `dismiss()` automatico del modal.
- Se reforzo implementacion de cuenta regresiva para asegurar decremento visible segundo a segundo.
- Archivos impactados:
  - `app/src/main/java/com/vending/kiosk/app/KioskCatalogActivity.kt`
  - `app/src/main/res/layout/dialog_dispense_success.xml`

## 2026-04-20 - Barra de categorias estaticas + carrito integrado en catalogo
- Se agrego barra de categorias estaticas debajo del carrusel para anticipar integracion futura con backend:
  - `Todos`, `Sacks`, `Bebidas`, `Energizantes`, `Saludable`.
- Se movio el acceso al carrito a la misma fila de categorias (lado derecho), manteniendo IDs funcionales existentes:
  - `cartFabContainer`
  - `ivCartBadge`
  - `tvCartBadge`
- Se ajusto presentacion del carrito a formato boton azul con texto `Comprar` + icono de carrito en una sola pieza visual.
- Se realizaron ajustes finos de posicion de burbuja de contador para evitar:
  - recorte visual,
  - superposicion sobre el icono del carrito.
- Se habilito `clipChildren=false` y `clipToPadding=false` en contenedores de fila y carrito para permitir calibracion de burbuja sin cortes con offsets negativos.
- Se agregaron drawables de chips para categorias:
  - `app/src/main/res/drawable/bg_catalog_category_chip.xml`
  - `app/src/main/res/drawable/bg_catalog_category_chip_active.xml`
- Archivos impactados:
  - `app/src/main/res/layout/activity_kiosk_catalog.xml`
  - `app/src/main/res/layout/activity_kiosk_catalog_legacy.xml`
  - `app/src/main/res/drawable/bg_catalog_category_chip.xml`
  - `app/src/main/res/drawable/bg_catalog_category_chip_active.xml`

## 2026-04-20 - Metodo de pago dinamico por servicios habilitados (token maquina)
- Se reemplazo la seleccion estatica de metodo de pago por carga dinamica desde backend usando token de maquina.
- Endpoint integrado:
  - `GET /api/maquina/pago/qr/servicios-habilitados`
  - headers: `Authorization: Bearer {token_maquina}`, `Accept: application/json`.
- Parseo implementado segun estructura de respuesta:
  - `values.taProviderResponse.values[]`
  - `paymentMethodId`
  - `paymentMethodName`
- El modal de metodo de pago ahora:
  - muestra estado de carga (`Cargando metodos de pago...`),
  - construye radio buttons en runtime segun metodos habilitados,
  - deshabilita `Continuar` hasta tener metodos validos,
  - envia al checkout el `tnPaymentMethodId` realmente seleccionado.
- Se mantuvo flujo de resiliencia de sesion:
  - si hay `401`, intenta refresh de token con credenciales de maquina.
  - si no se recupera sesion, se corta flujo con manejo de sesion expirada.
- Se removio del XML la opcion fija `QR` para evitar desalineacion con backend.
- Archivos impactados:
  - `app/src/main/java/com/vending/kiosk/app/KioskCatalogActivity.kt`
  - `app/src/main/res/layout/dialog_payment_method.xml`

## 2026-04-23 - Ajustes visuales en modales (iteracion UX)
- Se realizo revision de prioridad XML vs Kotlin en modales de producto/dispensado.
- Confirmacion funcional registrada:
  - el texto inicial definido en XML puede ser sobreescrito por asignaciones runtime en `KioskCatalogActivity`.
  - los estilos del XML se mantienen mientras no se fuercen desde Kotlin.
- Ajuste aplicado en UI:
  - `dialog_product_cart_fusion.xml`: preview de imagen de producto estandarizado con caja fija para mejorar consistencia visual entre productos.
- Ajuste de codigo solicitado por usuario:
  - se probo una variante para priorizar estilos XML en `KioskCatalogActivity`.
  - tras validacion en dispositivo, se revirtio ese bloque y se restauro comportamiento previo.
- Estado de la iteracion:
  - cambios visuales en evaluacion por usuario.
  - documentacion actualizada antes de commit manual.

## 2026-04-27 - Integracion QR backend (metodos + polling) y ajustes de catalogo
- Se actualizo la fuente de metodos de pago por maquina:
  - de `GET /api/maquina/pago/qr/servicios-habilitados`
  - a `GET /api/maquina/metodos-pago`.
- Se aplico filtro de negocio en APK para mostrar solo metodos permitidos:
  - `QR BCP`
  - `QR ATC`.
- Se migro el polling QR principal a:
  - `POST /api/maquina/pago/qr/consultar-transaccion`.
- Payload implementado en polling QR:
  - minimo: `{ "companyTransactionId": "<tnPedido>" }`
  - robusto (cuando existe): incluye `pagofacilTransactionId` (`taQr.tnTransaccionProveedor`).
- Se agrego persistencia en memoria de datos QR al crear pedido:
  - `values.taQr.tnTransaccionProveedor`
  - `values.taQr.tcTransaccionMetodoProveedor`.
- Se mejoro lectura de estados del proveedor en polling:
  - soporte de variantes de campos de estado/descripcion,
  - mapeo por codigo y por descripcion (`PAGADO/APROBADO/PENDIENTE/CANCELADO/ANULADO/REVERTIDO/FALLIDO`).
- Se ajusto UX del modal QR:
  - en errores transitorios ya no se muestra mensaje tecnico backend,
  - se mantiene mensaje operativo `Esperando confirmacion de pago...`.
- Ajuste de flujo de modales de producto:
  - en modal simple, al `Agregar a tu pedido` ahora:
    1. agrega al carrito,
    2. cierra modal simple,
    3. abre modal fusionado `dialog_product_cart_fusion`.
- Ajustes de layout de catalogo (normal + legacy):
  - se mantuvo la franja blanca superior (nombre de maquina/ubicacion),
  - se agrego una franja blanca inferior adicional (solo color),
  - grilla de catalogo ajustada de `6x3` a `5x3`.

## 2026-05-06 - Modal de incidencia: iconos de guia para evidencias
- Se incorporaron iconos vectoriales (SVG Android VectorDrawable) en el bloque:
  - `PARA REPORTAR ENVIE LAS SIGUIENTES FOTOS:`
- Recursos agregados:
  - `app/src/main/res/drawable/ic_phone_bottle_report.xml`
  - `app/src/main/res/drawable/ic_phone_vending_report.xml`
  - `app/src/main/res/drawable/ic_phone_screen_report.xml`
- Layout ajustado:
  - `app/src/main/res/layout/dialog_dispense_error.xml`
- Regla visual final por fila:
  - `1.` + icono + `Foto del producto trabado`
  - `2.` + icono + `Foto de la vending machine`
  - `3.` + icono + `Foto de esta pantalla`
- Nota de UX:
  - Se reubico iconografia para que el icono quede entre el numero y el texto (no al extremo derecho).

## 2026-05-07 - Timeout IO de retiro + modal dedicado en Kiosk
- Se reincorporo timeout en la etapa de retiro (polling IO) dentro de `VendingFlowController`:
  - constante activa: `IO_WAIT_TIMEOUT_MS = 10_000L`
  - mensaje de error emitido: `Timeout: no llego el 2do click en 10s`.
- Integracion de UI en `KioskCatalogActivity`:
  - se detecta timeout IO y se muestra modal específico de retiro no confirmado.
  - para otros errores de dispensacion se mantiene el modal general de incidencia.
- Nuevo layout agregado:
  - `app/src/main/res/layout/dialog_dispense_io_timeout.xml`
- Ajuste funcional:
  - en cierre del modal de timeout IO se refresca catalogo para volver a estado operativo.

## 2026-05-12 - Flujo IO timeout extendido + anulacion controlada

### Hecho en esta iteracion
- Se ajusto `VendingFlowController` para separar timeout IO en dos etapas:
  - `IO_TIMEOUT` (10s): aviso no fatal, mantiene espera de datos IO.
  - `IO_TIMEOUT_CANCEL` (120s posteriores): fallo definitivo por anulacion.
- Se agrego recuperacion de flujo cuando aparece `82` despues del primer timeout:
  - emite evento de recuperacion (`IO_TIMEOUT_RECOVERED`),
  - cierra modal de timeout IO,
  - retorna al flujo de retiro normal y espera `D2`.
- Se actualizo `KioskCatalogActivity` para el nuevo comportamiento:
  - `IO_TIMEOUT` ya no cancela dispensacion ni corta cola.
  - `IO_TIMEOUT_CANCEL` marca item actual en estado `7 (ANOMALO)`, detiene continuidad y muestra modal de incidencia.
- Se removio boton `Cerrar` del modal `dialog_dispense_io_timeout` para evitar corte manual del flujo de espera.
- Se ajusto `VendingTesterActivity` para habilitar `Reset Lift` tambien ante `IO_TIMEOUT_CANCEL`.

### Resultado operativo esperado
- Caso recuperable: timeout inicial -> aparece `82` -> flujo continua hasta `D2` -> item completado (`tnEstado=4`) y sigue la cola.
- Caso no recuperable: timeout inicial -> no aparece `82` en ventana de anulacion -> item actual `tnEstado=7`, resto pendiente y corte de dispensacion.

## 2026-05-12 - Ajuste IO intermedios (02/12) + recuperacion timeout por 82 o 02

### Hecho en esta iteracion
- Se incorporaron estados IO intermedios explicitos en `VendingFlowController`:
  - `02` (`IO_DOOR_OPEN_FIRST_TIME`): puerta chica se abre por primera vez.
  - `12` (`IO_PRODUCT_REMOVED_DOOR_OPEN`): producto retirado con puerta abierta.
- Se agregaron logs operativos para ambos estados en runtime.
- Se ajusto criterio de recuperacion tras `IO_TIMEOUT`:
  - antes: recuperaba solo con `82`.
  - ahora: recupera con `82` **o** `02`.
- Se actualizo `VendingTesterActivity` para mostrar eventos `onStep` en log (`STEP: ...`) y facilitar validacion de recuperacion en campo.

### Resultado operativo esperado
- Si durante la ventana de anulacion aparece `82` o `02`, el flujo se considera recuperado y continua retiro normal hasta `D2`.

## 2026-05-15 - Boton "Atras" condicionado por desbloqueo PIN en KioskCatalog

### Hecho en esta iteracion
- Se reemplazo la franja blanca inferior en `activity_kiosk_catalog` y `activity_kiosk_catalog_legacy` por una barra con boton `Atras` (`btnKioskBackToMain`).
- Comportamiento del boton:
  - oculto por defecto durante modo kiosk bloqueado,
  - visible solo despues de desbloqueo exitoso por PIN (cuando se ejecuta `exitKioskMode()`),
  - al presionarlo vuelve al selector principal (`MainActivity`) y cierra `KioskCatalogActivity`.
- Se agrego control de estado en `KioskCatalogActivity`:
  - flag `kioskUnlockedByPin`,
  - funciones `setupBackToMainButton()` y `updateBackToMainVisibility()`.

### Criterio operativo
- No se habilita navegacion de retorno desde catalogo mientras el kiosk siga bloqueado.
- El retorno a pantalla principal queda permitido solo tras validacion de PIN de salida.

## 2026-05-16 - Auto-resume kiosk + ajuste robusto IO retiro (D2 terminal)

### Hecho en esta iteracion
- Se implemento auto-resume kiosk persistente sin depender de LockTask/Device Owner:
  - `AuthSessionManager` ahora guarda:
    - `kiosk_auto_resume_enabled`
    - `last_machine_location`
  - Se agregaron funciones:
    - `setKioskAutoResumeEnabled(...)`
    - `isKioskAutoResumeEnabled()`
    - `saveMachineLocation(...)`
    - `getMachineLocation()`
    - `disableKioskAutoResume()`
- En `KioskMachinesActivity`, tras PIN correcto:
  - se sobreescriben credenciales de maquina (`id`, `code`, `pin`),
  - se guarda `last_machine_location`,
  - se activa `kiosk_auto_resume_enabled=true`.
- En `MainActivity`:
  - al abrir, si auto-resume esta activo y hay credenciales completas, intenta refresh con `MachineAuthGateway`;
  - si refresh es exitoso, redirige directo a `KioskCatalogActivity`;
  - si falla, no crashea ni entra en loop: muestra menu normal.
- En `KioskCatalogActivity`:
  - se agrego accion administrativa explicita `Desactivar auto-resume kiosk` (visible solo tras desbloqueo por PIN),
  - esta accion desactiva auto-resume sin limpiar credenciales ni sesion.

### Ajuste critico en flujo IO/driver (runtime)
- Se amplio criterio de progreso valido en retiro para timeout IO:
  - estados validos: `82`, `02`, `12`, `92`, `D2`.
- Se agregaron logs para transiciones observadas en campo:
  - `42` (`0042`) y `52` (`0052`) como estados transitorios.
- Correccion de regresion detectada en `VendingTester`:
  - antes podia quedar en polling infinito con `D2` repetido si `92` no quedaba estable,
  - ahora `D2` se trata como estado terminal de retiro y dispara cierre de flujo (`onDone`) aunque no haya `92` estable previo.
- Se mantiene recuperacion de timeout IO cuando llega progreso valido despues de advertencia.

### Resultado operativo esperado
- Caso con secuencia rapida (`...42/52...D2`): no debe quedarse colgado; debe finalizar retiro.
- Caso con secuencia clasica (`...82/02/12/92/D2`): mantiene finalizacion normal.
