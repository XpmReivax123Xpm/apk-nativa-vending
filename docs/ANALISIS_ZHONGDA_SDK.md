# Analisis del paquete "Zhong Da SDK English Version"

Fecha: 2026-03-20
Modo: lectura/investigacion (sin cambios en codigo funcional)

## 1. Que revise
- `C:\Users\xavier\Desktop\Zhong Da SDK English Version\Demo`
- `C:\Users\xavier\Desktop\Zhong Da SDK English Version\SDK`
- `C:\Users\xavier\Desktop\Zhong Da SDK English Version\USDK Interface Description Documentation v2.0.pdf`
- `C:\Users\xavier\Desktop\Zhong Da SDK English Version\_decompiled_usdk`

## 2. Hallazgos clave (confirmados)

### 2.1 Si hay API oficial para control de vending
En el SDK aparece una clase `UBoard` con metodos de operacion de maquina:
- apertura/cierre serial: `EF_OpenDev`, `EF_CloseDev`
- despacho: `Shipment`, `GetShipmentStatus`
- estado de entradas/sensores: `GetIOStatus`, `GetDropStatus`, `GetYStatus`, `GetXStatus`
- control de ejes y motores: `ToY`, `ToX`, `RunMoto`, `MotoTimeout`, etc.

Evidencia:
- `...\Demo\USDKDemo\app\src\main\java\cc\uling\usdk\demo\MainActivity.java`
- `...\_decompiled_usdk\sources\cc\uling\usdk\board\UBoard.java`
- `...\_decompiled_usdk\sources\cc\uling\usdk\board\e.java`

### 2.2 Si existe "reset" por comando
Si, existe un metodo explicitamente llamado `ResetLift`.
- En el demo lo usan con texto: "Send the reset command for the lift motor."
- En decompilado, `ResetLift(...)` envia el token de comando `"RESET"`.

Evidencia:
- Demo: boton `btn_left_reset` invoca `mBoard.ResetLift(para)`.
- UBoard:
  - `public void ResetLift(ResetReplyPara resetReplyPara)`
  - internamente: `a(resetReplyPara, "RESET", ...)`

### 2.3 Importante: reset de elevador != reboot total de placa
Con lo revisado, lo explicitamente documentado/codificado es reset del lift/plataforma.
No encontre un metodo claramente llamado reboot total de controladora (ej. `RebootBoard`, `RestartController`, etc.) en este SDK.

## 3. Segunda pasada especifica del PDF (lo que si se pudo validar)

### 3.1 Limitacion tecnica real
En esta maquina no hay extractor OCR/texto instalado para PDF (`pdftotext`, `pdfinfo`, `mutool`) ni runtime de Python operativo para librerias PDF.
Por eso, el contenido textual completo del PDF no se pudo leer limpio pagina por pagina.

### 3.2 Evidencia util recuperada del propio PDF
Aun sin OCR completo, el PDF si expone su indice/bookmarks internos (Outlines), y ahi se confirma:
- El documento tiene `Count 30` paginas.
- Tiene seccion `Class UBoard`.
- Incluye entradas de API con firmas, entre ellas:
  - `Board. ResetLift (ResetReplyPara)`
  - `Board. Shipment (SReplyPara)`
  - `Board. GetShipmentStatus (SSReplyPara)`
  - `Board. GetIOStatus (IOReplyPara)`
  - `Code Fault Table`

Adicionalmente, la entrada `Board. ResetLift (ResetReplyPara)` apunta a destino de pagina con objeto `107 0 R`, que cae dentro del bloque de paginas del PDF.

### 3.3 Interpretacion tecnica
El PDF refuerza que `ResetLift` es parte del API de movimiento/actuacion de la maquina, no una llamada de reboot de sistema completo.

## 4. Lo que esto significa para tu objetivo
1. Si la placa sigue viva por UART, hay una opcion realista: probar `ResetLift` como recuperacion local.
2. Si la placa queda totalmente colgada y no responde por serial, ningun comando por UART ayudara; ahi se requiere control electrico externo (relay/watchdog/IO de power).
3. No debemos asumir que `driver 000` siempre se recupera con `ResetLift`; depende del firmware real.

## 5. Riesgo tecnico y criterio de seguridad
- Riesgo alto: ejecutar comandos no verificados puede descalibrar o dejar estado inconsistente.
- Recomendacion: no usar comandos de posicion/calibracion (`SetPickX/Y`, `SeXPos`, `SeYPos`, etc.) en pruebas iniciales de recuperacion.
- Primera prueba segura: abrir puerto, consultar estado, ejecutar solo `ResetLift`, volver a consultar estado.

## 6. Propuesta de prueba minima (controlada)
1. Registrar baseline de estados (`GetShipmentStatus`, `GetIOStatus`, `GetYStatus`, `GetXStatus`).
2. Provocar escenario de fallo conocido (si es reproducible y seguro).
3. Intentar `ResetLift` una sola vez.
4. Reconsultar estados y validar si vuelve a estado operativo.
5. Si no revive en N intentos (N bajo, por ejemplo 1-2), marcar incidencia y detener operacion.

## 7. Dependencias pendientes
- Confirmar semantica exacta de codigos de fallo del firmware (tabla completa).
- Confirmar si el proveedor tiene comando de reboot completo oculto/no documentado.
- Validar comportamiento real de `ResetLift` en tu modelo exacto de placa.

## 8. Decision de arquitectura recomendada
Mantener en la app Kotlin una interfaz de recuperacion separada:
- `MachineRecoveryGateway` (o dentro de `MachineProtocolAdapter`)
- Implementacion inicial: `tryResetLift()`
- Politica: reintento limitado, timeout, y salida a "incidencia/manual" si no recupera.

Asi dejamos la arquitectura preparada sin inventar semanticas de hardware.

## 9. Validacion en campo (2026-03-20)
- Se realizo prueba real forzando aborto de secuencia con polling agresivo (modo estres).
- Resultado observado: la plataforma quedo atascada; luego operador ejecuto `STOP` y despues `ResetLift`.
- `ResetLift` se envio correctamente (log visible) y la plataforma recupero su estado base.
- Conclusión: para este escenario concreto, `ResetLift` si funciono como recuperacion local.
- Nota: esto no garantiza recuperacion para todos los tipos de fallo (especialmente cuelgue total sin respuesta serial), pero ya no es una hipotesis; hay evidencia positiva en campo.
