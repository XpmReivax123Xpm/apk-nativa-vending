# Integración con el backend (BoxiPago Laravel)

La APK del kiosko opera contra la API de Laravel, versionada bajo **`/api/v1`**. Este documento es el
contrato que la APK usa hoy y lo que queda pendiente del lado de la aplicación.

## A dónde apunta

`BackendConfig.baseUrl` sale de `BuildConfig.BACKEND_BASE_URL`, y todo endpoint cuelga de
`BackendConfig.apiUrl` (= `baseUrl` + `/api/v1`).

El backend ya está **desplegado** en `https://backend.boxipago.com.bo` (VPS público, con certificado
válido): es el destino de la `release` y el default del `debug`. El backend viejo
(`boxipagobackend.pagofacil.com.bo`) no se usa desde esta APK porque atiende otro flujo que está en
producción, y el Laravel local es solo para desarrollo.

| Build | A dónde apunta |
| --- | --- |
| `release` | `vendingDeployBackend` = `https://backend.boxipago.com.bo` |
| `debug` (sin propiedades) | `vendingDevelopmentBackend` = `https://backend.boxipago.com.bo` |
| `debug` con `vendingSerial=tcp` | el mock local (`http://10.0.2.2:9200`), con el mismo contrato que el backend |
| `debug` con `-PvendingBackendUrl=...` o `vendingBackendUrl` en `local.properties` | ese host: es lo que se usa para probar con el Laravel local o con la LAN |

`10.0.2.2` es como el emulador ve a la PC. Para una tablet de la LAN se pasa la IP de la PC (el
`local.properties` de esta máquina ya la tiene). El Laravel tiene que escuchar en la red:

```bash
php artisan serve --host=0.0.0.0 --port=8000
```

y para que **las fotos** se vean en el equipo, `ARCHIVOS_URL_BASE` del `.env` tiene que apuntar al
mismo host que la APK (`ARCHIVOS_URL_BASE=http://<ip-de-la-pc>:8000/storage`); con el dominio
`*.test` de Laragon la tablet no resuelve el nombre.

El tráfico es HTTP en claro hacia la LAN y el manifest ya lo permite (`usesCleartextTraffic="true"`).

## Cómo se autentica el kiosko

La máquina se identifica por el **identificador del APARATO** donde corre el kiosko (la MAC de la
tablet, o el serial si el kiosko va como *device owner*) y el **PIN** que teclea el operador:

```
POST /api/v1/maquina-auth/login   { "tcDireccionMac": "...", "tcPin": "1234" }
  -> { message, access_token, token_type, expires_in, refresh_token,
       Maquina: { tnMaquina, tcCodigoMaquina, tcEstadoOperativo, tcEmpresa, tcUbicacion } }
```

- El **CÓDIGO de la máquina NO autentica**: está impreso en el frente del equipo y es público. Si
  autenticara, quien se lo supiera entraría al planograma de otra máquina y cobraría contra ella.
- El **identificador del equipo se carga UNA sola vez** en la pantalla de acceso y queda guardado
  (`AuthSessionManager.getEquipo()`); después el kiosko solo pide el PIN. El botón «Cambiar equipo»
  de esa misma pantalla lo vuelve a pedir (cambio de tablet).
- `expires_in` son los MINUTOS de vigencia del token de acceso. La APK **no usa el refresh token**:
  cuando el suyo vence se vuelve a autenticar con el mismo identificador y PIN (es lo que hace el
  arranque automático y la recuperación de sesión vencida).
- El token viaja en `Authorization: Bearer <access_token>` y **la máquina nunca va en la URL**: la
  dice el token, así que una máquina no puede leer el planograma de otra ni cobrar contra ella.

## Los endpoints que usa

