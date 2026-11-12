# Canal de avisos backend -> maquina (MQTT)

Estado: **diseno cerrado, implementacion pendiente**. El primer paso es el spike (broker local +
APK minima), no la telemetria.

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

0. Contrato y broker de desarrollo (este documento y `tools/mqtt-broker/`).
1. **Spike**: mosquitto en la PC + APK minima que conecta, publica un latido y recibe un comando en
   el emulador. Sin colector, sin cola, sin integracion con las pantallas. Responde la unica pregunta
   abierta: si el servicio en primer plano aguanta el kiosko en Android 7.
2. Broker en el VPS + DNS + firewall.
3. Backend: credenciales + publicar al activar y desactivar.
4. APK definitiva: servicio, dedupe y las dos pantallas.
5. Punta a punta con una maquina real, dejando el sondeo de 30 minutos como red.

## Pendientes de decision

- Acceso al VPS para instalar mosquitto y abrir el 8883 (por la red de la empresa no hay SSH).
- Registro DNS `mqtt.boxipago.com.bo`.
