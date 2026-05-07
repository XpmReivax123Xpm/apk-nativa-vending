# PLAN - apk-nativa-vending

## Objetivo
Construir una app Android nativa tipo kiosk para tablet industrial conectada a una vending machine, actuando como puente confiable entre hardware serial local y backend central.

## Principios base
- Kotlin nativo.
- Clean Architecture + MVVM/UDF.
- Backend central como fuente de verdad del negocio.
- Serial/hardware y kiosk como preocupaciones de primer nivel.
- Sin mezclar UI con reglas de negocio ni control de hardware.

## Alcance funcional acordado
- Login de operador.
- Seleccion de vending asignada.
- Activacion de contexto local de vending.
- Entrada en modo kiosk bloqueado.
- Salida protegida por PIN.
- Catalogo por vending.
- Carrito.
- Pago digital orquestado por backend.
- Confirmacion de pago consumida por app desde backend.
- Recogida de reserva por codigo.
- Entrega por items (uno por uno).
- Soporte de fallo parcial y reporte preciso por item y global.

## Estado actual (implementado)
- Selector inicial de modos operativo con 3 entradas:
  - `Vending Kiosk` (ya conectado a pantalla de login backend).
  - `Vending Tester` (integrado y operativo).
  - `Vending Calibrator` (integrado y operativo).
- Recuperacion e integracion de flujos tecnicos desde APKs previas (tester/calibrator).
- `Vending Tester` con:
  - Conexion serial local (`/dev/ttyS1`, `9600`) y logging de RX/TX.
  - Flujo de pedido secuencial y control STOP.
  - `RESET LIFT` operativo para retorno a base (validado en pruebas de campo).
  - Guardas de UI para operacion segura:
    - Puerto y baudrate no editables por operador.
    - Boton `Continuar` deshabilitado por defecto y activo solo cuando corresponde.
    - Boton `RESET LIFT` deshabilitado por defecto y activo solo ante `driver status 0000`.
- Mejora estetica de pantallas inicial y tester sin romper funcionalidad.
- Inicio de modulo Login Kiosk conectado al backend BP (POST /api/login) con guardado de token + expiracion, validado en dispositivo real.
- Login Kiosk con branding BoxiPago aplicado (logo real), animacion de entrada secuencial y mensajes UX refinados para operacion.
- Vending Calibrator alineado al lenguaje visual del tester y con UX operativa de conexion/desconexion (toggle de botones + diagnostico de plataforma traducido).
- Flujo `Kiosk` extendido:
  - listado de maquinas asignadas via backend
  - seleccion de maquina activa
  - carga de catalogo por planograma/celdas
- Base backend actualizada a dominio:
  - `https://boxipagobackend.pagofacil.com.bo/`
- Compatibilidad operativa para vending Android `7.1.2` en pantalla de catalogo:
  - layout legacy dedicado
  - fallback seguro de inflado
  - carrusel seguro legacy (rotacion por codigo)


## Decisiones vigentes
- Estrategia de confirmacion de pago: polling con backoff (v1).
- PIN de salida kiosk: gestionado por backend por vending/operador.
- No inventar comandos ni semantica del protocolo serial.
- Protocolo de recuperacion de placa: pendiente validacion tecnica con hardware real.
- Compensacion/reembolso: responsabilidad del backend.

## Modulos del scaffold
- `app`: shell Android, selector de modos y navegacion base.
- `domain`: modelos y reglas de negocio puras.
- `data`: capa de implementaciones y coordinacion de repositorios.
- `integration-backend`: contratos de API/sync con backend.
- `integration-serial`: contratos de puerto serial y orquestacion de dispensado.
- `kiosk-device`: contrato de control de modo kiosk.
- `docs`: documentacion viva.

## Contratos base iniciales
- `BackendGateway`
- `PaymentConfirmationSync`
- `SerialPortGateway`
- `MachineProtocolAdapter`
- `DispenseOrchestrator`
- `KioskController`

