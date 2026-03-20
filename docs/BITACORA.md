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
