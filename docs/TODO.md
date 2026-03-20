# TODO - apk-nativa-vending

## Checklist de aceptacion del bootstrap (primera orden)
- [x] Existe `apk-nativa-vending/` en la raiz del repo.
- [x] Existe `apk-nativa-vending/docs/PLAN.md`.
- [x] Existe `apk-nativa-vending/docs/BITACORA.md`.
- [x] Existe `apk-nativa-vending/docs/TODO.md`.
- [x] El scaffold refleja separacion por modulos acordados.
- [x] No se toco `hola mundo` ni carpetas de codigo recuperado.
- [x] Se documentaron plan actual, hecho hoy y pendientes.

## Backlog priorizado

### Fase 1 - Especificacion funcional y tecnica
- [ ] Cerrar especificacion de `operator-auth`.
- [ ] Cerrar especificacion de `vending-context`.
- [ ] Cerrar especificacion de `kiosk-control`.
- [ ] Cerrar modelo de estados de transaccion y entrega.
- [ ] Definir esquema de errores y eventos observables.

### Fase 2 - Integracion backend
- [ ] Definir contratos JSON definitivos con equipo backend.
- [ ] Definir autenticacion y refresh de sesion.
- [ ] Definir endpoint y semantica para polling de pago.
- [ ] Definir endpoint de reporte por item y reporte global.
- [ ] Definir idempotencia de reportes.

### Fase 3 - Serial/hardware
- [ ] Recibir protocolo oficial de placa/controladora.
- [ ] Mapear comandos/respuestas en `MachineProtocolAdapter`.
- [ ] Definir timeouts operativos por comando.
- [ ] Definir politica de reintentos seguros.
- [ ] Definir condiciones de estado incierto.

### Fase 4 - Flujos de negocio
- [ ] Flujo completo de compra directa.
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