## Dependencias tecnicas pendientes
- Especificacion oficial del protocolo serial de placa/controladora (incluyendo recuperacion robusta post-fallo).
- Formato JSON/API definitivo con equipo backend.
- Politica final de recuperacion local de hardware en estado incierto.
- Estrategia exacta de reconciliacion post reinicio electrico.
- Integracion futura de carrusel con imagenes reales (backend/CDN) en lugar de placeholders locales.

## Coordinacion externa
Este proyecto Android se coordina con otro equipo/Codex de backend. Los contratos de integracion deben cerrarse en conjunto antes de la fase de implementacion de negocio.







## Protocolo operativo vigente (desde 2026-03-24)
- El responsable de `git commit` es el usuario.
- Toda iteracion de cambios debe seguir este orden:
  1. Declarar archivos a tocar.
  2. Ejecutar cambios pequenos y verificables.
  3. Reportar `git diff --name-only` y resumen breve.
- Si una iteracion falla, se revierte solo el bloque afectado al ultimo punto estable, evitando cambios destructivos globales.

## Estado de pagos QR (actualizado 2026-03-24)
- Flujo implementado en APK:
  1. seleccion de producto(s) y cantidad con control de stock
  2. creacion de pedido + generacion QR
  3. visualizacion de QR en modal
  4. polling de estado de pago
  5. dispensado secuencial post-confirmacion
- Criterio objetivo de confirmacion para esta etapa:
  - compatibilidad temporal: `tnEstadoPago == 2` o `tnEstadoPedido == 2` (con fallback `estado == 2`).
- Al expirar el tiempo del QR:
  - el modal se cierra automaticamente
  - se refresca el catalogo.

## Ajustes recientes de catalogo (actualizado 2026-03-26)
- UI de planograma adaptada para Android `7.1.2` con enfoque de estabilidad visual:
  - tarjetas e imagenes con esquinas rectas
  - imagen de producto con `FIT_CENTER` para evitar zoom excesivo
  - ocultamiento de celdas no disponibles en la grilla visible

## Modo kiosk en planograma (actualizado 2026-03-26)
- Al ingresar al planograma se activa bloqueo kiosk desde app:
  - lock task (cuando el dispositivo lo permite)
  - modo inmersivo con re-aplicacion para esconder navegacion del sistema
  - bloqueo de acciones de salida por teclas de navegacion en la activity
- Salida controlada:
  - gesto de 2 segundos sobre titulo de maquina
  - modal de PIN
  - validacion remota en `POST /api/maquinas/acceso`
- Entrada de datos en checkout/desbloqueo:
  - teclado propio modal para no depender del IME del sistema

## UX de checkout QR (actualizado 2026-03-26)
- Al confirmar `Generar QR`, la app entra en estado bloqueado de generacion:
  - modal de progreso no cancelable
  - sin interaccion con fondo
  - salida del modal solo al completar respuesta de backend

## Modo kiosk administrado (actualizado 2026-03-28)
- Infraestructura base incorporada para escenario Device Owner:
  - `DeviceAdminReceiver` declarado
  - XML de politicas admin
  - helper `KioskPolicyManager`
  - `lockTaskMode="if_whitelisted"` en activities clave
- Integracion en `KioskCatalogActivity`:
  - intenta lock task administrado cuando el dispositivo lo permite
  - conserva fallback visual (immersive + bloqueos actuales) cuando no hay provisioning DO
  - mantiene salida por PIN sin cambios funcionales.
- Estado actual:
  - app lista para operar en modo mixto (administrado si existe DO, fallback si no).
  - pendiente cierre operativo con provisioning real de dispositivo vending.

## UX modal de producto (actualizado 2026-03-28)
- Cabecera externa del modal con:
  - boton de cerrar
  - temporizador visible de autocierre
- Temporizador:
  - ventana de 60s
  - reinicio por interaccion del usuario en el modal
  - cierre automatico al llegar a 0.

