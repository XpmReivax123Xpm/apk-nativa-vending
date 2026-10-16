# Acceso al kiosko, identificador del equipo y watchdog

## 1. Como se entra

La pantalla de acceso es **solo el PIN**. Nunca se pide, ni se muestra, ni se puede teclear el
identificador del equipo: la APK manda `tcDireccionMac` con la MAC que lee del propio aparato, mas el PIN
que teclea el operador. La maquina se identifica por su APARATO —quien conoce el codigo impreso en el
frente no puede entrar al planograma de otra maquina—.

## 2. De donde sale la MAC

La lee la propia kiosk del **nodo del kernel del puerto ETHERNET**: `/sys/class/net/eth0/address` (y
`eth1`/`end0` como variantes del mismo nodo, por si una placa nombra distinto la interfaz cableada). Sin
permisos, sin root, sin Device Owner y sin depender de ninguna otra app.

- Es la MAC de **fabrica de la placa de red**: estable entre reinicios, redes y actualizaciones.
- **Por que no la de la Wi-Fi**: es justo la que Google bloqueo. El nodo de la wlan0 lleva la etiqueta
  `sysfs_mac_address` y un `neverallow` en el sepolicy de Android —ninguna app puede leerlo y el
  fabricante no puede habilitarlo—, y solo el sistema la lee (que es lo que hace por nosotros la API de
  Device Owner). Ademas la Wi-Fi no siempre esta encendida y su MAC puede ser aleatoria por red.
- El nodo del ethernet no lleva esa etiqueta y el firmware de estas tablets (familia ZTL) lo deja leer: es
  la misma via que usa su propia API (`ZtlApi.ZtlManager.getMacAddress()` = leer
  `/sys/class/net/eth0/address`, codigo publico en `github.com/Ztldc/ZtlApi`).
- `00:00:00:00:00:00` **no** cuenta: es una placa de red sin direccion asignada y no identifica nada.
- Implementado en `app/src/main/java/com/vending/kiosk/app/device/DeviceIdentity.kt`.

## 3. El identificador es requisito obligatorio

Sin MAC no se entra, y no hay salida por ingreso manual. Lo que se ve:

- Pantalla «No se pudo obtener el identificador de esta tablet», con el motivo, un boton «Reintentar»
  (vuelve a leerlo) y «Volver al menu» (el menu **no** entra al kiosko).
- El arranque automatico tampoco se salta esto: sin identificador no redirige al catalogo.
- El motivo queda en el log del equipo: `adb logcat -s DeviceIdentity`.

## 4. La MAC se muestra UNA sola vez

La primera vez que el aparato entrega su MAC, la pantalla la muestra **en grande** con una advertencia:
hay que anotarla y cargarla en el ERP como identificador de esa maquina (`MAQUINA.DireccionMac`). Al
tocar «Ya la anote, continuar», el kiosko sigue con el PIN y la MAC no se vuelve a mostrar.

Perdiste el dato: borrar los datos de la app (`adb shell pm clear com.vending.kiosk`, o Ajustes >
Aplicaciones > Vending Kiosk > Borrar datos) la vuelve a mostrar la proxima vez que se entre al kiosko. Se
borra tambien el equipo guardado, asi que hay que volver a entrar con el PIN.

## 5. Si NO se obtiene la MAC

| Motivo en pantalla | Que paso | Que hacer |
|---|---|---|
| no expone ninguna interfaz de red cableada | no existe el nodo (`eth0`/`eth1`/`end0`) | revisar el equipo: esta pensado para tablets con puerto ethernet |
| hubo un error al leerla | el firmware bloqueo el nodo (SELinux) | ver el detalle y el log |

```bash
adb shell cat /sys/class/net/eth0/address                            # el valor, como shell
adb shell run-as com.vending.kiosk cat /sys/class/net/eth0/address   # como la app
```

## 6. Watchdog de hardware

La app alimenta el watchdog de la **placa** (no un temporizador propio) llamando al servicio del
fabricante (`com.ztl.helper.ZTLHelperService`, `cmd=appWatchDog`) **cada 20 segundos**. Al primer alimento
el sistema deja de alimentarlo por su cuenta: si el kiosko se cuelga o lo matan, nadie manda el alimento y
la placa reinicia la tablet sola (~200 segundos).

- **Consecuencia a tener presente**: desde ese momento el equipo depende de que la app este corriendo.
  Cerrarla (o desinstalarla) termina en reinicio. Es lo que se quiere en una maquina desplegada, pero
  conviene saberlo antes de instalarla en la tablet de pruebas.
- Requisitos: hardware que soporte watchdog, firmware con el servicio del fabricante, y **el jumper de la
  placa sacado** (con el jumper puesto el watchdog no reinicia).
- En equipos sin el servicio del fabricante no hace nada (ni lo intenta): `adb shell pm path
  com.ztl.helper`.
- Log: `adb logcat -s HardwareWatchdog`.
- Implementado en `app/src/main/java/com/vending/kiosk/app/device/HardwareWatchdog.kt`; arranca con
  `MainActivity`.

## 7. Trampas

- En `MAQUINA.DireccionMac` tiene que estar la MAC del **ethernet**, que es la que manda la kiosk. Ojo: es
  distinta de la del Wi-Fi (la que devolvia la via Device Owner que se descarto).
- Formato: seis pares hexadecimales en mayusculas separados por dos puntos (`XX:XX:XX:XX:XX:XX`); la app
  normaliza asi, y el valor del backend debe tener la misma forma.
- Si la MAC no coincide, el login contesta credenciales incorrectas aunque el PIN este bien, y **no hay
  campo para corregirlo en el kiosko**: se corrige en el ERP.
- En el emulador de la PC no hay tarjeta de red que leer: el build de simulacion (`-PvendingSerial=tcp`,
  solo debug) usa el equipo `02:SIM:00:00:00:01` del backend simulado.
