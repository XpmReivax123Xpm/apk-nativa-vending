# Canal de avisos backend -> maquina (MQTT)

Estado: **el backend y el broker ya estan en produccion** (`mqtt.boxipago.com.bo:8883`, TLS con
certificado propio). Lo unico que falta del lado de la APK es que viaje con el `ca.pem` del servidor:
es el ancla con la que valida ese certificado.

## Que resuelve y que no

Hoy una maquina desactivada averigua si la reactivaron con un login cada 30 minutos. Con MQTT el
backend le avisa en el acto: la maquina mantiene una conexion abierta con el broker y el backend
publica en el tema de esa maquina. Deja de haber sondeo para el caso feliz.

**El aviso es un aviso, no la verdad.** El mensaje dice «cambia tu estado»; la maquina lo averigua
por HTTP con su token, que es lo unico autoritativo. Asi el mensaje no envejece, no duplica la
autorizacion y un aviso perdido se corrige solo. El sondeo de 30 minutos se queda como red de
seguridad para siempre.

## Las tres piezas

| Pieza | Que es | Donde |
|---|---|---|
| Broker | el cartero: recibe una conexion por maquina y entrega los mensajes | VPS, mosquitto 2.x |
| Backend | publica el aviso cuando se activa o desactiva, y entrega las credenciales del broker | este repo Laravel |
| APK | mantiene la conexion y reacciona al aviso | `apk-nativa-vending` |

## Temas

| Tema | Direccion | QoS | Retenido |
|---|---|---|---|
| `maq/<id>/comando` | backend -> maquina | 1 | no |
| `maq/<id>/estado` | maquina -> broker | 1 | si (con LWT) |
| `maq/<id>/telemetria` | maquina -> broker | 0 | no (fase 2) |
| `maq/<id>/evento` | maquina -> broker | 1 | no (fase 2) |

`<id>` es **`MAQUINA.Maquina`, el id numerico**, no `CodigoMaquina`: el codigo es unico solo dentro
de la empresa (`StoreMaquinaRequest` lo valida con `whereHas('empresas')`), asi que dos empresas
pueden tener «MAQ-01» y sus temas chocarian.

El usuario del broker y el clientId son ese mismo numero, con `use_username_as_clientid true`: una
maquina no puede aparecer como otra ni suscribirse al tema de otra.

Un comando **no** se retiene: retenerlo lo reentrega en cada reconexion, y «reiniciar» repetido a la
semana es un problema. Lo que se publica mientras la maquina esta sin conexion se encola solo si la
sesion es persistente (`cleanSession=false`), y eso lo hace la APK.

## Payloads

Comando (backend -> maquina):

```json
{"CodigoComando": "9f1c2a7e-...", "Tipo": "estado", "EmitidoEn": "2026-11-12T15:41:00-04:00"}
```

- `Tipo = "estado"`: «revisa tu estado ahora». No dice si esta activa ni desactivada — el payload no
  lleva estado, justamente para que el unico dato bueno sea el del HTTP.
- Fase 2: `recargar_planograma`, `reiniciar`.
- `CodigoComando` existe para descartar repetidos: un QoS 1 reentregado no puede reiniciar la tablet
  en medio de una venta.

Presencia (maquina -> broker, retenida) y testamento:

```json
{"Estado": "EN_LINEA", "VersionApk": "1.4.0", "Fecha": "2026-11-12T15:41:00-04:00"}
{"Estado": "SIN_LINEA"}
```

## Credenciales del broker

`POST /api/v1/maquina-auth/broker` (middleware `auth:maquina`):

```json
{"data": {"host": "mqtt.boxipago.com.bo", "puerto": 8883, "tls": true,
          "usuario": "22", "clave": "<secreto>"}}
```

- Cada llamada **genera una clave nueva** y reemplaza la anterior en el archivo del broker: la
  maquina la pide al arrancar si no la tiene, y una vez mas si el broker la rechaza.
- La clave no se guarda en claro en MySQL: el backend la escribe con `mosquitto_passwd -b` en el
  archivo de contrasenas del broker (backend y broker viven en la misma maquina).
- **El token de Sanctum no sirve de password del broker**: `expires_in` va en minutos.

## Broker en el VPS