## Estado visual Kiosk Catalog (actualizado 2026-03-31)
- Cabecera superior (nombre de maquina + ubicacion) definida como franja blanca independiente del fondo general.
- Ajuste aplicado en layout normal y layout legacy para mantener consistencia en Android `7.1.2`.
- Fondo general del catalogo mantiene tratamiento visual separado (degradado), sin invadir la franja superior blanca.
- Estado: ajuste visual de cabecera listo para validacion final en tablet vending real.

## Barra de categorias y carrito (actualizado 2026-04-20)
- Se implemento barra de categorias estaticas debajo del carrusel para preparar la integracion futura de categorias dinamicas desde backend.
- El carrito se integro en la misma fila de categorias (lado derecho) con boton azul tipo pill:
  - texto `Comprar`
  - icono de carrito
  - burbuja de contador.
- Se aplicaron ajustes de posicion para que la burbuja del contador:
  - quede en borde superior derecho del bloque azul,
  - no tape el icono,
  - no se recorte en pantalla.
- Estado:
  - implementado en layout normal y legacy.
  - pendiente solo conexion backend para categorias dinamicas (la UI base ya esta lista).

## Densidad visual de grilla (actualizado 2026-04-02)
- Se simplifico la informacion visible en cada celda del planograma:
  - en grilla queda visible solo el codigo de celda.
  - precio, nombre y stock pasan a consultarse en el modal de detalle al tocar el producto.
- Objetivo:
  - reducir ruido visual en pantalla principal y priorizar lectura/seleccion rapida.

## Media dinamica de planograma (actualizado 2026-04-01)
- Carrusel promocional conectado al backend (`taPresentacionArchivos`) con orden visual y filtros de estado/tipo.
- Fondo principal del planograma conectado al backend (`taFondoUiPrincipal`) con fallback local cuando no hay asset valido.
- Implementacion realizada sin romper compatibilidad Android `7.1.2` y sin cambiar flujo funcional de compra.
- Estado: listo para validacion final de calidad visual en vending real (resolucion/recorte de imagenes y curaduria de assets).

## Timers en flujo de pago (actualizado 2026-04-01)
- Modal `Selecciona metodo de pago` con temporizador de autocierre.
- Modal de checkout (`Generar QR`) con temporizador de autocierre.
- Regla operativa en ambos:
  - 60s de ventana
  - reinicio por interaccion del usuario
  - autocierre + refresh de catalogo al expirar
- Estado: implementado y desplegado; pendiente solo ajuste fino visual final segun validacion en dispositivo real.

## Flujo de compra QR (actualizado 2026-04-14)
- Flujo operativo vigente:
  - `Comprar ahora` -> `Selecciona metodo de pago` -> checkout QR.
  - `Comprar` desde carrito -> `Selecciona metodo de pago` -> checkout QR.
- Modal de QR durante polling:
  - estado pendiente unificado a `Esperando confirmacion de pago...` para evitar mensajes tecnicos/confusos.
  - estado de exito se mantiene en `Pago confirmado`.
  - se incorpora accion `Cancelar` con confirmacion previa (`Si/No`).
  - al confirmar `Si`, se ejecuta `POST /api/pedido/{tnPedido}/cancelar` con motivo `CANCELADO_CLIENTE_APK`.
  - si el operador elige `No`, el flujo continua en polling normal.
- Modal de exito de dispensado:
  - se incorpora autocierre de `5s` con contador visible.

## Flujo QR backend (actualizado 2026-04-27)
- Fuente principal de metodos de pago por maquina:
  - `GET /api/maquina/metodos-pago`.
- Regla de visibilidad en APK (negocio actual):
  - mostrar solo `QR BCP` y `QR ATC`.
- Polling QR principal:
  - `POST /api/maquina/pago/qr/consultar-transaccion`.
  - payload minimo: `companyTransactionId = tnPedido`.
  - payload robusto (si existe): incluye `pagofacilTransactionId` (`taQr.tnTransaccionProveedor`).
