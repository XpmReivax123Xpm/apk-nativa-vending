#!/usr/bin/env python3
"""
Backend simulado para la APK de vending (modo simulacion).

Responde los mismos endpoints que el backend real, con datos de prueba, QR
generado localmente y un pago que se confirma solo. No habla con ninguna pasarela
de pago real y no toca el servidor de produccion.

    python mock_backend.py                 # escucha en 127.0.0.1:9200
    curl "http://127.0.0.1:9200/__status"
    curl "http://127.0.0.1:9200/__control?payment=fail"

Modos de pago: paid (se confirma a los pocos sondeos), pending (nunca),
fail (rechazado), cancel (cancelado).
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

PRODUCTS = [
    ("Agua 600 ml", 5.00), ("Coca-Cola 500 ml", 7.00), ("Jugo Del Valle 300 ml", 6.50),
    ("Papas Lays 45 g", 8.00), ("Chocolate Sublime", 6.00), ("Galleta Oreo", 5.50),
    ("Maní Salado 40 g", 4.50), ("Cerveza Paceña 330 ml", 12.00), ("Red Bull 250 ml", 15.00),
    ("Agua con gas 500 ml", 6.00), ("Bon o Bon", 2.50), ("Chicle Trident", 3.00),
    ("Salchipapa snack", 9.00), ("Yogur Pil 200 ml", 7.50), ("Doritos 40 g", 8.50),
]

MACHINE_ID = 1
MACHINE_CODE = "MAQ-SIM-01"
MACHINE_PIN = "1234"
ADMIN_PIN = "9999"


# ---------------------------------------------------------------------------
# PNG minimo (sin dependencias): cuadro solido y patron tipo QR
# ---------------------------------------------------------------------------

def png_from_rows(rows: list[bytes], width: int, height: int) -> bytes:
    """rows: una lista de filas de bytes RGB (o gris) ya serializadas."""
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
# Estado simulado
# ---------------------------------------------------------------------------

class State:
    def __init__(self):
        self.lock = threading.Lock()
        self.orders: dict[int, dict] = {}
        self.next_order = 1000
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

    def create_order(self, payload: dict) -> dict:
        with self.lock:
            self.next_order += 1
            order_id = self.next_order
            self.orders[order_id] = {"payload": payload, "polls": 0, "created": time.time()}
            return self.orders[order_id]

    def order_status(self, order_id: int) -> dict:
        with self.lock:
            order = self.orders.get(order_id)
            if order is None:
                self.orders[order_id] = {"payload": {}, "polls": 0, "created": time.time()}
                order = self.orders[order_id]
            order["polls"] += 1
            mode = self.payment_mode
            polls = order["polls"]
        if mode == "paid":
            paid = polls > self.polls_until_paid
            return {"tnEstadoPago": 2 if paid else 1, "tnEstadoPedido": 2 if paid else 1, "estado": 2 if paid else 1}
        if mode == "fail":
            return {"tnEstadoPago": 3, "tnEstadoPedido": 3, "estado": 3}
        if mode == "cancel":
            return {"tnEstadoPago": 4, "tnEstadoPedido": 4, "estado": 4}
        return {"tnEstadoPago": 1, "tnEstadoPedido": 1, "estado": 1}


STATE = State()


# ---------------------------------------------------------------------------
# Datos de catalogo
# ---------------------------------------------------------------------------

def build_planogram(base: str) -> dict:
    cells = []
    for index, (name, price) in enumerate(PRODUCTS):
        cell_number = 10 + index
        product_id = 200 + index
        cells.append({
            "tnPlanogramaCelda": 100 + index,
            "tnCelda": cell_number,
            "tcCodigo": str(cell_number),
            "tnEsActiva": 1,
            "tnEstado": 1,
            "tnProducto": product_id,
            "tcPrecio": price,
            "tnPrecio": price,
            "taProducto": {
                "tnProducto": product_id,
                "tcNombre": name,
                "tnEstado": 1,
                "tnPrecio": price,
                "taImagenPrincipal": {
                    "tnProductoArchivo": 300 + index,
                    "tcUrl": f"{base}/img/producto{index}.png",
                },
            },
            "taInventario": {"tnCantidad": 8, "tnCantidadReservada": 0},
        })
    presentations = [
        {
            "tnPresentacionArchivo": 400 + index,
            "tcUrl": f"{base}/img/promo{index}.png",
            "tnEstado": 1,
            "tcUsoTipo": "carrusel",
            "tcMimeType": "image/png",
            "tnOrden": index,
        }
        for index in range(3)
    ]
    return {
        "celdas": cells,
        "taPresentacionArchivos": presentations,
        "taFondoUiPrincipal": {
            "tnPresentacionArchivo": 500,
            "tcUrl": f"{base}/img/fondo.png",
            "tnEstado": 1,
            "tcUsoTipo": "fondo",
            "tcMimeType": "image/png",
        },
    }


def envelope(values: dict | None = None, message: str = "OK") -> bytes:
    return json.dumps({
        "error": 0,
        "status": 1,
        "message": message,
        "values": values if values is not None else {},
    }).encode("utf-8")


# ---------------------------------------------------------------------------
# HTTP
# ---------------------------------------------------------------------------

class Handler(BaseHTTPRequestHandler):
    server_version = "VendingMock/1.0"

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

    def base_url(self) -> str:
        host = self.headers.get("Host") or f"127.0.0.1:{self.server.server_port}"
        return f"http://{host}"

    def send_png(self, data: bytes) -> None:
        self.send_response(200)
        self.send_header("Content-Type", "image/png")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    # -- rutas -------------------------------------------------------------

    def do_GET(self) -> None:
        path = urlparse(self.path).path
        STATE.requests += 1
        if path.startswith("/img/"):
            name = path.rsplit("/", 1)[-1]
            if name.startswith("promo"):
                index = int(name[5:].split(".")[0])
                palette = [(0, 90, 200), (220, 120, 0), (20, 140, 90)]
                self.send_png(png_solid(600, 200, palette[index % len(palette)]))
            elif name.startswith("fondo"):
                self.send_png(png_solid(400, 300, (24, 32, 48)))
            else:
                index = int("".join(ch for ch in name if ch.isdigit()) or 0)
                r = 60 + (index * 37) % 160
                g = 60 + (index * 71) % 160
                b = 60 + (index * 113) % 160
                self.send_png(png_solid(120, 120, (r, g, b)))
            STATE.log(f"GET {path} -> imagen")
            return

        if path == "/__status":
            self.send_json(json.dumps({
                "payment_mode": STATE.payment_mode,
                "orders": {str(k): v["polls"] for k, v in STATE.orders.items()},
                "requests": STATE.requests,
                "machine_code": MACHINE_CODE,
                "admin_pin": ADMIN_PIN,
            }).encode("utf-8"))
            return

        if path == "/__control":
            query = parse_qs(urlparse(self.path).query)
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

        if path == "/api/maquinas":
            self.send_json(envelope({"maquinas": [{
                "tnMaquina": MACHINE_ID,
                "tcCodigo": MACHINE_CODE,
                "tcLocacion": "PC de pruebas (simulador)",
                "tnEstado": 1,
            }]}))
            STATE.log("GET /api/maquinas -> 1 maquina")
            return

        if path.endswith("/planograma"):
            values = build_planogram(self.base_url())
            self.send_json(envelope(values))
            STATE.log(f"GET {path} -> {len(values['celdas'])} celdas")
            return

        if path == "/api/maquina/pago/qr/servicios-habilitados":
            self.send_json(envelope({"taProviderResponse": {"error": 0, "values": [
                {"paymentMethodId": 1, "paymentMethodName": "QR BCP"},
                {"paymentMethodId": 2, "paymentMethodName": "QR ATC"},
            ]}}))
            STATE.log("GET servicios-habilitados -> QR BCP, QR ATC")
            return

        if path.endswith("/estado-pago"):
            order_id = int(path.split("/")[-2])
            status = STATE.order_status(order_id)
            self.send_json(envelope(status))
            STATE.log(f"GET pedido {order_id}/estado-pago -> {status} (modo {STATE.payment_mode})")
            return

        self.send_json(json.dumps({"error": 1, "status": 0, "message": f"ruta no simulada: {path}"}).encode("utf-8"), 404)
        STATE.log(f"GET {path} -> 404 (ruta no simulada)")

    def do_POST(self) -> None:
        path = urlparse(self.path).path
        STATE.requests += 1
        payload = self.body_json()

        if path == "/api/login":
            self.send_json(envelope({
                "accessToken": "sim-operator-token",
                "tokenType": "bearer",
                "expiresInMinutes": 120,
            }))
            STATE.log(f"POST /api/login (correo={payload.get('tcCorreo')}) -> token simulado")
            return

        if path == "/api/maquinas/login":
            code = str(payload.get("tcCodigoMaquina", ""))
            pin = str(payload.get("tcPin", ""))
            if code.strip() and pin == MACHINE_PIN:
                self.send_json(envelope({
                    "accessToken": "sim-machine-token",
                    "tokenType": "bearer",
                    "expiresInMinutes": 120,
                    "tnMaquina": MACHINE_ID,
                    "tcCodigoMaquina": code,
                }))
                STATE.log(f"POST maquinas/login ({code}) -> PIN correcto")
            else:
                self.send_json(json.dumps({"error": 1, "status": 0,
                                           "message": f"PIN invalido (usa {MACHINE_PIN})"}).encode("utf-8"), 401)
                STATE.log(f"POST maquinas/login ({code}) -> PIN invalido")
            return

        if path == "/api/maquinas/acceso":
            pin = str(payload.get("tcPin", ""))
            granted = 1 if pin == ADMIN_PIN else 0
            self.send_json(envelope({"tnAcceso": granted}))
            STATE.log(f"POST maquinas/acceso -> {'concedido' if granted else f'denegado (usa {ADMIN_PIN})'}")
            return

        if path == "/api/pedido/crear-y-generar-qr":
            order = STATE.create_order(payload)
            order_id = STATE.next_order
            qr = base64.b64encode(png_qr_like(order_id)).decode("ascii")
            self.send_json(envelope({
                "taPedido": {"tnPedido": order_id, "tnEstadoPedido": 1},
                "taQr": {
                    "tcQrBase64": qr,
                    "ltExpirationDate": time.strftime("%Y-%m-%dT%H:%M:%S", time.localtime(time.time() + 120)),
                    "tnTransaccionProveedor": f"SIM-{order_id}",
                    "tcTransaccionMetodoProveedor": "SIMULADO",
                },
                "taPedidoDetalle": [
                    {"tnPedidoDetalle": 9000 + i, "tnPlanogramaCelda": item.get("tnPlanogramaCelda"),
                     "tnProducto": item.get("tnProducto"), "tnCantidad": item.get("tnCantidad")}
                    for i, item in enumerate(payload.get("taItems", []))
                ],
            }))
            STATE.log(f"POST crear-y-generar-qr -> pedido {order_id} ({len(payload.get('taItems', []))} items, "
                      f"{payload.get('tcNombreCliente')})")
            return

        if path.endswith("/cancelar"):
            order_id = int(path.split("/")[-2])
            self.send_json(envelope({"tnPedido": order_id, "tnEstadoPedido": 5}))
            STATE.log(f"POST pedido {order_id}/cancelar -> cancelado (motivo={payload.get('tcMotivo')})")
            return

        if path == "/api/maquina/pedido/dispensacion":
            self.send_json(envelope({"tnPedido": payload.get("tnPedido")}))
            STATE.log(f"POST dispensacion -> pedido {payload.get('tnPedido')} "
                      f"detalle {payload.get('tnPedidoDetalle')} celda {payload.get('tnPlanogramaCelda')} "
                      f"estado {payload.get('tnEstadoDispensacion')} ({payload.get('tcEstadoDispensacion')})")
            return

        self.send_json(json.dumps({"error": 1, "status": 0, "message": f"ruta no simulada: {path}"}).encode("utf-8"), 404)
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
            STATE.log(f"modo={STATE.payment_mode} pedidos={ {k: v['polls'] for k, v in STATE.orders.items()} }")
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
    STATE.log(f"escucha en http://{args.host}:{args.port} | la APK del emulador usa http://10.0.2.2:{args.port}")
    STATE.log(f"maquina={MACHINE_CODE} PIN maquina={MACHINE_PIN} | PIN admin={ADMIN_PIN} | pago={STATE.payment_mode}")
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