- mosquitto 2.x con `listener 8883` + TLS, `allow_anonymous false`, `password_file`, `acl_file`,
  persistencia, `max_queued_messages` alto.
- **El certificado ya existe**: el actual de `backend.boxipago.com.bo` trae
  `DNS:*.boxipago.com.bo` y vence el 11/05/2027, asi que cubre `mqtt.boxipago.com.bo`. Lo que falta
  es el **registro DNS**, que hoy no existe (NXDOMAIN).
- El puerto 8883 tiene que quedar abierto en el firewall / security group. Si la politica solo
  publica 80 y 443, plan B: `nginx` con `stream` + `ssl_preread` repartiendo por SNI en el 443
  (`mqtt.` al broker y el resto a la API), sin abrir un puerto nuevo.
- `backend.boxipago.com.bo` resuelve a **dos** IPs: el broker es un endpoint aparte, un solo host.
  No va detras del balanceador HTTP.

## Backend (Laravel)

- `composer require php-mqtt/client` (cliente puro PHP, sin extensiones).
- `config/mqtt.php` + claves `MQTT_*` del `.env`.
- `app/Domains/Vending/Support/MqttPublisher.php`: publica QoS 1 y **no rompe nada si el broker esta
  caido** (se loguea y sigue: una activacion no puede fallar porque el cartero no atienda).
- `DeactivateMaquinaAction` / `ActivateMaquinaAction`: publican **despues del commit**
  (`DB::afterCommit`). Publicar antes deja a la maquina consultando el estado viejo y el aviso se
  pierde.
- `app/Domains/Security/Actions/GenerarCredencialBrokerAction.php` + ruta + metodo del controlador.
- `app/Console/Commands/EscucharPresenciaMqttCommand.php`: se suscribe a `maq/+/estado` y escribe
  `MAQUINA.EstadoConexion` (la columna existe y hoy se llena a mano desde el ERP; con esto pasa a ser
  telemetria real).
- Tests: credenciales (401 sin token, 200 con el, clave rotada) y publicacion al activar/desactivar
  con el publisher mockeado.

## APK

- `implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")`. El
  `paho.android.service` esta abandonado y no arranca en Android 12+.
- **TLS**: el broker usa un certificado firmado por una CA propia que el almacen de Android no conoce.
  `BrokerTls` arma el `SSLSocketFactory` con las anclas de `app/src/main/assets/broker/mqtt_ca.pem`
  —el MISMO `ca.pem` del servidor, sin regenerarlo— y con las del sistema en paralelo, asi que un
  futuro certificado publico sigue andando sin recompilar. Sin ese archivo el saludo falla con
  «Trust anchor for certification path not found» y no llega ningun aviso.
- `AndroidManifest.xml`: `<service android:name=".mqtt.MaquinaMqttService">` +
  `FOREGROUND_SERVICE` (+ `foregroundServiceType="dataSync"` para targetSdk 34; en Android 7 el
  atributo se ignora). Ojo: hoy el manifest no tiene **ni un** service.
- Servicio en primer plano, `START_STICKY`, keepalive 60 s, `cleanSession=false`, clientId = id, LWT
  retenido en `maq/<id>/estado`. **Nada de MQTT en el hilo de UI**: el watchdog de hardware reinicia
  la tablet a los ~200 s sin alimento, asi que un cuelgue de la UI es un reinicio en cascada.
- Al recibir un comando: dedupe por `CodigoComando`, y `estado` corre **el mismo chequeo que hoy hace
  el sondeo** (`MachineAuthGateway.refreshSessionWithStoredMachineCredentials`). Con 403 se abre la
  pantalla roja o se corta la espera de la que ya esta abierta; con exito no se hace nada.

## Orden de trabajo

| Paso | Estado |
|---|---|
| 0. Contrato y broker de desarrollo (`docs/MQTT.md` + `tools/mqtt-broker/`) | hecho |
| 1. **Spike**: correr el ciclo en la PC (broker local + emulador) | pendiente |
| 2. Broker en el VPS + DNS + firewall | hecho |
| 3. Backend: `POST /maquina-auth/broker`, publicador y `afterCommit` en activar/desactivar | hecho (desplegado) |
| 4. APK: servicio, credenciales, dedupe y las dos pantallas | hecho (compila, sin probar en equipo) |
| 5. Punta a punta con una maquina real | pendiente |

