#!/usr/bin/env python3
"""
Backend simulado para la APK de vending (modo simulacion).

Sirve el MISMO contrato que el Laravel real —todo bajo /api/v1, el sobre `{"data": ...}`, el token de
maquina en la cabecera `Authorization`— pero con datos de prueba, un QR generado localmente y un pago
que se confirma solo. No habla con ninguna pasarela real ni toca el servidor de produccion.

    python mock_backend.py                 # escucha en 127.0.0.1:9200
    curl "http://127.0.0.1:9200/__status"
    curl "http://127.0.0.1:9200/__control?payment=fail"

Modos de pago: paid (se confirma a los pocos sondeos), pending (nunca), fail (rechazado),
cancel (cancelado).

Endpoints (los mismos del backend, con los nombres de campo del backend):

    POST /api/v1/maquina-auth/login         {tcDireccionMac, tcPin}          -> token al ras
    GET  /api/v1/maquina/planograma         (token)                          -> {celdas, fondo}
    GET  /api/v1/maquina/metodos-pago       (token)
    POST /api/v1/pedidos                    {Detalles[], ClaveIdempotencia?} -> tnPedido + taDetalles
    POST /api/v1/pedidos/{id}/cancelar      {tcMotivo}
    POST /api/v1/pedidos/{id}/dispensacion  {PedidoDetalle, DispensacionEstado, ...}
    POST /api/v1/pagos                      {Pedido, MetodoPago}             -> tnPago + tcQrBase64
    GET  /api/v1/pagos/{id}                 (token)                          -> tnEstadoPago
    GET  /img/<nombre>.png                  fotos de producto, promo y fondo

La clave de idempotencia funciona como en el backend: el MISMO alta repetida devuelve el pedido que ya
existe (200 en vez de 201) y no vuelve a reservar stock.
"""

from __future__ import annotations

import argparse
import base64
import json
import struct
import sys
import threading
import time
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

API = "/api/v1"

# 15 productos: 9 celdas por bandeja, asi que ocupan las bandejas A y B (A1..A9, B1..B6).
PRODUCTS = [
    ("Agua 600 ml", 5.00), ("Coca-Cola 500 ml", 7.00), ("Jugo Del Valle 300 ml", 6.50),
    ("Papas Lays 45 g", 8.00), ("Chocolate Sublime", 6.00), ("Galleta Oreo", 5.50),
    ("Maní Salado 40 g", 4.50), ("Cerveza Paceña 330 ml", 12.00), ("Red Bull 250 ml", 15.00),
    ("Agua con gas 500 ml", 6.00), ("Bon o Bon", 2.50), ("Chicle Trident", 3.00),
    ("Salchipapa snack", 9.00), ("Yogur Pil 200 ml", 7.50), ("Doritos 40 g", 8.50),
]

MACHINE_ID = 1
MACHINE_CODE = "MAQ-SIM-01"
MACHINE_MAC = "02:SIM:00:00:00:01"      # lo que el operador teclea UNA vez en la pantalla de acceso
MACHINE_PIN = "1234"
MACHINE_LOCATION = "PC de pruebas (simulador)"

PAYMENT_METHODS = [(1, "QR de pruebas"), (2, "QR BCP")]

STOCK_PER_CELL = 8
QR_VALID_SECONDS = 300

# Catalogos del backend: ESTADOPAGO, ESTADOPEDIDO y DISPENSACIONESTADO.
PAGO_PENDIENTE, PAGO_PAGADO, PAGO_CANCELADO, PAGO_FALLIDO = 1, 2, 3, 4
PEDIDO_PENDIENTE, PEDIDO_PAGADO, PEDIDO_CANCELADO, PEDIDO_ENTREGADO = 1, 2, 3, 7
DISPENSACION_EN_PROCESO, DISPENSACION_RECOGIDA_PENDIENTE, DISPENSACION_COMPLETADO = 2, 3, 4
DISPENSACION_FALLIDO, DISPENSACION_ATORADO = 5, 7

