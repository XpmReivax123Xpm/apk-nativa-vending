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

## Decisiones vigentes
- Estrategia de confirmacion de pago: polling con backoff (v1).
- PIN de salida kiosk: gestionado por backend por vending/operador.
- No inventar comandos ni semantica del protocolo serial.
- Protocolo de recuperacion de placa: pendiente validacion tecnica con hardware real.
- Compensacion/reembolso: responsabilidad del backend.

## Modulos del scaffold
- `app`: shell Android y punto de arranque.
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
- Especificacion oficial del protocolo serial de placa/controladora.
- Formato JSON/API definitivo con equipo backend.
- Politica final de recuperacion local de hardware en estado incierto.
- Estrategia exacta de reconciliacion post reinicio electrico.

## Coordinacion externa
Este proyecto Android se coordinara con otro Codex/equipo que trabaja backend en otra computadora. Las integraciones de contratos deben cerrarse de forma conjunta.

