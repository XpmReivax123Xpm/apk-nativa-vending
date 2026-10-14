# Simulador de placa de vending en la PC

Guia para correr la APK en el emulador de la PC y simular la placa de la maquina
expendedora, incluyendo el ciclo completo de dispensacion.

## 1. Por que hace falta el simulador

En la tablet vending la APK abre el puerto serie `/dev/ttyS1` a 9600 y habla
Modbus RTU con la placa. Ese puerto lo abre una libreria **nativa ARM**
(`libronyuanserial_port.so`, solo `armeabi-v7a`; el SDK del calibrador usa
`libserial_port.so`, tambien solo ARM).

El emulador de la PC es **x86_64**: esa libreria no existe para esa arquitectura
y no puede cargar (`UnsatisfiedLinkError`). Por eso la app no puede "correr tal
cual" en la PC.

La solucion es una costura minima: en **modo simulacion** la app no abre el
puerto serie nativo, sino un socket TCP contra el simulador de placa que corre en
la PC. Las tramas Modbus (CRC, registros, secuencia de IO) son exactamente las
mismas; solo cambia el medio fisico. En la tablet el build normal sigue igual que
siempre.

## 2. Piezas

| Pieza | Que es |
|---|---|
| `tools/board-sim/board_sim.py` | El servicio simulador de placa (Python, sin dependencias) |
| `tools/backend-mock/mock_backend.py` | El backend simulado: datos de prueba, QR local y pago que se confirma solo (sin pasarelas reales) |
| `-PvendingSerial=tcp` | Compila la APK en modo simulacion (por defecto es `native`) |
| `10.0.2.2:9100` | Direccion con la que el emulador ve a la PC anfitriona (placa) |
| `10.0.2.2:9200` | Direccion del backend simulado (se configura solo al compilar en modo simulacion) |
| AVD `vending-sim` | Emulador Android x86_64 |

## 2.1 Garantias: el build de despliegue no se ve afectado

El modo simulacion **solo** existe si se compila con `-PvendingSerial=tcp` (o con
`vendingSerial=tcp` en `local.properties`, que no esta en git). Hay tres cerrojos:

1. Sin esa propiedad el build es identico al de siempre: puerto serie nativo
   (`/dev/ttyS1`) y backend de produccion.
2. El build de **release** fuerza `SERIAL_TRANSPORT=native` y
   `BACKEND_BASE_URL=https://boxipagobackend.pagofacil.com.bo` aunque la propiedad
   este puesta en la maquina o en el IDE: es imposible desplegar un APK en modo
   simulacion.
3. Ademas, a nivel de codigo el modo simulacion solo se aplica en builds de
   depuracion: aunque un APK de release trajera el valor `tcp` horneado, la app
   abriria el puerto serie nativo igual.

Lo unico que el modo simulacion **no** puede permitir es apuntar a otro backend en
release: para eso hay que sacar la propiedad `-PvendingBackendUrl` (o borrarla de
`local.properties`) antes de compilar la release.

## 3. Requisitos

- Python 3.10 o superior (`python --version`).
- Android Studio con:
  - JDK 17 (`C:\Program Files\Java\jdk-17` en esta PC),
  - Android SDK Platform 34 y Build-Tools 34,
  - imagen de sistema `system-images;android-34;google_apis;x86_64`,
  - `cmdline-tools` (para `avdmanager` y `sdkmanager`).

Si falta la imagen del emulador:

```bash
SDK="C:/Users/usuario/AppData/Local/Android/Sdk"
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" \
  "$SDK/cmdline-tools/latest/bin/sdkmanager.bat" "system-images;android-34;google_apis;x86_64"
```

## 4. Crear el AVD (una sola vez)

```bash
SDK="C:/Users/usuario/AppData/Local/Android/Sdk"
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" \
  "$SDK/cmdline-tools/latest/bin/avdmanager.bat" create avd \
  -n vending-sim -k "system-images;android-34;google_apis;x86_64" -d pixel_5 --force
```

## 5.1 Arrancar el backend simulado

Doble clic en `tools/backend-mock/start-mock.bat`, o a mano:

```bash
cd tools/backend-mock
python mock_backend.py
```

Datos de prueba: maquina `MAQ-SIM-01`, PIN de maquina `1234`, PIN admin `9999`.
El login de operador acepta cualquier correo/contrasena. El pago QR se confirma
solo a los pocos sondeos (nada real, ninguna pasarela).

Control en caliente:

```bash
curl "http://127.0.0.1:9200/__status"
curl "http://127.0.0.1:9200/__control?payment=fail"     # paid | pending | fail | cancel
```

## 5.2 Arrancar el simulador de placa

Doble clic en `tools/board-sim/start-sim.bat`, o a mano:

```bash
cd tools/board-sim
python board_sim.py
```

Escenarios utiles:

```bash
python board_sim.py --scenario stuck        # plataforma atorada
python board_sim.py --scenario crushed      # producto aplastado
python board_sim.py --scenario driver0000   # falla de dispensacion
python board_sim.py --scenario nopickup     # el cliente no retira
python board_sim.py --selftest              # autoprueba del protocolo (sin APK)
```

Con el simulador corriendo, en su misma consola se pueden escribir comandos
(tambien por TCP en `127.0.0.1:9101`, o con `simctl.py` desde otra consola):

```bash
python simctl.py status            # estado actual de la placa simulada
python simctl.py scenario stuck    # cambiar escenario en caliente
```

| Comando | Que hace |
|---|---|
| `help` | lista de comandos |
| `status` | estado actual de la placa simulada |
| `scenario normal` | ciclo completo exitoso |
| `scenario stuck` | la plataforma queda atorada (habilita RESET LIFT) |
| `scenario crushed` | producto aplastado (corte de dispensacion) |
| `scenario driver0000` | driver nunca termina (falla de dispensacion) |
| `scenario nopickup` | dispensado correcto pero nadie retira el producto |
| `scenario transitions` | incluye las transiciones informativas `42` / `52` |
| `speed 1.5` | acelera la secuencia (0.5 a 1.5) |
| `cell 37` | fuerza un ciclo de dispensacion de la celda 37 |
| `reset` | vuelve a reposo |

El escenario se puede cambiar **en caliente**: por ejemplo se lanza un ciclo
normal y luego se escribe `scenario stuck` para que el siguiente ciclo falle.

## 6. Arrancar el emulador

```bash
SDK="C:/Users/usuario/AppData/Local/Android/Sdk"
"$SDK/emulator/emulator.exe" -avd vending-sim -gpu auto
```

Esperar a que termine de bootear (`adb shell getprop sys.boot_completed` debe
devolver `1`).

## 6.1 Ejecutar todo desde Android Studio (manual)

1. **Abrir el proyecto**: Android Studio > *File > Open* >
   `C:\Alain\BOXIPAGOS\apk-nativa-vending`.
2. **Encender el emulador**: menu *Tools > Device Manager* (o el icono de telefono en
   la barra lateral). El AVD `vending-sim` ya esta creado: apretar el ▶ que aparece a
   su derecha. Si no estuviera, *Create Device* > Pixel 5 > Android 14 (API 34) >
   imagen `x86_64`.
3. **Elegir dispositivo y modulo**: en la barra superior, modulo `app` y dispositivo
   `vending-sim`.
4. **Compilar y ejecutar**: boton ▶ *Run*. Eso genera el build **normal** (serie
   nativa y backend real). La app abre; si tocas `Conectar` en el Tester vas a ver
   `UnsatisfiedLinkError` porque la libreria nativa del puerto serie es solo ARM:
   es lo esperado en la PC.
5. **Para el build de simulacion**, elegi uno de estos dos caminos:
   - **Recomendado**: agregar una linea a `local.properties` (archivo local, no esta
     en git):
     ```
     vendingSerial=tcp
     ```
     Desde ahi, ▶ *Run* compila en modo simulacion (y apunta solo al backend
     simulado). Para volver al build normal, borrar esa linea.
   - Alternativa: *Settings > Build, Execution, Deployment > Gradle > Command line
     options* con `-PvendingSerial=tcp`. Ojo: esa opcion afecta a **todos** los
     builds que lances desde el IDE, asi que hay que quitarla despues (la release
     igual queda protegida por los cerrojos de la seccion 2.1).
6. **Desde la terminal** (equivalente, sin tocar el IDE):

   ```bash
   JAVA_HOME="C:/Program Files/Java/jdk-17" ./gradlew :app:installDebug -PvendingSerial=tcp
   ```

   Otros parametros utiles: `-PvendingBackendUrl=http://10.0.2.2:9200`,
   `-PvendingSimEndpoint=10.0.2.2:9100`.

## 7. Compilar e instalar la APK en modo simulacion