- Decision de estado en APK:
  - pagado: `paymentStatus=2` o descripcion aprobatoria (`PAGADO/APROBADO`),
  - pendiente: `paymentStatus=1` o descripcion de espera,
  - fallido/cancelado: `3/4` o descripcion (`CANCELADO/ANULADO/REVERTIDO/FALLIDO`).
- Nota:
  - `GET /api/pedido/{tnPedido}/estado-pago` queda como apoyo diagnostico, no como fuente principal para QR.

## Metodos de pago habilitados (actualizado 2026-04-20)
- El modal `Selecciona metodo de pago` ya no depende de una opcion fija local.
- Carga metodos habilitados por maquina desde backend con token de sesion de maquina:
  - `GET /api/maquina/pago/qr/servicios-habilitados`.
- El checkout usa el `paymentMethodId` seleccionado en runtime para crear pedido QR.
- Estado:
  - implementado en APK.
  - pendiente validacion operativa final en vending real con distintos perfiles de maquina (lista de metodos variable).

## Planograma - estrategia de medios (actualizado 2026-04-20)
- Se adopto cache local de imagenes del planograma con invalidacion por ID de archivo.
- Regla operativa:
  - mantener imagen local cuando el ID de archivo no cambia,
  - reemplazar imagen local cuando el ID de archivo cambia.
- Alcance del cache por ID:
  - productos (`tnProductoArchivo`) en principal/secundaria,
  - carrusel/fondo (`tnPresentacionArchivo`).
- Uso visual acordado:
  - grilla principal usa imagen principal,
  - modal de producto seleccionado usa imagen secundaria (fallback principal),
  - lineas del carrito mantienen imagen principal.

## UX modal de dispensado (actualizado 2026-04-16)
- Modal de progreso de dispensado alineado a operacion visual en campo:
  - titulo principal: `Dispensando productos...`
  - progreso de fase por unidades reales en formato `X de Y`
  - nombre del producto actualmente en dispensado
  - imagen del producto/celda actual durante cada paso
  - icono de reloj de arena superior desde recurso real `@drawable/reloj_de_arena`
  - escalas visuales refinadas (tipografias e imagenes) para lectura operativa en tablet
- Estado:
  - implementado en APK (layout + logica de cola).
  - pendiente validacion visual final en dispositivo vending por el usuario.

## Fase post-driver done (actualizado 2026-04-16)
- Se agrego modal dedicado de retiro para la etapa entre `driver done` y `D2`:
  - aparece al evento `onNeedRetrieve`.
  - solicita retiro del producto y muestra progreso del item (`X de Y`).
  - la secuencia continua al detectar `onDone` (`D2`).
- Se aplico ajuste de transicion para no exponer texto tecnico largo por milisegundos antes del modal de retiro.

## Modal de exito final (actualizado 2026-04-16)
- Se separo el estado final de entrega en modal propio:
  - layout dedicado `dialog_dispense_success.xml` (ya no depende visualmente de `dialog_dispense_progress.xml`).
  - incluye icono de confirmacion (`@drawable/check`) en cabecera.
- Temporizador:
  - contador visible de `5s` en zona de cabecera externa.
  - cierre automatico (`dismiss`) al llegar a `0s`.
- Flujo:
  - al finalizar entrega, se abre modal de exito y se mantiene refresh de planograma.

## Autenticacion de maquina y refresh de sesion (actualizado 2026-04-15)
- Seleccion de maquina ahora exige PIN por maquina antes de entrar al catalogo.
- Autenticacion operativa:
  - `POST /api/maquinas/login` con `tcCodigoMaquina` + `tcPin`.
  - la APK guarda token operativo + expiracion + credenciales de maquina.
- Renovacion automatica:
  - si el token expira o backend responde `401`, la APK intenta relogin automatico usando credenciales de maquina guardadas.
  - aplica en carga de planograma y flujo de pagos (crear pedido, polling, cancelacion).
  - si no se puede renovar, se corta el flujo y se solicita volver a seleccionar maquina e ingresar PIN.

