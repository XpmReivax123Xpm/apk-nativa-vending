# Broker MQTT de desarrollo

Broker local para el spike y para probar la APK en el emulador. El de produccion vive en el VPS con
TLS; el contrato (temas, payloads, credenciales) esta en `docs/MQTT.md`.

## 1. Instalar mosquitto (una vez)

Instalador oficial de Windows desde https://mosquitto.org/download/ (win64). Deja los ejecutables en
`C:\Program Files\mosquitto`.

Ojo: el instalador registra el servicio `mosquitto` y lo deja escuchando en el 1883. Para ver el log
en vivo hay que pararlo antes (`net stop mosquitto`); `net start mosquitto` lo devuelve al de fabrica.

## 2. Crear credenciales

```bash
cd tools/mqtt-broker
bash crear-credencial.sh backend     # el usuario con el que publica el Laravel
bash crear-credencial.sh 22          # la maquina 22 (SIM-01 en el backend mock)
```

`passwd`, `acl` y `mosquitto.conf` son del entorno local: no van a produccion.

## 3. Arrancar

```bash
cd tools/mqtt-broker
"C:/Program Files/mosquitto/mosquitto.exe" -c mosquitto.conf -v
```

Desde el emulador el host es `10.0.2.2:1883`.

## 4. Probar sin la APK

```bash
# presencia de la maquina 22
"C:/Program Files/mosquitto/mosquitto_sub.exe" -h 127.0.0.1 -u backend -P <clave-backend> \
  -t 'maq/22/estado' -v

# un comando para la maquina 22
"C:/Program Files/mosquitto/mosquitto_pub.exe" -h 127.0.0.1 -u backend -P <clave-backend> \
  -t 'maq/22/comando' -q 1 \
  -m '{"CodigoComando":"prueba-1","Tipo":"estado","EmitidoEn":"2026-11-12T15:41:00-04:00"}'
```

Con el testamento (LWT) la maquina aparece `SIN_LINEA` cuando se cae; para verla `EN_LINEA` la APK
tiene que publicar su estado retenido al conectar.