```bash
cd apk-nativa-vending
JAVA_HOME="C:/Program Files/Java/jdk-17" ./gradlew :app:installDebug -PvendingSerial=tcp
```

- `-PvendingSerial=tcp` apunta la app al simulador en vez de `/dev/ttyS1`.
- Sin esa propiedad, la APK es la de produccion (puerto serie nativo). El APK que
  se genera por defecto **no cambia en nada**.
- Se puede cambiar el destino con `-PvendingSimEndpoint=10.0.2.2:9100`.

En Android Studio, si se quiere lanzar con el modo simulacion, agregar
`-PvendingSerial=tcp` en *Settings > Build, Execution, Deployment > Gradle >
Command line options* (y quitarlo despues).

## 8. Probar el ciclo de dispensacion

1. Abrir **Vending Tester** desde el menu.
2. Tocar **Conectar** (el puerto `/dev/ttyS1` y `9600` quedan igual en pantalla,
   pero el log muestra `Abriendo simulador de placa en 10.0.2.2:9100`).
3. Escribir una celda, por ejemplo `37`, y tocar **INICIAR PEDIDO**.
4. En pantalla se ve, en el log del tester:
   - `Puerta blanca: ABRIENDO` → `Plataforma: SUBIENDO` → `Plataforma: BAJANDO` →
     `Puerta blanca: CERRANDO` → `DISPENSACION COMPLETA`,
   - y luego el retiro: `cerrada SIN producto`, `2do click confirmado -> FIN`.
5. Con el escenario `stuck`, en cambio aparece `PLATAFORMA ATORADA` y se habilita
   **RESET LIFT**, que ejecuta la recuperacion a base y reanuda el retiro.

En el simulador se ve la traza completa con cada trama:

```
18:22:01.500 [RX ] 01 10 00 01 00 02 04 1B 03 01 01 8B 5A  <- write multiple
18:22:01.500 [SIM] ciclo #1 para celda 37 (escenario=normal)
18:22:01.500 [IO ] io = 0xD2  (puerta blanca: abriendo)
18:22:02.022 [START] consulta de registro 0x000B ...
```

## 8.1 Probar el Kiosk completo (login, catalogo, pago y entrega)

Con el backend simulado corriendo:

1. Abrir **Vending Kiosk** desde el menu.
2. Correo y contrasena: cualquiera (por ejemplo `operador@sim.local` / `sim123`).
3. Aparece **MAQ-SIM-01 / PC de pruebas (simulador)**; tocarla e ingresar el PIN
   `1234`.
4. Entra al planograma con 15 productos e imagenes servidas por el backend simulado.
5. Tocar un producto (se agrega al carrito) y pulsar **PAGAR**.
6. Elegir **QR BCP** > **GENERAR QR**: se muestra el QR generado localmente y la app
   empieza a sondear el pago. A los pocos sondeos el pago se confirma solo y arranca
   la dispensacion real contra la placa simulada (celda del producto elegido).
7. Al terminar aparece el modal de exito y el catalogo se refresca.

Para probar los caminos de error del pago:

```bash
curl "http://127.0.0.1:9200/__control?payment=fail"     # rechazado
curl "http://127.0.0.1:9200/__control?payment=pending"  # nunca confirma (queda esperando)
curl "http://127.0.0.1:9200/__control?payment=paid"     # vuelve a lo normal
```

## 8.2 Probar el Calibrador

1. Abrir **Vending Calibrator** desde el menu y pulsar **CONECTAR**: se conecta a la
   placa simulada (el log muestra `SIM: Conectado a simulador de placa en 10.0.2.2:9100`).
2. **ESTADO PLATAFORMA**: lee el estado real del registro del SDK y muestra
   `En reposo. Error: Sin falla`.
3. **DATOS GUARDADOS**: lee las 10 posiciones Y de la placa y llena los campos en cm.
4. **GUARDAR CAMBIOS**: envia la tabla de puntos Y calculada desde los cm.
5. **PROBAR POSICION**: mueve la plataforma a la posicion pedida (`ToY`).

Desde el simulador se puede forzar una falla para ver el camino de error:

```bash
python simctl.py fault 3      # el calibrador mostrara la falla reportada por la placa
python simctl.py fault 0      # sin falla
python simctl.py ypoints 0,100,200,300,400,500,600,700,800,900
```

## 9. Protocolo simulado

Registros Modbus que la APK consulta y escribe:

| Registro | Uso | Valores |
|---|---|---|
| `0x0003` | estado del driver | `0x0000` driver detenido (la APK lo trata como anomalia), `0x0001` dispensando, `0x0200` ciclo terminado (DONE) |
| `0x000B` | palabra de estado de IO | ver tabla de codigos |
| `0x0001` | seleccion de celda (escritura multiple) | primer byte = celda - 10 |
| `0x1002` | `ResetLift` | plataforma a base |
| `0x1010` | `ToY` (extension del simulador) | posicion Y: `0` = base |

Nota sobre el driver: la APK corta la dispensacion si lee `0x0000` tres veces
seguidas **sin** progreso de IO, y si hay progreso se pasa al retiro
(`DRIVER_ZERO_PICKUP_MODE`, tolerancia definida en campo). Por eso el simulador
reporta `0x0001` mientras dispensa y reserva el `0x0000` para el escenario de
falla `driver0000`, que es el que reproduce la anomalia real.

Codigos de IO que la APK interpreta:

| Codigo | Significado |
|---|---|
| `0xD2` | puerta blanca abriendo (en dispensacion) / segundo click (en retiro) |
| `0xD8` | plataforma subiendo |
| `0xC8` | plataforma bajando |
| `0xC2` | puerta blanca cerrando |
| `0x02` | puerta chica abierta (primer contacto) |
| `0x82` | primer click del retiro |
| `0x92` | puerta chica cerrada sin producto |
| `0x12` | producto retirado con puerta abierta |
| `0x42` / `0x52` | transiciones informativas |
| `0x0A` / `0x8A` / `0x9A` / `0xDA` | variantes con puerta blanca abierta (retry manual) |

Tiempos del ciclo normal (ajustables con `speed`):

```
0.0s  selector de celda            -> 0xD2 puerta blanca abriendo
1.5s                               -> 0xD8 plataforma subiendo
3.5s                               -> 0xC8 plataforma bajando
8.0s                               -> 0xC2 puerta blanca cerrando
11.0s                              -> driver DONE -> la APK pasa a retiro
11.6s                              -> 0x02 puerta chica abierta
13.1s                              -> 0x82 primer click
14.6s                              -> 0x92 cerrada sin producto
16.1s                              -> 0xD2 segundo click -> ciclo terminado
```

Los margenes no son arbitrarios: la APK marca `PLATAFORMA ATORADA` si el driver
termina antes de 3 s de ver `0xC8`, y `PRODUCTO APLASTADO` si pasan mas de 12 s
sin `0xC2`. Las secuencias del simulador respetan esas reglas (hay una autoprueba
que lo verifica).

## 10. Que NO esta simulado

- **El firmware de la placa**: el simulador responde el mismo protocolo Modbus (y los
  registros reales del SDK, extraidos del propio jar), pero no reproduce tiempos ni
  fallas electricas reales. No reemplaza la validacion en la maquina.
- **El SDK ZhongDa como binario**: en modo simulacion el Calibrador y la recuperacion
  de plataforma hacen exactamente las mismas operaciones (mismas tramas, mismos
  registros) pero desde codigo propio, porque las librerias nativas del SDK son solo
  ARM. En la tablet se sigue usando el SDK real.
- **El backend y los pagos**: en modo simulacion la app apunta al backend simulado
  (`10.0.2.2:9200`); no se toca el servidor de produccion ni ninguna pasarela real.
  El QR se genera localmente y el pago se confirma solo.
- **Los videos promocionales**: si faltan en `res/raw` (no estan en git), el build
  falla; ver la tabla de problemas comunes.

## 11. Problemas comunes

| Sintoma | Causa / solucion |
|---|---|
| `UnsatisfiedLinkError: ronyuanserial_port` | Se compilo sin `-PvendingSerial=tcp` o se esta abriendo el Calibrador |
| El build falla con `Unresolved reference: R.raw.<video>` | Faltan los videos promocionales en `app/src/main/res/raw` (no estan en el repo: `.gitignore` ignora `*.mp4`). Copiar los videos reales con esos nombres |
| El tester dice `Puerto abierto` pero no llega ningun RX | El simulador no esta corriendo, o el endpoint no coincide (`-PvendingSimEndpoint`) |
| `Connection refused` en el log | El simulador no escucha en el puerto 9100 (o Windows Firewall lo bloqueo) |
| El ciclo queda colgado en `Puerta blanca: ABRIENDO` | Se cambio el escenario a mitad de ciclo: tocar STOP y reiniciar |
| `adb` no reconoce el emulador | Falta `platform-tools` en el PATH |