## UX modal de producto (actualizado 2026-04-14)
- Modal de detalle de producto actualizado para mejor legibilidad en tablet vending:
  - estructura visual por bloques (titulo, media, cantidad, resumen economico, CTAs).
  - total dinamico segun cantidad seleccionada.
  - controles de cantidad con iconografia centrada (`-`/`+`) para evitar desalineaciones por fuente OEM.
  - regla de apertura condicional:
    - si el carrito esta vacio, se mantiene modal de detalle clasico.
    - si el carrito ya tiene items, se abre modal fusionado (detalle + carrito) para continuar compra sin cambiar de contexto.

## Coordinacion de timers (actualizado 2026-04-02)
- Se incorporo regla de convivencia entre timers:
  - mientras exista cualquier modal abierto, el temporizador global de inactividad del planograma queda pausado.
  - al cerrar el ultimo modal, el temporizador global se reactiva automaticamente.
- Objetivo cubierto:
  - evitar refresh general + vaciado de carrito durante una operacion activa dentro de modales.
- Estado:
  - implementado en `KioskCatalogActivity` con contador de modales activos.
  - pendiente validacion final prolongada en vending real (sesiones largas con interacciones mixtas).

## Robustez serial en campo (actualizado 2026-04-06)
- `VendingFlowController` actualizado para operacion real con mayor tolerancia:
  - timeout de driver extendido a `60s`.
  - eliminacion de timeout del segundo click en etapa de retiro (espera indefinida hasta evento IO valido).
- Impacto funcional:
  - el flujo no corta por timeout a los `180s` en retiro.
  - disminuye abortos por ciclos lentos observados en uso real.

## Carrusel promocional (actualizado 2026-04-01)
- Decisión tomada: usar definitivamente carrusel tipo 3 (`ViewFlipper` + fade) en `KioskCatalog`.
- Se retiro el laboratorio temporal de comparacion de carruseles y su boton de acceso.
- Se mejoro visualizacion de promos:
  - render en `FIT_CENTER`
  - altura adaptativa del carrusel segun proporcion de imagen (con limites para no romper layout).
- Estado: implementado; pendiente validacion final en tablet vending sobre fluidez real con Android `7.1.2`.

## Ajustes UX modales (actualizado 2026-04-23)
- Se reviso comportamiento visual de modales de producto y dispensado para reducir saltos de interfaz.
- Criterio operativo acordado para este bloque:
  - XML define estructura y estilo base.
  - Kotlin actualiza solo datos dinamicos del flujo (texto variable, contadores, estados).
- Se aplico estandar visual en `dialog_product_cart_fusion.xml` para preview de producto con caja fija (consistencia entre imagenes).
- Se mantuvo la logica existente en `KioskCatalogActivity` segun validacion del usuario (sin cambio funcional en flujo de negocio).
- Estado:
  - iteracion visual en curso.
  - pendiente validacion final del usuario en dispositivo antes de cierre de commit.

## Incidencias y reporte visual (actualizado 2026-05-06)
- En modal de error de dispensacion se adopta guia visual por evidencia:
  - iconos SVG dedicados para cada instruccion de foto.
- Orden visual estandar por fila:
  - `numero + icono + texto`.
- Objetivo:
  - hacer mas clara la accion de reporte para operador/cliente en campo.

## Timeout de retiro IO (actualizado 2026-05-07)
- Se reactiva control de timeout en etapa IO de retiro (espera de 2do click / `D2`) para evitar espera indefinida.
- Politica actual:
  - timeout a 10s (`IO_WAIT_TIMEOUT_MS`),
  - al vencer, se marca error de retiro no confirmado.
- UX en Kiosk:
  - timeout IO muestra modal dedicado (`dialog_dispense_io_timeout`),
  - otros errores mantienen modal de incidencia general.