| Para qué | Llamada |
| --- | --- |
| Entrar | `POST /maquina-auth/login` |
| Planograma + fondo | `GET /maquina/planograma` |
| Métodos de pago | `GET /maquina/metodos-pago` |
| Alta del pedido | `POST /pedidos` |
| Iniciar el cobro (QR) | `POST /pagos` |
| Estado del cobro (sondeo cada 5 s) | `GET /pagos/{pago}` |
| Cancelar el pedido | `POST /pedidos/{pedido}/cancelar` (campo `tcMotivo`) |
| Reportar la dispensación de una unidad | `POST /pedidos/{pedido}/dispensacion` |
| Subir la bitácora de esa unidad (gzip) | `POST /pedidos/{pedido}/dispensacion/{pedidoDetalle}/bitacora` |
| Validar el PIN de salida del kiosko | `POST /maquina-auth/login` (es el mismo PIN; el token se descarta) |

**Sobre de respuesta:** los recursos vienen envueltos en `{ "data": ... }` y los errores son
`{ "message": ..., "errors": { campo: [motivos] } }` con su status (401 = sesión vencida, 422 =
rechazo de negocio). El login NO va envuelto: sus llaves van en la raíz.

### Planograma (`GET /maquina/planograma`)

```json
{"data": {
  "celdas": [
    {"tnMaquinaCelda": 1, "tcCodigoCelda": "A1", "tlEstado": true, "tlOcupada": true,
     "tnCantidadActual": 5, "tnCantidadReservada": 0, "tnDisponible": 5,
     "producto": {"tnProducto": 7, "tcCodigoProducto": "PROD-1", "tcNombreProducto": "Coca-Cola",
                  "tnPrecioVenta": 10.0, "tlEstado": true,
                  "archivoPrincipal": {"tnArchivo": 3, "tcUrl": "https://.../x.webp"},
                  "archivoSecundarioPrincipal": null}}
  ],
  "fondo": {"tnArchivoUi": 4, "tnArchivo": 9, "tcUsoTipo": "FONDO_PLANOGRAMA",
            "archivo": {"tcUrl": "https://.../fondo.webp"}}
}}
```

- **`tcCodigoCelda` es obligatorio y es con el que la máquina acciona la celda**: la bandeja es la
  decena y la columna la unidad (`A1` → 10, `C7` → 27), así que también se acepta un número ya listo
  en el rango de la placa (10..68). Sin código, la unidad no se puede dispensar.
- **`tnDisponible` es lo que se puede VENDER** (lo cargado menos lo reservado por el otro sistema):
  es la misma cuenta con la que el backend acepta o rechaza el pedido, así que el kiosko no ofrece
  algo que al cobrar se rechace.
- `fondo` es la imagen que el kiosko pinta detrás de la rejilla, o `null` (la máquina tiene a lo sumo
  un fondo, y la biblioteca de fondos es compartida por la empresa).

### Compra: dos llamadas

```json
POST /api/v1/pedidos
{"Detalles": [{"MaquinaCelda": 1, "Producto": 7, "Cantidad": 2}],
 "tcNombreCliente": "Ana", "tcTelefonoCliente": "70000000", "tcNITCliente": "8765432"}
  -> {"data": {"tnPedido": 15, "taDetalles": [{"tnPedidoDetalle": 30, "tnPlanogramaCelda": 1}, ...]}}

POST /api/v1/pagos
{"Pedido": 15, "MetodoPago": 1}
  -> {"data": {"tnPago": 22, "tcQrBase64": "...", "ttExpira": "2026-10-14T10:00:00-04:00", "tnEstadoPago": 1}}
```

- El precio lo pone el backend (no viaja en el pedido) y cada unidad es **una línea** del pedido.
- Los tres datos del comprador son OPCIONALES: el backend busca al cliente que ya está (por documento
  y después por teléfono) o lo crea. Vender anónimo es un caso normal.
- El estado del cobro se sondea con el **id del PAGO** (`tnPago`), no con el del pedido. El catálogo
  de estados es el del backend: `1 PENDIENTE`, `2 PAGADO`, `3 CANCELADO`, `4 FALLIDO`.
- Con la pasarela de pruebas (`falso`) el QR es un PNG de 1×1: sirve para probar el flujo, no para
  mostrar en pantalla.

### Dispensación

