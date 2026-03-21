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

## Coordinacion externa
Este proyecto Android se coordina con otro equipo/Codex de backend. Los contratos de integracion deben cerrarse en conjunto antes de la fase de implementacion de negocio.






