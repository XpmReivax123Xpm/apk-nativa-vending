# Backend simulado de vending

Responde el **mismo contrato del backend real** —todo bajo `/api/v1`, el sobre `{"data": ...}` y el token
de maquina en `Authorization`— con datos de prueba, para poder probar en la PC todo el flujo del kiosko
(acceso, planograma, carrito, pago QR y dispensacion) sin tocar el servidor de produccion y **sin
ninguna pasarela de pago real**: el QR se genera localmente y el pago se confirma solo a los pocos sondeos.

- `mock_backend.py` — el servicio (Python 3, sin dependencias).
- `start-mock.bat` — lanzador para Windows.

## Uso rapido

```bash
python mock_backend.py                 # escucha en http://127.0.0.1:9200
python mock_backend.py --payment fail  # el pago siempre falla
python mock_backend.py --polls 5       # tarda 5 sondeos en confirmar
```

Datos de prueba: **identificador de equipo `02:SIM:00:00:00:01`** (es el que se teclea una vez en la
pantalla de acceso del kiosko) y **PIN `1234`**; maquina `MAQ-SIM-01`; 15 productos con stock 8 en las
celdas `A1`..`B6` (salidas fisicas 10..18 y 20..25); dos metodos de pago.

## Control en caliente

```bash
curl "http://127.0.0.1:9200/__status"                    # estado, pedidos, claves y stock por celda
curl "http://127.0.0.1:9200/__control?payment=fail"       # paid | pending | fail | cancel
curl "http://127.0.0.1:9200/__control?polls=5"
```

## Endpoints simulados

| Metodo | Ruta | Notas |
|---|---|---|
| POST | `/api/v1/maquina-auth/login` | `{tcDireccionMac, tcPin}`; el token va al ras, como el backend |
| GET | `/api/v1/maquina/planograma` | `{data: {celdas, fondo}}` (token de maquina) |
| GET | `/api/v1/maquina/metodos-pago` | `{data: [{tnMetodoPago, tcNombreMetodoPago}]}` |
| POST | `/api/v1/pedidos` | `{Detalles[], tcNombreCliente?, ClaveIdempotencia?}` |
| POST | `/api/v1/pedidos/{id}/cancelar` | `{tcMotivo}`; un pedido pagado responde 422 |
| POST | `/api/v1/pedidos/{id}/dispensacion` | `{PedidoDetalle, DispensacionEstado, MaquinaCelda?, CodigoEvento?}` |
| POST | `/api/v1/pagos` | `{Pedido, MetodoPago}` -> `tnPago`, `tcQrBase64`, `ttExpira` |
| GET | `/api/v1/pagos/{id}` | el sondeo del cobro: `tnEstadoPago` 1/2/3/4 |
| GET | `/img/*.png` | fotos de producto, carrusel y fondo |

Los endpoints de maquina exigen el token (401 sin el), como el backend. La **clave de idempotencia**
funciona igual: el mismo alta repetida devuelve el pedido que ya existe (200 en vez de 201) y no vuelve a
reservar stock.

La APK del emulador lo alcanza en `http://10.0.2.2:9200`; eso se configura solo al compilar en modo
simulacion (ver `docs/SIMULADOR_PC.md`). Con un build que NO sea de simulacion hay que apuntarla a mano:
`-PvendingBackendUrl=http://10.0.2.2:9200` (o `vendingBackendUrl` en `local.properties`).