ETIQUETA_PAGO = {
    PAGO_PENDIENTE: "PENDIENTE",
    PAGO_PAGADO: "PAGADO",
    PAGO_CANCELADO: "CANCELADO",
    PAGO_FALLIDO: "FALLIDO",
}
ETIQUETA_PEDIDO = {
    PEDIDO_PENDIENTE: "PENDIENTE",
    PEDIDO_PAGADO: "PAGADO",
    PEDIDO_CANCELADO: "CANCELADO",
    PEDIDO_ENTREGADO: "ENTREGADO",
}
ETIQUETA_DISPENSACION = {
    DISPENSACION_EN_PROCESO: "EN_PROCESO",
    DISPENSACION_RECOGIDA_PENDIENTE: "RECOGIDA_PENDIENTE",
    DISPENSACION_COMPLETADO: "COMPLETADO",
    DISPENSACION_FALLIDO: "FALLIDO",
    DISPENSACION_ATORADO: "ATORADO",
}


# ---------------------------------------------------------------------------
# PNG minimo (sin dependencias): cuadro solido y patron tipo QR
# ---------------------------------------------------------------------------

def png_from_rows(rows: list[bytes], width: int, height: int) -> bytes:
    """rows: una lista de filas de bytes RGB ya serializadas."""
    raw = b"".join(b"\x00" + row for row in rows)
    def chunk(tag: bytes, data: bytes) -> bytes:
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))
    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)  # 8 bits, color RGB
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header)
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def png_solid(width: int, height: int, rgb: tuple[int, int, int]) -> bytes:
    row = bytes(rgb) * width
    return png_from_rows([row] * height, width, height)


def png_product(index: int, size: int = 240) -> bytes:
    """Cuadro de color con una franja clara: alcanza para ver que la foto llego y que cambia por celda."""
    r = 60 + (index * 37) % 160
    g = 60 + (index * 71) % 160
    b = 60 + (index * 113) % 160
    rows = []
    for y in range(size):
        if size // 3 <= y < 2 * size // 3:
            rows.append(bytes((245, 245, 235)) * size)
        else:
            rows.append(bytes((r, g, b)) * size)
    return png_from_rows(rows, size, size)


def png_qr_like(seed: int, modules: int = 25, scale: int = 8) -> bytes:
    """Patron determinista tipo QR (no codifica nada, es solo para la pantalla)."""
    state = seed * 2654435761 % 4294967296
    def next_bit() -> int:
        nonlocal state
        state = (1103515245 * state + 12345) % (2 ** 31)
        return (state >> 16) & 1

    grid = [[1] * modules for _ in range(modules)]
    for y in range(modules):
        for x in range(modules):
            inside = 2 <= x < modules - 2 and 2 <= y < modules - 2
            grid[y][x] = next_bit() if inside else 0
    # marcadores de posicion (esquinas) para que parezca un QR
    for ox, oy in ((1, 1), (modules - 8, 1), (1, modules - 8)):
        for y in range(7):
            for x in range(7):
                border = x in (0, 6) or y in (0, 6)
                center = 2 <= x <= 4 and 2 <= y <= 4
                grid[oy + y][ox + x] = 1 if (border or center) else 0

    rows = []
    for y in range(modules):
        row = bytearray()
        for x in range(modules):
            value = 0 if grid[y][x] else 255
            row += bytes((value, value, value)) * scale
        for _ in range(scale):
            rows.append(bytes(row))
    return png_from_rows(rows, modules * scale, modules * scale)


# ---------------------------------------------------------------------------
# Celdas del planograma
# ---------------------------------------------------------------------------

def cell_code(index: int) -> str:
    """`A1`, `A9`, `B1`...: la bandeja es la letra y la columna el numero (9 por bandeja)."""
    return f"{chr(ord('A') + index // 9)}{index % 9 + 1}"