Lo implementado, por archivo:

- Backend: `config/mqtt.php`, `app/Domains/Vending/Support/MqttPublisher.php`,
  `app/Domains/Security/Actions/GenerarCredencialBrokerAction.php`, el metodo `broker` de
  `MaquinaAuthController`, las dos actions de activar/desactivar y los tests
  `MaquinaBrokerCredencialesTest` / `MaquinaAvisoMqttTest`.
- APK: `app/.../mqtt/MqttCredentials.kt` (credenciales + almacen), `MqttCredentialsGateway.kt` (HTTP),
  `MqttCommandBus.kt` (aviso dentro del proceso) y `MaquinaMqttService.kt` (servicio en primer plano);
  el canal se enciende desde `KioskCatalogActivity` y `MachineOutOfServiceActivity`.
- La regla de no cortar una venta en curso vive en `KioskCatalogActivity`: el aviso con el pago abierto
  o el producto saliendo se guarda (`avisoEstadoPendiente`) y se aplica al cerrar la venta.
- Lo que NO se hizo: la presencia (`maq/<id>/estado` se publica, pero del lado del backend todavia no
  hay quien la lea para llenar `MAQUINA.EstadoConexion`) y la telemetria. Eso es fase 2.

## Construir sin tocar produccion

Todo el canal se escribe y se prueba sin tocar el servidor, porque las tres piezas nacen APAGADAS y se
degradan solas:

| Pieza | Que se hace | Donde se prueba | Si el broker no existe |
|---|---|---|---|
| APK | Paho, servicio en primer plano, LWT, dedupe, las dos pantallas | emulador + broker local | el servicio no arranca y sigue el sondeo de 30 min |
| Backend | endpoint de credenciales, publicador, `afterCommit` en las dos actions, comando de presencia | Laravel local + APK del emulador | `MQTT_HOST` vacio: el publicador no hace nada, ni error |
| Broker | los archivos de configuracion (TLS, ACL, contrasenas) | PC, con certificado autofirmado | es un proceso aparte con su puerto: no toca nginx, PHP ni MySQL |

Con eso el ciclo completo — desactivar en el ERP, el aviso, la pantalla roja, reactivar y volver — se
corre entero en la PC. Y como el backend y la APK se pueden desplegar con el canal apagado, ninguna de
las dos cosas cambia el comportamiento actual de las maquinas en la calle.

## Puesta en marcha minima (el dia que se prende)

1. Instalar mosquitto en el servidor: paquete + un archivo de config + unidad de systemd. No se toca
   nginx, ni PHP, ni MySQL, ni la base.
2. TLS: **el certificado actual es de Amazon (ALB/ACM) y no se puede copiar al broker** — la clave
   privada no esta en la instancia. Dos caminos:
   - NLB en AWS con el mismo wildcard de ACM: el broker escucha en 1883 en la instancia y el NLB hace
     TLS en el 8883. Cero archivos de certificado en el servidor; el NLB tiene costo mensual.
   - Certificado propio (Let's Encrypt) en la instancia, con `mqtt.` apuntando a la IP de la instancia.
     Gratis, pero necesita IP publica y abrir 80 y 8883.
3. DNS: crear `mqtt.boxipago.com.bo`.
4. `.env` del backend: las claves `MQTT_*`. Es el unico cambio de configuracion de la aplicacion y se
   revierte vaciando `MQTT_HOST`.
5. (Opcional) el comando de presencia en supervisor, igual que el worker de colas.

El encendido puede ser **por maquina**: las credenciales son por maquina, asi que se prende con una
sola (la de prueba) y el resto sigue exactamente como hoy. Apagarlo es borrar la clave del `.env` y
reiniciar el worker; las maquinas vuelven al sondeo sin reinstalar nada.

## Pendientes de decision

- Camino de TLS: NLB con el certificado de ACM, o Let's Encrypt en la instancia.
- Acceso al servidor para instalar mosquitto y abrir el 8883 (por la red de la empresa no hay SSH).
- Registro DNS `mqtt.boxipago.com.bo`.
