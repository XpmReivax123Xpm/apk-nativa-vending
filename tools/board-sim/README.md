# Simulador de placa de vending

Servicio que hace de **controladora de la maquina expendedora** cuando la APK
`apk-nativa-vending` corre en el emulador de la PC: recibe las mismas tramas
Modbus RTU que la placa real y devuelve la misma secuencia de estados de IO
durante la dispensacion y el retiro del producto.

- `board_sim.py` — el servicio (Python 3, sin dependencias externas).
- `start-sim.bat` — lanzador para Windows.

## Uso rapido

```bash
python board_sim.py                      # escenario normal
python board_sim.py --scenario stuck     # plataforma atorada
python board_sim.py --selftest           # autoprueba del protocolo (no necesita la APK)
```

Del lado de la APK:

```bash
gradlew :app:installDebug -PvendingSerial=tcp
```

La guia completa (AVD, emulador, escenarios, tabla de codigos) esta en
`docs/SIMULADOR_PC.md`.
