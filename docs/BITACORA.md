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
