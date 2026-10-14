# Backend simulado de vending

Responde los **mismos endpoints** del backend real con datos de prueba, para poder
probar en la PC todo el flujo Kiosk (login, maquinas, catalogo, carrito, pago QR y
dispensacion) sin tocar el servidor de produccion y **sin ninguna pasarela de pago
real**: el QR se genera localmente y el pago se confirma solo a los pocos sondeos.

- `mock_backend.py` — el servicio (Python 3, sin dependencias).
- `start-mock.bat` — lanzador para Windows.

## Uso rapido

```bash
python mock_backend.py                 # escucha en http://127.0.0.1:9200
python mock_backend.py --payment fail  # el pago siempre falla
python mock_backend.py --polls 5       # tarda 5 sondeos en confirmar
```

Datos de prueba: maquina `MAQ-SIM-01`, PIN de maquina `1234`, PIN admin `9999`,
15 productos con stock 8. El login de operador acepta cualquier correo/contrasena.

## Control en caliente

```bash
curl "http://127.0.0.1:9200/__status"                    # estado y pedidos
curl "http://127.0.0.1:9200/__control?payment=fail"       # paid | pending | fail | cancel
curl "http://127.0.0.1:9200/__control?polls=5"
```

## Endpoints simulados

| Metodo | Ruta |
|---|---|
| POST | `/api/login` |
| GET | `/api/maquinas` |
| POST | `/api/maquinas/login` |
| POST | `/api/maquinas/acceso` |
| GET | `/api/maquinas/{id}/planograma` |
| GET | `/api/maquina/pago/qr/servicios-habilitados` |
| POST | `/api/pedido/crear-y-generar-qr` |
| GET | `/api/pedido/{id}/estado-pago` |
| POST | `/api/pedido/{id}/cancelar` |
| POST | `/api/maquina/pedido/dispensacion` |
| GET | `/img/*.png` (imagenes de productos, carrusel y fondo) |

La APK del emulador lo alcanza en `http://10.0.2.2:9200`; eso se configura solo al
compilar en modo simulacion (ver `docs/SIMULADOR_PC.md`).
