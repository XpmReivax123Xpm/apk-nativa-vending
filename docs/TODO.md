# TODO - apk-nativa-vending

## Checklist de aceptacion del bootstrap (primera orden)
- [x] Existe `apk-nativa-vending/` en la raiz del repo.
- [x] Existe `apk-nativa-vending/docs/PLAN.md`.
- [x] Existe `apk-nativa-vending/docs/BITACORA.md`.
- [x] Existe `apk-nativa-vending/docs/TODO.md`.
- [x] El scaffold refleja separacion por modulos acordados.
- [x] No se toco `hola mundo` ni carpetas de codigo recuperado.
- [x] Se documentaron plan actual, hecho hoy y pendientes.

## Logros ya completados (hasta hoy)
- [x] Selector de modos con 3 opciones (`Kiosk`, `Tester`, `Calibrator`).
- [x] Integracion de `Vending Tester` recuperado dentro de la app principal.
- [x] Integracion de `Vending Calibrator` recuperado dentro de la app principal.
- [x] Soporte de `RESET LIFT` en tester para retorno a base.
- [x] Ajustes de seguridad operativa UI en tester:
  - [x] `/dev/ttyS1` y `9600` no editables.
  - [x] `Continuar` con estado visual apagado/encendido segun flujo.
  - [x] `RESET LIFT` solo se habilita al detectar `driver status 0000`.
- [x] Mejora visual de selector y pantalla tester.

## Backlog priorizado

### Fase 1 - Cierre funcional de negocio (Kiosk)
- [x] Definir e implementar flujo inicial de login operador (pantalla + POST /api/login + guardado de token), validado en vending.
- [x] Pulido UI/UX de login kiosk (branding BoxiPago, animaciones de entrada, mensajes operativos y eliminacion de acento rosado).
- [x] Definir seleccion de vending asignada (listado de maquinas backend + seleccion de activa).
- [ ] Definir activacion de contexto local de vending.
- [~] Diseñar UI funcional de catalogo y carrito.
- [x] Catalogo base por planograma/celdas implementado (sin carrito aun).
- [x] Carrito mejorado con modal operativo por item (ajuste cantidad `+/-`, total en vivo y compra directa).
- [x] Acceso a carrito con icono flotante sin afectar area de carrusel.
- [~] Integrar imagenes reales de carrusel/catalogo.
  - [x] Carrusel promocional desde `taPresentacionArchivos`.
  - [x] Fondo de planograma desde `taFondoUiPrincipal`.
  - [x] Definir carrusel definitivo tipo 3 (`ViewFlipper + fade`) y retirar laboratorio temporal.
  - [x] Ajustar visualizacion de promos a `FIT_CENTER` + altura adaptativa por proporcion.
  - [ ] Validacion final de calidad visual y fluidez en tablet vending real (Android `7.1.2`).
- [x] Definir salida protegida de modo kiosk (PIN) con validacion backend.
- [~] Endurecer bloqueo total de navegacion del sistema segun politica del dispositivo (Device Owner / launcher kiosk).
  - [x] Infraestructura base de kiosk administrado (DeviceAdminReceiver + policy XML + `KioskPolicyManager` + `lockTaskMode` en manifest).
  - [x] Integracion de intento de lock task administrado en `KioskCatalogActivity` con fallback visual.
  - [ ] Provisioning real de Device Owner en tablet vending y validacion final contra barra OEM.

### Fase 2 - Integracion backend
- [ ] Definir contratos JSON definitivos con equipo backend.
- [ ] Definir autenticacion y refresh de sesion.
- [ ] Definir endpoint y semantica para polling de pago.
- [ ] Definir endpoint de reporte por item y reporte global.
- [ ] Definir idempotencia de reportes.
- [x] Migrar base URL de entorno LAN a dominio backend productivo.

- [x] Ajustar UX de calibrador: Conectar/Desconectar con estado habilitado/deshabilitado y foco en plataforma.
- [ ] Cerrar traduccion completa de codigos/errores de placa en calibrador (tabla validada en campo).