def physical_cell(index: int) -> int:
    """El numero que acciona la placa (10..68): bandeja x10 + columna - 1, en el mismo orden."""
    return (index // 9 + 1) * 10 + (index % 9)


def build_cells() -> list[dict]:
    cells = []
    for index, (name, price) in enumerate(PRODUCTS):
        cells.append({
            "tnMaquinaCelda": 100 + index,
            "tcCodigoCelda": cell_code(index),
            "tlEstado": True,
            "tlOcupada": True,
            "tnCantidadActual": STOCK_PER_CELL,
            "tnCantidadReservada": 0,
            "producto": {
                "tnProducto": 200 + index,
                "tcCodigoProducto": f"sim-{index + 1:02d}",
                "tcNombreProducto": name,
                "tnPrecioVenta": price,
                "tlEstado": True,
                "archivoPrincipal": {
                    "tnArchivo": 300 + index,
                    "tcUrl": f"/img/producto{index}.png",
                },
                # La segunda foto del planograma existe en el contrato aunque el kiosko pinte la principal.
                "archivoSecundarioPrincipal": None,
            },
        })
    return cells


# ---------------------------------------------------------------------------
# Estado simulado
# ---------------------------------------------------------------------------

class State:
    def __init__(self):
        self.lock = threading.Lock()
        self.cells = build_cells()
        self.orders: dict[int, dict] = {}
        self.payments: dict[int, dict] = {}
        self.idempotency: dict[str, int] = {}
        self.tokens: set[str] = set()
        self.next_order = 1000
        self.next_payment = 5000
        self.next_token = 0
        self.payment_mode = "paid"
        self.polls_until_paid = 2
        self.requests = 0
        self.log_file = None

    def log(self, message: str) -> None:
        stamp = time.strftime("%H:%M:%S")
        line = f"{stamp} [MOCK] {message}"
        print(line, flush=True)
        if self.log_file:
            self.log_file.write(line + "\n")
            self.log_file.flush()

    # -- sesion ------------------------------------------------------------

    def new_token(self) -> str:
        with self.lock:
            self.next_token += 1
            token = f"sim-machine-token-{self.next_token}"
            self.tokens.add(token)
            return token

    def token_valido(self, header: str) -> bool:
        token = header.removeprefix("Bearer ").strip()
        return bool(token) and token in self.tokens

    # -- celdas ------------------------------------------------------------

    def cell_by_id(self, maquina_celda: int) -> dict | None:
        for cell in self.cells:
            if cell["tnMaquinaCelda"] == maquina_celda:
                return cell
        return None

    def disponible(self, cell: dict) -> int:
        return max(0, cell["tnCantidadActual"] - cell["tnCantidadReservada"])

    # -- pedidos -----------------------------------------------------------

    def crear_pedido(self, maquina_celda: int, producto: int, clave: str | None) -> tuple[dict, bool]:
        """Devuelve (pedido, es_nuevo). Con la misma clave devuelve el pedido que ya existe.

        Cada linea es UNA unidad, como en el backend: la maquina reporta la dispensacion de cada una.
        """
        with self.lock:
            if clave and clave in self.idempotency:
                return self.orders[self.idempotency[clave]], False

            self.next_order += 1
            pedido_id = self.next_order
            detalle_id = pedido_id * 100 + 1

            pedido = {
                "tnPedido": pedido_id,
                "tnEmpresa": 1,
                "tnMaquina": MACHINE_ID,
                "tcCodigoMaquina": MACHINE_CODE,
                "tnEstadoPedido": PEDIDO_PENDIENTE,
                "ttFechaCreacion": time.strftime("%Y-%m-%dT%H:%M:%S"),
                "taDetalles": [{
                    "tnPedidoDetalle": detalle_id,
                    "tnPlanogramaCelda": maquina_celda,
                    "tnProducto": producto,
                    "tnCantidad": 1,
                    "tnEstadoDispensacion": 1,
                }],
                "pago": None,
                "descontado": False,
            }
            self.orders[pedido_id] = pedido
            if clave:
                self.idempotency[clave] = pedido_id
            self.reservar_stock(pedido)
            return pedido, True

    def pedido(self, pedido_id: int) -> dict | None:
        return self.orders.get(pedido_id)

    # -- pagos -------------------------------------------------------------

    def crear_pago(self, pedido: dict, metodo: int) -> dict:
        with self.lock:
            self.next_payment += 1
            pago_id = self.next_payment
            monto = 0.0
            for detalle in pedido["taDetalles"]:
                cell = self.cell_by_id(detalle["tnPlanogramaCelda"]) or {}
                monto += (cell.get("producto") or {}).get("tnPrecioVenta", 0) or 0
            pago = {
                "tnPago": pago_id,
                "tnPedido": pedido["tnPedido"],
                "tnMontoTotal": round(monto, 2),
                "tnMetodoPago": metodo,
                "tnEstadoPago": PAGO_PENDIENTE,
                "tcQrBase64": base64.b64encode(png_qr_like(pago_id)).decode("ascii"),
                "ttExpira": time.strftime("%Y-%m-%dT%H:%M:%S", time.localtime(time.time() + QR_VALID_SECONDS)),
                "polls": 0,
            }
            self.payments[pago_id] = pago
            pedido["pago"] = pago_id
            pedido["tnEstadoPedido"] = PEDIDO_PENDIENTE
            return pago

    def poll_pago(self, pago_id: int) -> dict | None:
        """El estado del cobro: en `paid` se confirma recien despues de N sondeos."""
        with self.lock:
            pago = self.payments.get(pago_id)
            if pago is None:
                return None
            pago["polls"] += 1
            modo = self.payment_mode
            polls = pago["polls"]

        if modo == "paid":
            if polls > self.polls_until_paid:
                return self.marcar_pago(pago_id, PAGO_PAGADO)
            return pago
        if modo == "fail":
            return self.marcar_pago(pago_id, PAGO_FALLIDO)
        if modo == "cancel":
            return self.marcar_pago(pago_id, PAGO_CANCELADO)
        return pago

    def marcar_pago(self, pago_id: int, estado: int) -> dict:
        """El pago pasa a su estado final y el pedido lo sigue. Corre con el lock tomado."""
        with self.lock:
            pago = self.payments[pago_id]
            if pago["tnEstadoPago"] != PAGO_PENDIENTE:
                return pago

            pago["tnEstadoPago"] = estado
            pedido = self.orders[pago["tnPedido"]]
            if estado == PAGO_PAGADO:
                pedido["tnEstadoPedido"] = PEDIDO_PAGADO
                self.descontar_stock(pedido)
            elif estado == PAGO_CANCELADO:
                pedido["tnEstadoPedido"] = PEDIDO_CANCELADO
                self.liberar_stock(pedido)
            return pago

    # -- inventario --------------------------------------------------------
    # Las tres mueven contadores de las celdas y las llama el propio estado mientras tiene el lock
    # tomado: por eso NO lo vuelven a tomar —el lock no es reentrante y tomarlo de nuevo traba el hilo
    # de la peticion—.

    def reservar_stock(self, pedido: dict) -> None:
        for detalle in pedido["taDetalles"]:
            cell = self.cell_by_id(detalle["tnPlanogramaCelda"])
            if cell is not None:
                cell["tnCantidadReservada"] += 1

    def descontar_stock(self, pedido: dict) -> None:
        """La unidad salio: se libera su reserva y baja lo cargado (una sola vez por pedido)."""
        if pedido.get("descontado"):
            return
        pedido["descontado"] = True
        for detalle in pedido["taDetalles"]:
            cell = self.cell_by_id(detalle["tnPlanogramaCelda"])
            if cell is None:
                continue
            cell["tnCantidadReservada"] = max(0, cell["tnCantidadReservada"] - 1)
            cell["tnCantidadActual"] = max(0, cell["tnCantidadActual"] - 1)

    def liberar_stock(self, pedido: dict) -> None:
        for detalle in pedido["taDetalles"]:
            cell = self.cell_by_id(detalle["tnPlanogramaCelda"])
            if cell is not None:
                cell["tnCantidadReservada"] = max(0, cell["tnCantidadReservada"] - 1)


STATE = State()


# ---------------------------------------------------------------------------
# Sobre de las respuestas (igual que el backend)
# ---------------------------------------------------------------------------

def envoltura(data, status: int = 200) -> tuple[bytes, int]:
    return json.dumps({"data": data}, ensure_ascii=False).encode("utf-8"), status


def error(mensaje: str, status: int, errors: dict | None = None) -> tuple[bytes, int]:
    cuerpo = {"message": mensaje}
    if errors:
        cuerpo["errors"] = errors
    return json.dumps(cuerpo, ensure_ascii=False).encode("utf-8"), status


def sin_sesion() -> tuple[bytes, int]:
    return error("Unauthenticated.", 401)


# ---------------------------------------------------------------------------
# HTTP
# ---------------------------------------------------------------------------

class Handler(BaseHTTPRequestHandler):
    server_version = "VendingMock/2.0"

    def log_message(self, fmt: str, *args) -> None:  # silencia el log por defecto
        return

    # -- utilidades --------------------------------------------------------

    def body_json(self) -> dict:
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0:
            return {}
        raw = self.rfile.read(length).decode("utf-8", "replace")
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            return {"_raw": raw}

    def send_json(self, payload: bytes, status: int = 200) -> None:
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def enviar(self, respuesta: tuple[bytes, int]) -> None:
        self.send_json(respuesta[0], respuesta[1])

    def base_url(self) -> str:
        """La URL con la que el equipo del kiosko alcanza a este mock (el Host de la peticion)."""
        host = self.headers.get("Host") or f"127.0.0.1:{self.server.server_port}"
        return f"http://{host}"

    def url_imagen(self, ruta: str) -> str:
        return f"{self.base_url()}{ruta}"

    def send_png(self, data: bytes) -> None:
        self.send_response(200)
        self.send_header("Content-Type", "image/png")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def con_token(self) -> bool:
        return STATE.token_valido(self.headers.get("Authorization") or "")

    # -- vistas ------------------------------------------------------------

    def planograma(self) -> dict:
        celdas = []
        for cell in STATE.cells:
            producto = json.loads(json.dumps(cell["producto"]))
            producto["archivoPrincipal"]["tcUrl"] = self.url_imagen(producto["archivoPrincipal"]["tcUrl"])
            celdas.append({
                "tnMaquinaCelda": cell["tnMaquinaCelda"],
                "tcCodigoCelda": cell["tcCodigoCelda"],
                "tlEstado": cell["tlEstado"],
                "tlOcupada": cell["tlOcupada"],
                "tnCantidadActual": cell["tnCantidadActual"],
                "tnCantidadReservada": cell["tnCantidadReservada"],
                "tnDisponible": STATE.disponible(cell),
                "producto": producto,
            })
        return {
            "celdas": celdas,
            "fondo": {
                "tnArchivo": 900,
                "archivo": {
                    "tnArchivo": 900,
                    "tcUrl": self.url_imagen("/img/fondo.png"),
                    "tcMimeType": "image/png",
                },
            },
        }

    def vista_pedido(self, pedido: dict) -> dict:
        detalles = []
        for detalle in pedido["taDetalles"]:
            cell = STATE.cell_by_id(detalle["tnPlanogramaCelda"]) or {}
            producto = cell.get("producto") or {}
            estado = detalle["tnEstadoDispensacion"]
            detalles.append({
                "tnPedidoDetalle": detalle["tnPedidoDetalle"],
                "tnPlanogramaCelda": detalle["tnPlanogramaCelda"],
                "tcCodigoCelda": cell.get("tcCodigoCelda"),
                "tnProducto": detalle["tnProducto"],
                "tcCodigoProducto": producto.get("tcCodigoProducto"),
                "tnCantidad": detalle["tnCantidad"],
                "tnPrecioVenta": producto.get("tnPrecioVenta"),
                "tnEstadoPedidoDetalle": 1,
                "tnEstadoDispensacion": estado,
                "tcEstadoDispensacion": ETIQUETA_DISPENSACION.get(estado),
            })
        estado_pedido = pedido["tnEstadoPedido"]
        pago = STATE.payments.get(pedido["pago"]) if pedido["pago"] else None
        return {
            "tnPedido": pedido["tnPedido"],
            "tnEmpresa": pedido["tnEmpresa"],
            "tnMaquina": pedido["tnMaquina"],
            "tcCodigoMaquina": pedido["tcCodigoMaquina"],
            "tnMetodoPago": (pago or {}).get("tnMetodoPago"),
            "tnMontoTotal": (pago or {}).get("tnMontoTotal"),
            "tnEstadoPedido": estado_pedido,
            "tcEstadoPedido": ETIQUETA_PEDIDO.get(estado_pedido),
            "ttFechaCreacion": pedido["ttFechaCreacion"],
            "taDetalles": detalles,
        }

    def vista_pago(self, pago: dict) -> dict:
        estado = pago["tnEstadoPago"]
        return {
            "tnPago": pago["tnPago"],
            "tnPedido": pago["tnPedido"],
            "tnMontoTotal": pago["tnMontoTotal"],
            "tnMetodoPago": pago["tnMetodoPago"],
            "tnEstadoPago": estado,
            "tcEstadoPago": ETIQUETA_PAGO.get(estado),
            "tcQrBase64": pago["tcQrBase64"],
            "ttExpira": pago["ttExpira"],
        }

    # -- GET ---------------------------------------------------------------

    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        path = parsed.path
        STATE.requests += 1

        if path.startswith("/img/"):
            name = path.rsplit("/", 1)[-1]
            if name.startswith("fondo"):
                self.send_png(png_solid(480, 360, (18, 26, 40)))
            elif name.startswith("promo"):
                index = int("".join(ch for ch in name if ch.isdigit()) or 0)
                palette = [(0, 90, 200), (220, 120, 0), (20, 140, 90)]
                self.send_png(png_solid(600, 200, palette[index % len(palette)]))
            else:
                index = int("".join(ch for ch in name if ch.isdigit()) or 0)
                self.send_png(png_product(index))
            STATE.log(f"GET {path} -> imagen")
            return

        if path == "/__status":
            self.send_json(json.dumps({
                "payment_mode": STATE.payment_mode,
                "polls_until_paid": STATE.polls_until_paid,
                "pedidos": {str(k): v["tnEstadoPedido"] for k, v in STATE.orders.items()},
                "pagos": {str(k): v["tnEstadoPago"] for k, v in STATE.payments.items()},
                "claves_idempotencia": STATE.idempotency,
                "requests": STATE.requests,
                "maquina": MACHINE_CODE,
                "identificador_de_equipo": MACHINE_MAC,
                "pin": MACHINE_PIN,
                "celdas": [
                    {"codigo": c["tcCodigoCelda"], "salida": physical_cell(i),
                     "disponible": STATE.disponible(c), "reservado": c["tnCantidadReservada"]}
                    for i, c in enumerate(STATE.cells)
                ],
            }, ensure_ascii=False).encode("utf-8"))
            return

        if path == "/__control":
            query = parse_qs(parsed.query)
            if "payment" in query:
                mode = query["payment"][0]
                if mode not in ("paid", "pending", "fail", "cancel"):
                    self.send_json(json.dumps({"error": f"modo invalido: {mode}"}).encode("utf-8"), 400)
                    return
                STATE.payment_mode = mode
                STATE.log(f"modo de pago = {mode}")
            if "polls" in query:
                STATE.polls_until_paid = int(query["polls"][0])
                STATE.log(f"sondeos hasta pago = {STATE.polls_until_paid}")
            self.send_json(json.dumps({"ok": True, "payment_mode": STATE.payment_mode,
                                       "polls_until_paid": STATE.polls_until_paid}).encode("utf-8"))
            return

        if path == f"{API}/maquina/planograma":
            if not self.con_token():
                self.enviar(sin_sesion())
                STATE.log("GET planograma -> 401 (sin token de maquina)")
                return
            planograma = self.planograma()
            self.enviar(envoltura(planograma))
            STATE.log(f"GET planograma -> {len(planograma['celdas'])} celdas")
            return

        if path == f"{API}/maquina/metodos-pago":
            if not self.con_token():
                self.enviar(sin_sesion())
                return
            metodos = [{"tnMetodoPago": pid, "tcNombreMetodoPago": label} for pid, label in PAYMENT_METHODS]
            self.enviar(envoltura(metodos))
            STATE.log(f"GET metodos-pago -> {len(metodos)} metodos")
            return

        if path.startswith(f"{API}/pagos/"):
            if not self.con_token():
                self.enviar(sin_sesion())
                return
            pago_id = int(path.rstrip("/").split("/")[-1])
            pago = STATE.poll_pago(pago_id)
            if pago is None:
                self.enviar(error("Pago no encontrado.", 404))
                STATE.log(f"GET pago {pago_id} -> 404")
                return
            self.enviar(envoltura(self.vista_pago(pago)))
            STATE.log(f"GET pago {pago_id} -> {ETIQUETA_PAGO.get(pago['tnEstadoPago'])} "
                      f"(sondeo {pago['polls']}, modo {STATE.payment_mode})")
            return

        self.enviar(error(f"ruta no simulada: {path}", 404))
        STATE.log(f"GET {path} -> 404 (ruta no simulada)")

    # -- POST --------------------------------------------------------------

    def do_POST(self) -> None:
        path = urlparse(self.path).path
        STATE.requests += 1
        payload = self.body_json()

        if path == f"{API}/maquina-auth/login":
            mac = str(payload.get("tcDireccionMac") or "").strip()
            pin = str(payload.get("tcPin") or "").strip()
            if not mac or not pin:
                self.enviar(error("Los datos enviados no son validos.", 422, {
                    "tcDireccionMac" if not mac else "tcPin": ["El campo es obligatorio."],
                }))
                STATE.log("POST login -> 422 (falta identificador o PIN)")
                return
            if mac.upper() != MACHINE_MAC.upper() or pin != MACHINE_PIN:
                self.enviar(error("Las credenciales proporcionadas son incorrectas.", 401))
                STATE.log(f"POST login ({mac}) -> 401 (identificador o PIN equivocado)")
                return
            token = STATE.new_token()
            self.send_json(json.dumps({
                "access_token": token,
                "token_type": "Bearer",
                "expires_in": 1440,
                "refresh_token": "sim-refresh-token",
                "message": "Sesion de maquina iniciada.",
                "Maquina": {
                    "tnMaquina": MACHINE_ID,
                    "tcCodigoMaquina": MACHINE_CODE,
                    "tlEstado": True,
                    "tnEstadoOperativo": 1,
                    "tcEstadoOperativo": "OPERATIVA",
                    "tnEstadoConexion": 1,
                    "tcEstadoConexion": "ONLINE",
                    "tnEmpresa": 1,
                    "tcEmpresa": "Boxipagos",
                    "tcUbicacion": MACHINE_LOCATION,
                },
            }, ensure_ascii=False).encode("utf-8"))
            STATE.log(f"POST login ({mac}) -> token {token}")
            return

        # Todo lo demas exige el token de la maquina, igual que el backend.
        if path.startswith(API) and not self.con_token():
            self.enviar(sin_sesion())
            STATE.log(f"POST {path} -> 401 (sin token de maquina)")
            return

        if path == f"{API}/pedidos":
            detalles = payload.get("Detalles") or []
            if not detalles:
                self.enviar(error("Los datos enviados no son validos.", 422,
                                  {"Detalles": ["El pedido no trae lineas."]}))
                return
            clave = (payload.get("ClaveIdempotencia") or "").strip() or None
            celda = int(detalles[0].get("MaquinaCelda") or 0)
            producto = int(detalles[0].get("Producto") or 0)
            cell = STATE.cell_by_id(celda)
            if cell is None or STATE.disponible(cell) <= 0:
                self.enviar(error("La celda no tiene unidades para vender.", 422,
                                  {"Detalles": ["Celda sin stock."]}))
                STATE.log(f"POST pedidos -> 422 (celda {celda} sin stock)")
                return
            pedido, es_nuevo = STATE.crear_pedido(celda, producto, clave)
            cliente = payload.get("tcNombreCliente") or "(anonimo)"
            self.enviar(envoltura(self.vista_pedido(pedido), 201 if es_nuevo else 200))
            STATE.log(f"POST pedidos -> {pedido['tnPedido']} celda {celda} "
                      f"({'nuevo' if es_nuevo else 'REPETIDO'}; clave {clave or 'sin clave'}) cliente {cliente}")
            return

        if path.endswith("/cancelar"):
            pedido_id = int(path.rstrip("/").split("/")[-2])
            pedido = STATE.pedido(pedido_id)
            if pedido is None:
                self.enviar(error("Pedido no encontrado.", 404))
                return
            pago_id = pedido.get("pago")
            if pago_id and STATE.payments[pago_id]["tnEstadoPago"] == PAGO_PAGADO:
                self.enviar(error("El pedido ya esta pagado: le corresponde una devolucion.", 422))
                STATE.log(f"POST pedido {pedido_id}/cancelar -> 422 (ya pagado)")
                return
            with STATE.lock:
                if pedido["tnEstadoPedido"] != PEDIDO_CANCELADO:
                    pedido["tnEstadoPedido"] = PEDIDO_CANCELADO
                    STATE.liberar_stock(pedido)
            self.enviar(envoltura(self.vista_pedido(pedido)))
            STATE.log(f"POST pedido {pedido_id}/cancelar -> cancelado (motivo={payload.get('tcMotivo')})")
            return

        if path.endswith("/dispensacion"):
            pedido_id = int(path.rstrip("/").split("/")[-2])
            pedido = STATE.pedido(pedido_id)
            if pedido is None:
                self.enviar(error("Pedido no encontrado.", 404))
                return
            detalle_id = int(payload.get("PedidoDetalle") or 0)
            estado = int(payload.get("DispensacionEstado") or 0)
            detalle = next((d for d in pedido["taDetalles"] if d["tnPedidoDetalle"] == detalle_id), None)
            if detalle is None:
                self.enviar(error("El detalle no pertenece al pedido.", 422))
                return
            detalle["tnEstadoDispensacion"] = estado
            if estado == DISPENSACION_COMPLETADO:
                with STATE.lock:
                    pedido["tnEstadoPedido"] = PEDIDO_ENTREGADO
                STATE.descontar_stock(pedido)
            self.enviar(envoltura(self.vista_pedido(pedido)))
            STATE.log(f"POST pedido {pedido_id}/dispensacion -> detalle {detalle_id} "
                      f"{ETIQUETA_DISPENSACION.get(estado, estado)} "
                      f"(celda {payload.get('MaquinaCelda')}, evento {payload.get('CodigoEvento')})")
            return

        if path == f"{API}/pagos":
            pedido_id = int(payload.get("Pedido") or 0)
            metodo = int(payload.get("MetodoPago") or 0)
            pedido = STATE.pedido(pedido_id)
            if pedido is None:
                self.enviar(error("Pedido no encontrado.", 404))
                STATE.log(f"POST pagos -> 404 (pedido {pedido_id})")
                return
            if metodo not in [pid for pid, _ in PAYMENT_METHODS]:
                self.enviar(error("Los datos enviados no son validos.", 422,
                                  {"MetodoPago": ["Metodo no habilitado."]}))
                return
            pago = STATE.crear_pago(pedido, metodo)
            self.enviar(envoltura(self.vista_pago(pago), 201))
            STATE.log(f"POST pagos -> pago {pago['tnPago']} del pedido {pedido_id} "
                      f"({pago['tnMontoTotal']} Bs, metodo {metodo})")
            return

        self.enviar(error(f"ruta no simulada: {path}", 404))
        STATE.log(f"POST {path} -> 404 (ruta no simulada)")


def control_stdin() -> None:
    for raw in sys.stdin:
        command = raw.strip().lower()
        if not command:
            continue
        if command.startswith("payment"):
            parts = command.split()
            STATE.payment_mode = parts[1] if len(parts) > 1 else "paid"
            STATE.log(f"modo de pago = {STATE.payment_mode}")
        elif command.startswith("polls"):
            parts = command.split()
            STATE.polls_until_paid = int(parts[1]) if len(parts) > 1 else 2
            STATE.log(f"sondeos hasta pago = {STATE.polls_until_paid}")
        elif command == "status":
            STATE.log(f"modo={STATE.payment_mode} "
                      f"pedidos={ {k: v['tnEstadoPedido'] for k, v in STATE.orders.items()} }")
        else:
            print("comandos: payment paid|pending|fail|cancel | polls <n> | status", flush=True)


def main() -> int:
    parser = argparse.ArgumentParser(description="Backend simulado para la APK de vending")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=9200)
    parser.add_argument("--payment", default="paid", choices=["paid", "pending", "fail", "cancel"])
    parser.add_argument("--polls", type=int, default=2, help="sondeos en estado pendiente antes de confirmar el pago")
    parser.add_argument("--log", default=None)
    args = parser.parse_args()

    STATE.payment_mode = args.payment
    STATE.polls_until_paid = args.polls
    if args.log:
        STATE.log_file = open(args.log, "a", encoding="utf-8")

    STATE.log("=" * 70)
    STATE.log("Backend simulado de vending (sin pasarelas reales)")
    STATE.log(f"escucha en http://{args.host}:{args.port}{API} | la APK del emulador usa http://10.0.2.2:{args.port}")
    STATE.log(f"equipo={MACHINE_MAC} PIN={MACHINE_PIN} | maquina={MACHINE_CODE} | pago={STATE.payment_mode}")
    STATE.log("control: curl \"http://127.0.0.1:%d/__control?payment=fail\" | /__status" % args.port)
    STATE.log("=" * 70)

    server = ThreadingHTTPServer((args.host, args.port), Handler)
    threading.Thread(target=control_stdin, daemon=True).start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        STATE.log("detenido por el usuario")
    return 0


if __name__ == "__main__":
    sys.exit(main())
