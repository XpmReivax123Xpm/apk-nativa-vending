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
- [ ] Definir seleccion de vending asignada.
- [ ] Definir activacion de contexto local de vending.
- [ ] Diseñar UI funcional de catalogo y carrito.
- [ ] Definir salida protegida de modo kiosk (PIN).

### Fase 2 - Integracion backend
- [ ] Definir contratos JSON definitivos con equipo backend.
- [ ] Definir autenticacion y refresh de sesion.
- [ ] Definir endpoint y semantica para polling de pago.
- [ ] Definir endpoint de reporte por item y reporte global.
- [ ] Definir idempotencia de reportes.

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