### Fase 3 - Robustez serial/hardware
- [ ] Formalizar contrato del protocolo con placa real (PENDIENTE HW).
- [ ] Documentar matriz de errores reales observados (driver 0000 y otros).
- [ ] Definir politica de reintentos seguros por comando.
- [ ] Definir estrategia de recuperacion local antes de compensacion backend.
- [ ] Definir condiciones de estado incierto y corte seguro.

### Fase 4 - Flujos completos E2E
- [ ] Flujo completo de compra directa (login -> pago -> entrega).
- [ ] Flujo de recogida por reserva.
- [ ] Reporte de entrega parcial y abortos.
- [ ] Reconciliacion post reinicio.

### Fase 5 - Hardening y calidad
- [ ] Unit tests de dominio.
- [ ] Tests de orquestacion con fakes de backend/hardware.
- [ ] Pruebas de resiliencia: red intermitente, serial timeout, reinicio.
- [ ] Politica de logs tecnicos y trazabilidad por transaccion.

## Notas de trabajo
- Si una decision depende del protocolo real de hardware, se marca como `PENDIENTE HW`.
- Si una decision depende de backend, se marca como `PENDIENTE API`.
- Documentar cada cambio relevante en `docs/BITACORA.md`.
- Android vending objetivo actual: `7.1.2` (API 25). Evitar componentes UI inestables para esa API.








## Reglas de colaboracion (vigentes)
- [x] Usuario a cargo de todos los `git commit`.
- [x] Antes de editar: declarar archivos exactos a modificar.
- [x] Despues de editar: reportar `git diff --name-only` + resumen corto.
- [x] Aplicar cambios pequenos/verificables; evitar ediciones masivas riesgosas.
- [x] Ante falla: rollback del bloque afectado al ultimo estado estable.

### Fase 2 - Integracion backend (estado al 2026-03-24)
- [x] Crear pedido + generar QR desde APK.
- [x] Mostrar QR base64 como imagen en modal de pago.
- [x] Polling de estado de pago cada 5s (timeout 120s).
- [~] Cerrar criterio final de confirmacion con backend (actualmente compatibilidad temporal: `tnEstadoPago == 2` o `tnEstadoPedido == 2`, con fallback `estado == 2`) y eliminar dependencias de campos legacy.
- [x] Cierre automatico de modal QR al timeout + refresh de catalogo.
- [x] Insertar modal intermedio de seleccion de metodo de pago (v1: solo `QR BCP`) para escalar a nuevos metodos.
- [x] Unificar paleta visual de modales de compra/pago con tema kiosk claro.
- [x] Bloquear interaccion durante generacion de QR con modal de progreso no cancelable.
- [x] Agregar temporizador de autocierre en modal `Selecciona metodo de pago` (60s + refresh al expirar).
- [x] Agregar temporizador de autocierre en modal de checkout previo a `Generar QR` (60s + refresh al expirar).
- [x] Coordinar timers de modales con timer global de inactividad del planograma (pausar refresh global mientras haya modal abierto).

### Fase 4 - Flujos completos E2E
- [~] Compra directa: implementada hasta pago y disparo de dispensado secuencial; falta endurecer manejo de estados excepcionales/reporteria final.

### Fase 5 - Operacion kiosk
- [x] Activar bloqueo kiosk al entrar a planograma.
- [x] Implementar gesto de salida segura (hold 2s en titulo de maquina + PIN).
- [x] Implementar teclado propio para `Nombre`, `Telefono`, `CI/NIT` y `PIN`.
- [x] Agregar temporizador de autocierre (60s) en modal de producto con reinicio por interaccion.
- [x] Agregar cierre manual visible en cabecera del modal de producto.
- [x] Aislar visualmente la cabecera superior del `KioskCatalog` con franja blanca full-width independiente del fondo.
- [ ] Validar y cerrar bloqueo de barra/sistema con configuracion final del dispositivo vending.
