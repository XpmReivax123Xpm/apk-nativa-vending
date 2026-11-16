#!/usr/bin/env bash
# Crea o reemplaza la credencial de un usuario en el broker de DESARROLLO.
#
#   bash crear-credencial.sh 22          # maquina 22 (SIM-01 en el backend mock)
#   bash crear-credencial.sh backend     # el usuario con el que publica el Laravel
#
# La clave se genera sola si no se pasa, y se imprime UNA vez: no queda guardada en claro.
set -euo pipefail

MOSQUITTO_BIN="${MOSQUITTO_BIN:-C:/Program Files/mosquitto}"
PASSWD="${PASSWD:-./passwd}"

usuario="${1:?falta el usuario: el id de MAQUINA, o 'backend'}"
clave="${2:-$(openssl rand -base64 24 | tr -dc 'A-Za-z0-9' | cut -c1-24)}"

# -c solo cuando el archivo no existe: con -c en un archivo existente se borran los demas usuarios.
crear=""
if [ ! -f "$PASSWD" ]; then
  crear="-c"
fi

"$MOSQUITTO_BIN/mosquitto_passwd.exe" $crear -b "$PASSWD" "$usuario" "$clave"

echo "usuario: $usuario"
echo "clave:   $clave"