```json
POST /api/v1/pedidos/{pedido}/dispensacion
{"PedidoDetalle": 30, "DispensacionEstado": 4, "MaquinaCelda": 1, "CodigoEvento": "COMPLETADO"}
```

Los ids son los del catálogo del backend —`2 EN_PROCESO`, `3 RECOJO_PENDIENTE`, `4 COMPLETADO`,
`5 FALLIDO`, `7 ATORADO`— y el código del evento es texto libre que queda como rastro de qué pasó en
esa unidad. El kiosko **encola el reporte en el equipo antes de mandarlo** (`DispenseStatusUploader`,
carpeta `dispensacion_pendientes`) y reintenta lo que no salió al cerrar cada unidad, al cargar el
catálogo y con espera creciente: la venta y el inventario dependen de este reporte, así que un corte de
red no puede dejarlo en el aire. Reenviarlo es seguro porque el backend es idempotente por el estado
actual de la dispensación; un rechazo definitivo (la línea no es de ese pedido, la celda no coincide) no
se reintenta y queda guardado en `dispensacion_rechazados` para diagnosticar.

### Bitácora de la dispensación (el texto de la unidad)

```http
POST /api/v1/pedidos/{pedido}/dispensacion/{pedidoDetalle}/bitacora
Content-Type: application/gzip
X-Version-Apk: 0.1.0-bootstrap

<cuerpo: el .txt.gz del texto que el kiosko escribió durante esa unidad>
```

La bitácora es la de UNA unidad (la del pedido y su `pedidoDetalle`), no la de toda la compra: si un
carrito lleva tres productos y falla uno, lo que el ERP muestra es el texto de ese. Se sube APARTE del
reporte de estado —que no la espera— y en segundo plano: el kiosko la comprime en su carpeta de
pendientes y la sube con la próxima unidad o al arrancar, así que una red caída no se lleva justo la
bitácora del fallo. La versión de la APK viaja en `X-Version-Apk` (queda en la fila del backend).

Responde `201` la primera vez y `200` si esa unidad ya tenía bitácora (el reintento no la pisa), `404`
si el pedido no es de esta máquina, `422` si el cuerpo no es un gzip legible o pasa el tope, y `410`
si la retención ya la borró (7 días desde la dispensación). Las dispensaciones de PRUEBA no se suben:
no tienen pedido al que colgarlas.

## Pendientes y avisos

- **Cuando exista el VPS**: se cambian las DOS constantes de `app/build.gradle.kts`
  (`vendingDevelopmentBackend`, `vendingDeployBackend`) y el default de `BackendConfig`.
- **Leer la MAC del aparato**: hoy el identificador se carga a mano UNA vez (que es lo que se probó
  primero). Leerlo del equipo es trabajo del kiosko —Android no entrega la MAC del WiFi a una app
  común desde Android 6—, y el candidato firme en un aparato administrado (*device owner*) es el
  serial del equipo.
- **El mock del simulador sirve el MISMO contrato** (`tools/backend-mock/mock_backend.py`, con sus
  endpoints bajo `/api/v1`, el sobre `{data}` y el token de máquina): un build de simulador
  (`vendingSerial=tcp`) no necesita el Laravel para recorrer el ciclo completo, y sus datos de prueba son
  equipo `02:SIM:00:00:00:01` + PIN `1234`.
- **Para probar hacen falta, del lado del backend**: la máquina con su `DireccionMac` cargada, un
  PIN definido (`PUT /maquina/{maquina}/pin`), un método de pago asignado
  (`PUT /maquina/{maquina}/metodos-pago`) y producto cargado en alguna celda.
- **Con la clave de idempotencia, el reintento no abre un segundo pedido**: la APK la manda en el alta
  (`ClaveIdempotencia = KIOSKO-<uuid>`, una por compra: se renueva cuando cambia el carrito o el método de
  pago y en cada entrada nueva a la pantalla de pago), así que un alta cuya respuesta se perdió devuelve
  el pedido que ya existe en vez de reservar el stock dos veces.
- **Segunda foto del producto**: el backend ya la expone
  (`producto.archivoSecundarioPrincipal`) y la pantalla hoy usa una sola imagen.
