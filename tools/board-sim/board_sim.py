#!/usr/bin/env python3
"""
Simulador de placa de maquina expendedora (ZhongDa / Modbus RTU sobre serie).

Reemplaza fisicamente a la controladora de la vending cuando la APK corre en el
emulador de la PC: la APK abre "el puerto serie" contra este servicio (TCP
127.0.0.1:9100) y este responde con las mismas tramas Modbus y la misma
secuencia de IO que produce la placa real.

No depende de nada externo: solo biblioteca estandar de Python.

Ver docs/SIMULADOR_PC.md para el uso completo.
"""

from __future__ import annotations

import argparse
import socket
import sys
import threading
import time

# ---------------------------------------------------------------------------
# Constantes del protocolo observadas en el runtime de la APK
# (VendingFlowController / CommandSet / VendingTesterActivity)
# ---------------------------------------------------------------------------

ADDRESS = 0x01

FUNC_READ_HOLDING = 0x03
FUNC_WRITE_SINGLE = 0x06
FUNC_WRITE_MULTIPLE = 0x10

REG_DRIVER_STATUS = 0x0003
REG_IO_STATUS = 0x000B
REG_DROP_STATUS = 0x0008          # GetDropStatus del SDK (DS): 1 = sensores normales
REG_Y_STATUS = 0x000D             # GetYStatus del SDK (YS): byte alto = estado, bajo = falla
REG_Y_POSITIONS = 0x000F          # GetYPos del SDK (YP): 10 registros con las posiciones Y
REG_CELL_SELECT = 0x0001
REG_RESET_LIFT = 0x1002
REG_Y_POINTS = 0x2002             # SeYPos del SDK (SYP): escritura de 10 puntos de calibracion
REG_TOY_POSITION = 0x1007         # ToY real del SDK (TY)
REG_TOY_POSITION_LEGACY = 0x1010  # alias que usaba una version anterior del simulador

# Otros registros de escritura del SDK ZhongDa (extraidos del jar): se reconocen
# y se responden OK, pero no tienen efecto en el simulador.
SDK_OTHER_WRITES = {
    0x1003: "RunMoto",
    0x1004: "MotoTimeout",
    0x1005: "SetPickY",
    0x1006: "SetPickX",
    0x1008: "ToX",
    0x2001: "Shipment",
    0x2003: "SeXPos",
    0x2004: "ArrayOutput",
    0x2005: "DriveOutput",
}

# Registro 0x0003: estado del driver de dispensacion.
DRIVER_RUNNING = 0x0000  # 0000 = driver detenido; la APK lo trata como anomalia
DRIVER_BUSY = 0x0001     # dispensando (valor inferido: la APK solo exige != 0000 y != 0200)
DRIVER_DONE = 0x0200     # la APK lo detecta como DONE y cierra la fase de vend

# Registro 0x000B: palabra de estado de IO. Codigos que la APK interpreta.
IO_IDLE = 0x00
IO_DOOR_OPEN_FIRST_TIME = 0x02
IO_PRODUCT_REMOVED_DOOR_OPEN = 0x12
IO_AFTER_FIRST_CLICK = 0x82
IO_DOOR_CLOSED_NO_PROD = 0x92
IO_SECOND_CLICK = 0xD2
IO_TRANSITION_42 = 0x42
IO_TRANSITION_52 = 0x52
IO_WHITE_DOOR_CLOSING = 0xC2
IO_PLATFORM_DOWN = 0xC8
IO_PLATFORM_UP = 0xD8
IO_WHITE_DOOR_OPENING = 0xD2

# Variantes con "puerta blanca abierta" que la APK acepta solo en el camino de
# retry manual (ToY(2000) -> ToY(0)); ver pickupWhiteDoorVariantMode.
IO_DOOR_OPEN_FIRST_TIME_WD = 0x0A
IO_PRODUCT_REMOVED_DOOR_OPEN_WD = 0x1A
IO_AFTER_FIRST_CLICK_WD = 0x8A
IO_DOOR_CLOSED_NO_PROD_WD = 0x9A
IO_SECOND_CLICK_WD = 0xDA

IO_NAMES = {
    IO_IDLE: "reposo",
    IO_DOOR_OPEN_FIRST_TIME: "puerta chica: abierta (primer contacto)",
    IO_PRODUCT_REMOVED_DOOR_OPEN: "puerta chica: producto retirado, puerta abierta",
    IO_AFTER_FIRST_CLICK: "puerta chica: primer click confirmado",
    IO_DOOR_CLOSED_NO_PROD: "puerta chica: cerrada SIN producto",
    IO_SECOND_CLICK: "puerta chica: segundo click -> fin de retiro",
    IO_TRANSITION_42: "transicion informativa (42)",
    IO_TRANSITION_52: "transicion informativa (52)",
    IO_WHITE_DOOR_CLOSING: "puerta blanca: cerrando",
    IO_PLATFORM_DOWN: "plataforma: bajando",
    IO_PLATFORM_UP: "plataforma: subiendo",
    IO_WHITE_DOOR_OPENING: "puerta blanca: abriendo",
    IO_DOOR_OPEN_FIRST_TIME_WD: "puerta chica: abierta (variante puerta blanca)",
    IO_PRODUCT_REMOVED_DOOR_OPEN_WD: "producto retirado (variante puerta blanca)",
    IO_AFTER_FIRST_CLICK_WD: "primer click (variante puerta blanca)",
    IO_DOOR_CLOSED_NO_PROD_WD: "cerrada SIN producto (variante puerta blanca)",
    IO_SECOND_CLICK_WD: "segundo click (variante puerta blanca) -> fin de retiro",
}

SCENARIOS = ("normal", "stuck", "crushed", "driver0000", "nopickup", "transitions")

BASE_SIGNALS = (IO_WHITE_DOOR_CLOSING, IO_AFTER_FIRST_CLICK, IO_DOOR_OPEN_FIRST_TIME, IO_DOOR_CLOSED_NO_PROD)


# ---------------------------------------------------------------------------
# CRC16 Modbus (el mismo algoritmo que usa CommandSet.kt en la APK)
# ---------------------------------------------------------------------------

def crc16_modbus(data: bytes) -> int:
    crc = 0xFFFF
    for byte in data:
        crc ^= byte
        for _ in range(8):
            crc = (crc >> 1) ^ 0xA001 if crc & 1 else crc >> 1
    return crc & 0xFFFF


def append_crc(frame_without_crc: bytes) -> bytes:
    crc = crc16_modbus(frame_without_crc)
    return frame_without_crc + bytes((crc & 0xFF, (crc >> 8) & 0xFF))


def hexs(data: bytes) -> str:
    return " ".join(f"{b:02X}" for b in data)


# ---------------------------------------------------------------------------
# Log
# ---------------------------------------------------------------------------

class Log:
    def __init__(self, path: str | None = None):
        self._lock = threading.Lock()
        self._file = open(path, "a", encoding="utf-8") if path else None

    def line(self, tag: str, message: str) -> None:
        stamp = time.strftime("%H:%M:%S") + f".{int(time.time() * 1000) % 1000:03d}"
        text = f"{stamp} [{tag}] {message}"
        with self._lock:
            print(text, flush=True)
            if self._file:
                self._file.write(text + "\n")
                self._file.flush()


# ---------------------------------------------------------------------------
# Maquina simulada
# ---------------------------------------------------------------------------

class Machine:
    """Estado de la controladora + secuencias temporizadas de dispensacion."""

    def __init__(self, log: Log, scenario: str = "normal", speed: float = 1.0):
        self.log = log
        self.lock = threading.RLock()
        self.scenario = scenario
        self.speed = speed
        self.driver = DRIVER_RUNNING
        self.io = IO_IDLE
        self.state = "idle"
        self.cell: int | None = None
        self.generation = 0
        self.manual_retry_armed = False
        self.cycles = 0
        self._timers: list[threading.Timer] = []
        # Bloque de calibracion (lo que consume el Calibrador de la APK vía SDK)
        self.drop_status = 1
        self.y_run_status = 0
        self.y_fault = 0
        self.y_current = 0
        self.y_points = [row * 100 for row in range(10)]

    # -- estado ------------------------------------------------------------

    def snapshot(self) -> str:
        with self.lock:
            return (
                f"estado={self.state} escenario={self.scenario} celda={self.cell} "
                f"driver=0x{self.driver:04X} io=0x{self.io:02X} ciclos={self.cycles} "
                f"velocidad={self.speed:.2f}x"
            )

    def calibration_snapshot(self) -> str:
        with self.lock:
            return (
                f"Y actual={self.y_current} | estado={self.y_run_status} falla={self.y_fault} "
                f"| sensores={self.drop_status} | puntos={self.y_points}"
            )

    def read_register(self, register: int) -> int:
        with self.lock:
            if register == REG_DRIVER_STATUS:
                return self.driver
            if register == REG_IO_STATUS:
                return self.io
            if register == REG_DROP_STATUS:
                return self.drop_status
            if register == REG_Y_STATUS:
                return ((self.y_run_status & 0xFF) << 8) | (self.y_fault & 0xFF)
            if REG_Y_POSITIONS <= register < REG_Y_POSITIONS + len(self.y_points):
                return self.y_points[register - REG_Y_POSITIONS]
            return 0x0000

    # -- planificacion -----------------------------------------------------

    def _cancel_timers(self) -> None:
        for timer in self._timers:
            timer.cancel()
        self._timers = []

    def _at(self, generation: int, delay_ms: float, kind: str, value: int, note: str) -> None:
        def fire() -> None:
            with self.lock:
                if generation != self.generation:
                    return
                if kind == "driver":
                    self.driver = value
                    self.log.line("DRV", f"driver = 0x{value:04X}  ({note})")
                elif kind == "state":
                    self.state = note
                    self.log.line("SIM", f"estado = {note}")
                else:
                    self.io = value
                    self.log.line("IO ", f"io = 0x{value:02X}  ({note})")
        timer = threading.Timer(max(delay_ms, 0) / 1000.0 / max(self.speed, 0.1), fire)
        timer.daemon = True
        timer.start()
        self._timers.append(timer)

    # -- secuencias --------------------------------------------------------

    def _vend_steps(self) -> list[tuple[float, str, int, str]]:
        """Tramo de dispensacion (desde que llega SELECT hasta el DONE del driver)."""
        if self.scenario == "stuck":
            # DONE antes de 3s desde que la APK ve "plataforma bajando" (0xC8):
            # la APK lo interpreta como plataforma atorada.
            return [
                (0, "io", IO_WHITE_DOOR_OPENING, "puerta blanca: abriendo"),
                (1500, "io", IO_PLATFORM_UP, "plataforma: subiendo"),
                (3500, "io", IO_PLATFORM_DOWN, "plataforma: bajando"),
                (4600, "driver", DRIVER_DONE, "ciclo terminado DEMASIADO rapido (simula atascado)"),
            ]
        if self.scenario == "crushed":
            # 0xC8 sostenido, sin 0xC2 y DONE recien a los 13s: producto aplastado.
            return [
                (0, "io", IO_WHITE_DOOR_OPENING, "puerta blanca: abriendo"),
                (1500, "io", IO_PLATFORM_UP, "plataforma: subiendo"),
                (3500, "io", IO_PLATFORM_DOWN, "plataforma: bajando"),
                (16500, "driver", DRIVER_DONE, "ciclo terminado sin cierre de puerta blanca"),
            ]
        if self.scenario == "driver0000":
            # Sin progreso de IO y driver fijo en 0x0000: la APK corta a los 3 sondeos.
            return [
                (20000, "driver", DRIVER_RUNNING, "driver sigue en 0000 (falla de dispensacion)"),
            ]
        # normal / nopickup / transitions
        return [
            (0, "io", IO_WHITE_DOOR_OPENING, "puerta blanca: abriendo"),
            (1500, "io", IO_PLATFORM_UP, "plataforma: subiendo"),
            (3500, "io", IO_PLATFORM_DOWN, "plataforma: bajando"),
            (8000, "io", IO_WHITE_DOOR_CLOSING, "puerta blanca: cerrando"),
            (11000, "driver", DRIVER_DONE, "ciclo terminado (DONE)"),
        ]

    def _pickup_steps(self, base_ms: float, variant: bool) -> list[tuple[float, str, int, str]]:
        if self.scenario == "nopickup":
            # El cliente nunca retira: la APK acumula IO_TIMEOUT_PROLONGED y sigue esperando.
            return [(base_ms + 600, "io", IO_DOOR_OPEN_FIRST_TIME, "puerta chica abierta y nadie retira el producto")]
        if variant:
            return [
                (base_ms + 600, "io", IO_DOOR_OPEN_FIRST_TIME_WD, "puerta chica abierta (retry manual)"),
                (base_ms + 2100, "io", IO_AFTER_FIRST_CLICK_WD, "primer click (retry manual)"),
                (base_ms + 3600, "io", IO_DOOR_CLOSED_NO_PROD_WD, "cerrada sin producto (retry manual)"),
                (base_ms + 5100, "io", IO_SECOND_CLICK_WD, "segundo click -> fin de retiro (retry manual)"),
            ]
        if self.scenario == "transitions":
            return [
                (base_ms + 600, "io", IO_DOOR_OPEN_FIRST_TIME, "puerta chica abierta"),
                (base_ms + 2100, "io", IO_TRANSITION_42, "transicion informativa"),
                (base_ms + 3400, "io", IO_AFTER_FIRST_CLICK, "primer click"),
                (base_ms + 4900, "io", IO_TRANSITION_52, "transicion informativa"),
                (base_ms + 6400, "io", IO_DOOR_CLOSED_NO_PROD, "cerrada sin producto"),
                (base_ms + 7900, "io", IO_SECOND_CLICK, "segundo click -> fin de retiro"),
            ]
        return [
            (base_ms + 600, "io", IO_DOOR_OPEN_FIRST_TIME, "puerta chica abierta (primer contacto)"),
            (base_ms + 2100, "io", IO_AFTER_FIRST_CLICK, "primer click confirmado"),
            (base_ms + 3600, "io", IO_DOOR_CLOSED_NO_PROD, "cerrada sin producto"),
            (base_ms + 5100, "io", IO_SECOND_CLICK, "segundo click -> fin de retiro"),
        ]

    # -- eventos -----------------------------------------------------------

    def select_cell(self, cell: int) -> None:
        with self.lock:
            self.generation += 1
            generation = self.generation
            self._cancel_timers()
            self.cell = cell
            self.cycles += 1
            self.state = "vend"
            # El escenario driver0000 reproduce la anomalia real: driver fijo en 0000.
            self.driver = DRIVER_RUNNING if self.scenario == "driver0000" else DRIVER_BUSY
            self.io = IO_WHITE_DOOR_OPENING if self.scenario != "driver0000" else IO_IDLE
            self.log.line("SIM", f"ciclo #{self.cycles} para celda {cell} (escenario={self.scenario})")
            steps = self._vend_steps()
            for delay, kind, value, note in steps:
                self._at(generation, delay, kind, value, note)

            # El tramo de retiro arranca cuando la APK ya vio el DONE. Excepto en
            # "stuck" (espera accion del operador) y en "crushed"/"driver0000" (la
            # APK corta el ciclo y el retiro solo aparece tras la recuperacion).
            if self.scenario in ("normal", "nopickup", "transitions"):
                done_at = max(d for d, k, _, _ in steps if k == "driver")
                pickup_steps = self._pickup_steps(done_at, variant=False)
                for delay, kind, value, note in pickup_steps:
                    self._at(generation, delay, kind, value, note)
                self._at(generation, done_at + 100, "state", 0, "retiro")
                self._at(generation, max(d for d, _, _, _ in pickup_steps) + 250, "state", 0,
                         "esperando retiro del cliente" if self.scenario == "nopickup" else "entregado")

    def to_y(self, position: int) -> None:
        """ToY del SDK emulado: mueve la plataforma y deja IO en senal de base."""
        with self.lock:
            self.generation += 1
            generation = self.generation
            self._cancel_timers()
            self.log.line("SIM", f"ToY({position}) recibido -> plataforma a posicion {position}")
            self.y_current = position
            # Simplificacion documentada: la primera fila de la tabla de posiciones
            # refleja el movimiento para que el Calibrador muestre el cambio.
            self.y_points[0] = position
            self.driver = DRIVER_BUSY
            self.state = "recovery"
            if position != 0:
                # Primer paso del retry manual (ToY(2000)): solo mueve la plataforma.
                # El retorno a base real lo hace el ToY(0) que viene despues.
                self.manual_retry_armed = True
                self._at(generation, 3000, "driver", DRIVER_DONE, "plataforma en posicion pedida")
                return

            variant = self.manual_retry_armed
            self.manual_retry_armed = False
            self.io = IO_DOOR_OPEN_FIRST_TIME
            self._at(generation, 3000, "driver", DRIVER_DONE, "plataforma detenida en base")
            # La APK tarda unos segundos (cierre del transporte + reapertura) antes
            # de volver a sondear IO, por eso la senal de base se mantiene hasta entonces.
            base_ms = 8000
            steps = self._pickup_steps(base_ms, variant=variant)
            for delay, kind, value, note in steps:
                self._at(generation, delay, kind, value, note)
            self._at(generation, base_ms - 500, "state", 0, "retiro")
            self._at(generation, max(d for d, _, _, _ in steps) + 250, "state", 0,
                     "esperando retiro del cliente" if self.scenario == "nopickup" else "entregado")

    def reset_lift(self) -> None:
        with self.lock:
            self.generation += 1
            generation = self.generation
            self._cancel_timers()
            self.log.line("SIM", "ResetLift recibido -> plataforma a base")
            self.state = "recovery"
            self.driver = DRIVER_BUSY
            self.io = IO_AFTER_FIRST_CLICK
            self._at(generation, 1500, "driver", DRIVER_DONE, "plataforma en base (fallback ResetLift)")
            self._at(generation, 2000, "io", IO_WHITE_DOOR_CLOSING, "puerta blanca cerrada (senal de base)")
            self._at(generation, 6000, "io", IO_IDLE, "reposo")

    def reset(self) -> None:
        with self.lock:
            self.generation += 1
            self._cancel_timers()
            self.driver = DRIVER_RUNNING
            self.io = IO_IDLE
            self.state = "idle"
            self.cell = None
            self.log.line("SIM", "estado reiniciado")

    def set_scenario(self, scenario: str) -> None:
        with self.lock:
            if scenario not in SCENARIOS:
                raise ValueError(f"escenario invalido: {scenario} (validos: {', '.join(SCENARIOS)})")
            self.scenario = scenario
            self.log.line("SIM", f"escenario = {scenario}")

    def set_speed(self, speed: float) -> None:
        with self.lock:
            if not 0.5 <= speed <= 1.5:
                raise ValueError("velocidad fuera de rango (0.5 a 1.5)")
            self.speed = speed
            self.log.line("SIM", f"velocidad = {speed:.2f}x")

    def set_fault(self, code: int) -> None:
        with self.lock:
            self.y_fault = code
            self.log.line("SIM", f"falla de plataforma = {code} (0 = sin falla)")

    def set_y_points(self, points: list[int]) -> None:
        with self.lock:
            self.y_points = [int(value) for value in points[:10]]
            self.log.line("SIM", f"puntos Y de la placa = {self.y_points}")


# ---------------------------------------------------------------------------
# Servidor Modbus RTU (linea serie por TCP)
# ---------------------------------------------------------------------------

class BoardServer:
    def __init__(self, machine: Machine, log: Log, host: str, port: int, latency_ms: float = 20.0):
        self.machine = machine
        self.log = log
        self.host = host
        self.port = port
        self.latency_ms = latency_ms
        self._server: socket.socket | None = None
        self._threads: list[threading.Thread] = []
        self._stop = threading.Event()

    def start(self) -> None:
        server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind((self.host, self.port))
        server.listen(4)
        self._server = server
        self.log.line("NET", f"linea serie escuchando en {self.host}:{self.port} (la APK usa 10.0.2.2:{self.port})")
        thread = threading.Thread(target=self._accept_loop, name="board-accept", daemon=True)
        thread.start()
        self._threads.append(thread)

    def stop(self) -> None:
        self._stop.set()
        if self._server:
            try:
                self._server.close()
            except OSError:
                pass

    def _accept_loop(self) -> None:
        while not self._stop.is_set():
            try:
                assert self._server is not None
                conn, addr = self._server.accept()
            except OSError:
                return
            conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            self.log.line("NET", f"linea conectada desde {addr[0]}:{addr[1]}")
            handler = threading.Thread(target=self._client_loop, args=(conn,), name="board-line", daemon=True)
            handler.start()

    def _client_loop(self, conn: socket.socket) -> None:
        buffer = bytearray()
        try:
            while not self._stop.is_set():
                chunk = conn.recv(1024)
                if not chunk:
                    return
                buffer.extend(chunk)
                while True:
                    parsed = self._process(buffer)
                    if parsed is None:
                        break
                    consumed, response = parsed
                    if consumed == 0:
                        buffer.clear()
                        break
                    del buffer[:consumed]
                    if response:
                        time.sleep(self.latency_ms / 1000.0)
                        conn.sendall(response)
        except (OSError, ConnectionResetError):
            return
        finally:
            try:
                conn.close()
            except OSError:
                pass
            self.log.line("NET", "linea desconectada")

    def _process(self, buffer: bytearray) -> tuple[int, bytes | None] | None:
        """Devuelve (bytes consumidos, respuesta), o None si falta completar la trama."""
        if not buffer:
            return None
        if buffer[0] != ADDRESS:
            # resincronizacion: se descarta todo hasta la direccion esperada
            index = buffer.find(bytes((ADDRESS,)))
            if index <= 0:
                self.log.line("WRN", f"bytes fuera de trama descartados: {hexs(bytes(buffer))}")
                return (0, None)
            del buffer[:index]
        if len(buffer) < 2:
            return None
        function = buffer[1]
        if function == FUNC_READ_HOLDING:
            if len(buffer) < 8:
                return None
            return (8, self._handle_read(bytes(buffer[:8])))
        if function == FUNC_WRITE_SINGLE:
            if len(buffer) < 8:
                return None
            return (8, self._handle_write_single(bytes(buffer[:8])))
        if function == FUNC_WRITE_MULTIPLE:
            if len(buffer) < 7:
                return None
            count = int.from_bytes(buffer[4:6], "big")
            length = 9 + 2 * count
            if len(buffer) < length:
                return None
            return (length, self._handle_write_multiple(bytes(buffer[:length])))
        # funcion no soportada: excepcion 01 (funcion ilegal)
        self.log.line("WRN", f"funcion no soportada 0x{function:02X}")
        return (8, self._exception(function, 0x01))

    def _check_crc(self, frame: bytes) -> bool:
        body, crc = frame[:-2], frame[-2:]
        expected = crc16_modbus(body)
        received = crc[0] | (crc[1] << 8)
        if expected != received:
            self.log.line("WRN", f"CRC invalido en {hexs(frame)} (esperado {expected:04X}, recibido {received:04X})")
            return False
        return True

    def _handle_read(self, frame: bytes) -> bytes | None:
        self.log.line("RX ", f"{hexs(frame)}  <- leer registro")
        if not self._check_crc(frame):
            return None
        register = int.from_bytes(frame[2:4], "big")
        count = int.from_bytes(frame[4:6], "big")
        self.log.line("SIM", f"consulta de registro 0x{register:04X} x{count}  [{self.machine.snapshot()}]")
        payload = bytearray()
        for offset in range(count):
            payload += self.machine.read_register(register + offset).to_bytes(2, "big")
        response = append_crc(bytes((ADDRESS, FUNC_READ_HOLDING, 2 * count)) + bytes(payload))
        self.log.line("TX ", f"{hexs(response)}  -> estado (0x{int.from_bytes(payload[:2], 'big'):04X})")
        return response

    def _handle_write_single(self, frame: bytes) -> bytes | None:
        self.log.line("RX ", f"{hexs(frame)}  <- write single")
        if not self._check_crc(frame):
            return None
        register = int.from_bytes(frame[2:4], "big")
        value = int.from_bytes(frame[4:6], "big")
        if register == REG_RESET_LIFT:
            self.machine.reset_lift()
        elif register == REG_TOY_POSITION or register == REG_TOY_POSITION_LEGACY:
            self.machine.to_y(value)
        elif register in SDK_OTHER_WRITES:
            self.log.line("SIM", f"{SDK_OTHER_WRITES[register]} (registro 0x{register:04X} = {value}) sin efecto en el simulador")
        else:
            self.log.line("SIM", f"escritura en registro 0x{register:04X} = 0x{value:04X} (sin efecto)")
        return frame

    def _handle_write_multiple(self, frame: bytes) -> bytes | None:
        self.log.line("RX ", f"{hexs(frame)}  <- write multiple")
        if not self._check_crc(frame):
            return None
        start = int.from_bytes(frame[2:4], "big")
        count = int.from_bytes(frame[4:6], "big")
        data = frame[7:7 + 2 * count]
        if start == REG_CELL_SELECT and count >= 1:
            # El payload de seleccion de celda empieza con el id de celda (celda - 10).
            self.machine.select_cell(data[0] + 10)
        elif start == REG_Y_POINTS and count >= 1:
            points = [int.from_bytes(data[2 * i:2 * i + 2], "big") for i in range(count)]
            self.machine.set_y_points(points)
        elif start in SDK_OTHER_WRITES:
            self.log.line("SIM", f"{SDK_OTHER_WRITES[start]} (0x{start:04X} x{count}) sin efecto en el simulador")
        else:
            self.log.line("SIM", f"escritura multiple en 0x{start:04X} x{count} (sin efecto)")
        return append_crc(frame[:6])

    def _exception(self, function: int, code: int) -> bytes:
        return append_crc(bytes((ADDRESS, function | 0x80, code)))


# ---------------------------------------------------------------------------
# Canal de control (puerto TCP 9101 + stdin)
# ---------------------------------------------------------------------------

class Control:
    def __init__(self, machine: Machine, log: Log, port: int):
        self.machine = machine
        self.log = log
        self.port = port

    def start(self) -> None:
        server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            server.bind(("127.0.0.1", self.port))
        except OSError as exc:
            self.log.line("WRN", f"canal de control no disponible en {self.port}: {exc}")
            return
        server.listen(2)
        self.log.line("NET", f"canal de control en 127.0.0.1:{self.port} (escribe 'help')")

        def accept_loop() -> None:
            while True:
                try:
                    conn, _ = server.accept()
                except OSError:
                    return
                threading.Thread(target=self._serve, args=(conn,), daemon=True).start()

        threading.Thread(target=accept_loop, daemon=True).start()

    def _serve(self, conn: socket.socket) -> None:
        with conn:
            conn.sendall(self.help_text().encode("utf-8"))
            pending = b""
            while True:
                try:
                    chunk = conn.recv(1024)
                except OSError:
                    return
                if not chunk:
                    return
                pending += chunk
                while b"\n" in pending:
                    line, pending = pending.split(b"\n", 1)
                    answer = self.execute(line.decode("utf-8", "replace").strip())
                    conn.sendall((answer + "\n").encode("utf-8"))

    def stdin_loop(self) -> None:
        for raw in sys.stdin:
            answer = self.execute(raw.strip())
            self.log.line("CTL", answer)

    def help_text(self) -> str:
        return (
            "comandos:\n"
            "  status                     estado actual de la maquina simulada\n"
            "  calib                      estado del bloque de calibracion (Y, sensores, falla)\n"
            "  scenario <nombre>          normal | stuck | crushed | driver0000 | nopickup | transitions\n"
            "  speed <0.5-1.5>            acelera o frena la secuencia\n"
            "  cell <10-68>               fuerza un ciclo de dispensacion\n"
            "  fault <codigo>             fuerza una falla del eje Y (0 = sin falla)\n"
            "  ypoints <10 valores>       carga los puntos Y de la placa (ej: 0,100,200,...)\n"
            "  reset                      vuelve a reposo\n"
            "  help                       esta ayuda\n"
        )

    def execute(self, command: str) -> str:
        if not command:
            return "comando vacio"
        parts = command.split()
        name, args = parts[0].lower(), parts[1:]
        self.log.line("CTL", f"comando: {command}")
        try:
            if name == "help":
                return self.help_text()
            if name == "status":
                return self.machine.snapshot()
            if name == "calib":
                return self.machine.calibration_snapshot()
            if name == "scenario" and args:
                self.machine.set_scenario(args[0].lower())
                return f"escenario = {args[0].lower()}"
            if name == "speed" and args:
                self.machine.set_speed(float(args[0]))
                return f"velocidad = {float(args[0]):.2f}x"
            if name == "cell" and args:
                cell = int(args[0])
                if not 10 <= cell <= 68:
                    return "celda fuera de rango (10..68)"
                self.machine.select_cell(cell)
                return f"ciclo forzado para celda {cell}"
            if name == "fault" and args:
                code = int(args[0])
                if not 0 <= code <= 255:
                    return "codigo de falla fuera de rango (0..255)"
                self.machine.set_fault(code)
                return f"falla de plataforma = {code}"
            if name == "ypoints" and args:
                values = [int(value) for value in " ".join(args).replace(",", " ").split()]
                if len(values) != 10:
                    return f"se esperan 10 valores Y (recibidos {len(values)})"
                self.machine.set_y_points(values)
                return f"puntos Y = {values}"
            if name == "reset":
                self.machine.reset()
                return "estado reiniciado"
            if name == "quit":
                self.machine.reset()
                return "usa Ctrl+C para detener el servicio"
            return f"comando desconocido: {name} (escribe 'help')"
        except ValueError as exc:
            return f"error: {exc}"


# ---------------------------------------------------------------------------
# Autoprueba del protocolo
# ---------------------------------------------------------------------------

def selftest() -> int:
    log = Log()
    failures: list[str] = []

    def check(label: str, condition: bool, detail: str = "") -> None:
        if condition:
            log.line("OK ", label)
        else:
            failures.append(label)
            log.line("ERR", f"{label} {detail}")

    log.line("TEST", "=== 1) CRC contra las tramas reales de la APK ===")
    check("POLL_DRIVER_STATUS = 010300030001740A",
          hexs(append_crc(bytes.fromhex("010300030001"))) == "01 03 00 03 00 01 74 0A")
    check("POLL_IO_STATUS = 0103000B0001F5C8",
          hexs(append_crc(bytes.fromhex("0103000B0001"))) == "01 03 00 0B 00 01 F5 C8")
    check("POLL_IO_STATUS x4 = 0103000B000435CB",
          hexs(append_crc(bytes.fromhex("0103000B0004"))) == "01 03 00 0B 00 04 35 CB")

    log.line("TEST", "=== 2) tramas generadas por CommandSet.kt ===")
    select37 = "01100001000204" + f"{37 - 10:02X}" + "030101"
    select37_frame = append_crc(bytes.fromhex(select37))
    check("SELECT celda 37 -> CRC de ida y vuelta valido",
          crc16_modbus(select37_frame[:-2]) == (select37_frame[-2] | (select37_frame[-1] << 8)),
          hexs(select37_frame))
    log.line("SIM", f"frame SELECT celda 37 del simulador: {hexs(select37_frame)}")
    reset_frame = append_crc(bytes.fromhex("010610020001"))
    check("ResetLift -> CRC de ida y vuelta valido",
          crc16_modbus(reset_frame[:-2]) == (reset_frame[-2] | (reset_frame[-1] << 8)),
          hexs(reset_frame))

    log.line("TEST", "=== 3) secuencia completa contra el servidor TCP ===")
    machine = Machine(log, scenario="normal", speed=1.0)
    server = BoardServer(machine, log, "127.0.0.1", 19100, latency_ms=5.0)
    server.start()
    time.sleep(0.2)

    client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    client.settimeout(5.0)
    client.connect(("127.0.0.1", 19100))

    def exchange(frame_hex: str) -> bytes:
        client.sendall(bytes.fromhex(frame_hex))
        return client.recv(64)

    def reader_for(sock: socket.socket):
        def read_register(register: int) -> int:
            frame = append_crc(bytes((ADDRESS, FUNC_READ_HOLDING)) + register.to_bytes(2, "big") + b"\x00\x01")
            sock.sendall(frame)
            response = sock.recv(64)
            return int.from_bytes(response[3:5], "big")
        return read_register

    read_register = reader_for(client)

    driver = read_register(REG_DRIVER_STATUS)
    check("driver inicial = 0x0000", driver == DRIVER_RUNNING, f"leido 0x{driver:04X}")
    io_idle = read_register(REG_IO_STATUS)
    check("io inicial = 0x00 (reposo)", io_idle == IO_IDLE, f"leido 0x{io_idle:02X}")

    # SELECT celda 37 -> 0x1B (37-10)
    select = hexs(append_crc(bytes.fromhex("01100001000204" + f"{37 - 10:02X}" + "030101"))).replace(" ", "")
    response = exchange(select)
    check("respuesta de SELECT = eco Modbus 0x10", response[:2] == bytes((ADDRESS, FUNC_WRITE_MULTIPLE)), hexs(response))
    time.sleep(0.1)
    check("celda decodificada del payload = 37", machine.cell == 37, f"celda={machine.cell}")

    seen: list[tuple[str, int]] = []
    deadline = time.time() + 22
    last_io = last_driver = None
    while time.time() < deadline:
        io = read_register(REG_IO_STATUS)
        drv = read_register(REG_DRIVER_STATUS)
        if io != last_io:
            seen.append(("io", io))
            last_io = io
        if drv != last_driver:
            seen.append(("driver", drv))
            last_driver = drv
        if last_io == IO_SECOND_CLICK and last_driver == DRIVER_DONE:
            break
        time.sleep(0.25)

    expected_io = [IO_WHITE_DOOR_OPENING, IO_PLATFORM_UP, IO_PLATFORM_DOWN, IO_WHITE_DOOR_CLOSING,
                   IO_DOOR_OPEN_FIRST_TIME, IO_AFTER_FIRST_CLICK, IO_DOOR_CLOSED_NO_PROD, IO_SECOND_CLICK]
    observed_io = [value for kind, value in seen if kind == "io"]
    check("secuencia de IO completa y en orden",
          observed_io == expected_io, f"observado {[f'{v:02X}' for v in observed_io]}")
    try:
        driver_done_index = seen.index(("driver", DRIVER_DONE))
    except ValueError:
        driver_done_index = -1
    try:
        door_closing_index = seen.index(("io", IO_WHITE_DOOR_CLOSING))
    except ValueError:
        door_closing_index = -1
    check("driver DONE llega despues de 0xC2 (la APK no lo lee como atascado)",
          driver_done_index > door_closing_index > -1,
          f"done_idx={driver_done_index} closing_idx={door_closing_index}")

    log.line("TEST", "=== 4) CRC invalido no genera respuesta ===")
    bad = bytearray(append_crc(bytes.fromhex("010300030001")))
    bad[-1] ^= 0xFF
    client.sendall(bytes(bad))
    client.settimeout(1.0)
    try:
        leftover = client.recv(64)
    except socket.timeout:
        leftover = b""
    check("trama con CRC roto ignorada", leftover == b"", hexs(leftover))

    log.line("TEST", "=== 5) recuperacion ToY(0) -> senal de base ===")
    client.settimeout(8.0)
    exchange(hexs(append_crc(bytes.fromhex("010610100000"))).replace(" ", ""))
    time.sleep(9.0 / machine.speed)
    io_base = read_register(REG_IO_STATUS)
    check("ToY(0) deja IO en senal de base valida para la APK",
          io_base in BASE_SIGNALS, f"leido 0x{io_base:02X}")

    client.close()
    server.stop()

    log.line("TEST", "=== 6) semantica de cada escenario contra las reglas de la APK ===")
    windows = {"normal": 22, "stuck": 8, "crushed": 21, "driver0000": 5, "nopickup": 16, "transitions": 24}
    select_bytes = bytes.fromhex(select)

    for index, scenario in enumerate(SCENARIOS):
        machine_s = Machine(log, scenario=scenario, speed=1.0)
        server_s = BoardServer(machine_s, log, "127.0.0.1", 19300 + index, latency_ms=2.0)
        server_s.start()
        time.sleep(0.15)
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(5.0)
        sock.connect(("127.0.0.1", 19300 + index))
        read_s = reader_for(sock)
        sock.sendall(select_bytes)
        sock.recv(64)

        start = time.time()
        events: list[tuple[int, int, int]] = []
        previous: tuple[int, int] | None = None
        while time.time() - start < windows[scenario]:
            io_value = read_s(REG_IO_STATUS)
            driver_value = read_s(REG_DRIVER_STATUS)
            if (io_value, driver_value) != previous:
                events.append((int((time.time() - start) * 1000), io_value, driver_value))
                previous = (io_value, driver_value)
            if driver_value == DRIVER_DONE and io_value == IO_SECOND_CLICK:
                break
            time.sleep(0.2)
        sock.close()
        server_s.stop()

        io_seq: list[int] = []
        last_io = None
        for _, io_value, _ in events:
            if io_value != last_io:
                io_seq.append(io_value)
                last_io = io_value

        def moment_of(kind: str, value: int) -> int | None:
            for ms, io_value, driver_value in events:
                if (kind == "io" and io_value == value) or (kind == "drv" and driver_value == value):
                    return ms
            return None

        c8 = moment_of("io", IO_PLATFORM_DOWN)
        done = moment_of("drv", DRIVER_DONE)
        c2 = moment_of("io", IO_WHITE_DOOR_CLOSING)
        gap = (done - c8) if (done is not None and c8 is not None) else None

        if scenario == "normal":
            check("normal: secuencia de IO completa",
                  io_seq == [IO_WHITE_DOOR_OPENING, IO_PLATFORM_UP, IO_PLATFORM_DOWN,
                             IO_WHITE_DOOR_CLOSING, IO_DOOR_OPEN_FIRST_TIME, IO_AFTER_FIRST_CLICK,
                             IO_DOOR_CLOSED_NO_PROD, IO_SECOND_CLICK],
                  f"{[f'{v:02X}' for v in io_seq]}")
            check("normal: DONE entre 3s y 12s despues de 0xC8 y con 0xC2 visto",
                  gap is not None and 3000 < gap < 12000 and c2 is not None and c2 < done,
                  f"gap={gap}ms c2={c2}")
        elif scenario == "transitions":
            check("transitions: 0x42 y 0x52 informados antes del cierre",
                  IO_TRANSITION_42 in io_seq and IO_TRANSITION_52 in io_seq and io_seq[-1] == IO_SECOND_CLICK,
                  f"{[f'{v:02X}' for v in io_seq]}")
        elif scenario == "stuck":
            check("stuck: DONE dentro de 3s desde 0xC8 (la APK lo lee como atorada)",
                  gap is not None and 0 < gap <= 3000, f"gap={gap}ms")
            check("stuck: no hay 0xC2 ni tramo de retiro (espera accion del operador)",
                  c2 is None and IO_AFTER_FIRST_CLICK not in io_seq, f"{[f'{v:02X}' for v in io_seq]}")
        elif scenario == "crushed":
            check("crushed: DONE pasado 12s de 0xC8 y sin 0xC2 (producto aplastado)",
                  gap is not None and gap > 12000 and c2 is None, f"gap={gap}ms c2={c2}")
            check("crushed: la plataforma queda en 0xC8", io_seq[-1] == IO_PLATFORM_DOWN,
                  f"{[f'{v:02X}' for v in io_seq]}")
        elif scenario == "driver0000":
            check("driver0000: driver nunca sale de 0x0000",
                  all(driver_value == DRIVER_RUNNING for _, _, driver_value in events),
                  f"{[(ms, f'{d:04X}') for ms, _, d in events]}")
            check("driver0000: no hay progreso de IO (la APK corta la dispensacion)",
                  all(io_value == IO_IDLE for _, io_value, _ in events),
                  f"{[f'{v:02X}' for v in io_seq]}")
        elif scenario == "nopickup":
            check("nopickup: DONE correcto y sin avance de retiro (el cliente no retira)",
                  gap is not None and 3000 < gap < 12000 and io_seq[-1] == IO_DOOR_OPEN_FIRST_TIME
                  and IO_SECOND_CLICK not in io_seq[1:],
                  f"{[f'{v:02X}' for v in io_seq]}")

    log.line("TEST", "=== 7) bloque de calibracion (lo que consume el Calibrador) ===")
    machine_c = Machine(log, scenario="normal", speed=1.0)
    server_c = BoardServer(machine_c, log, "127.0.0.1", 19400, latency_ms=2.0)
    server_c.start()
    time.sleep(0.15)
    sock_c = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock_c.settimeout(5.0)
    sock_c.connect(("127.0.0.1", 19400))
    read_c = reader_for(sock_c)

    def read_many(register: int, count: int) -> list[int]:
        frame = append_crc(bytes((ADDRESS, FUNC_READ_HOLDING)) + register.to_bytes(2, "big") + count.to_bytes(2, "big"))
        sock_c.sendall(frame)
        response = sock_c.recv(128)
        return [int.from_bytes(response[3 + 2 * i:5 + 2 * i], "big") for i in range(count)]

    check("sensores de caida normales (DS = 1)", read_c(REG_DROP_STATUS) == 1)
    check("estado Y en reposo y sin falla (YS = 0)", read_c(REG_Y_STATUS) == 0)
    check("posiciones Y por defecto (YP x10)",
          read_many(REG_Y_POSITIONS, 10) == [row * 100 for row in range(10)])

    new_points = [11, 22, 33, 44, 55, 66, 77, 88, 99, 110]
    payload = b"".join(value.to_bytes(2, "big") for value in new_points)
    write_points = append_crc(
        bytes((ADDRESS, FUNC_WRITE_MULTIPLE)) + REG_Y_POINTS.to_bytes(2, "big") + (10).to_bytes(2, "big") + bytes((20,)) + payload
    )
    sock_c.sendall(write_points)
    sock_c.recv(64)
    time.sleep(0.1)
    check("SeYPos (0x2002 x10) carga la tabla Y", read_many(REG_Y_POSITIONS, 10) == new_points,
          f"{read_many(REG_Y_POSITIONS, 10)}")

    to_y_frame = append_crc(bytes((ADDRESS, FUNC_WRITE_SINGLE)) + REG_TOY_POSITION.to_bytes(2, "big") + (1234).to_bytes(2, "big"))
    sock_c.sendall(to_y_frame)
    sock_c.recv(64)
    time.sleep(0.2)
    check("ToY (0x1007) mueve la plataforma a la posicion pedida", read_c(REG_Y_POSITIONS) == 1234,
          f"Y0={read_c(REG_Y_POSITIONS)}")

    machine_c.set_fault(3)
    check("fault <codigo> se refleja en el estado Y", read_c(REG_Y_STATUS) == 3, f"YS={read_c(REG_Y_STATUS)}")

    machine_c.set_y_points([1, 2, 3, 4, 5, 6, 7, 8, 9, 10])
    check("ypoints del canal de control actualiza la tabla", read_many(REG_Y_POSITIONS, 10) == [1, 2, 3, 4, 5, 6, 7, 8, 9, 10])

    sock_c.close()
    server_c.stop()

    log.line("TEST", f"total de fallas: {len(failures)}")
    for failure in failures:
        log.line("ERR", f"fallo: {failure}")
    return 1 if failures else 0


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------

def main() -> int:
    parser = argparse.ArgumentParser(description="Simulador de placa de vending para la APK apk-nativa-vending")
    parser.add_argument("--host", default="127.0.0.1", help="interfaz de escucha del puerto serie simulado")
    parser.add_argument("--port", type=int, default=9100, help="puerto TCP que la APK usara como puerto serie")
    parser.add_argument("--control-port", type=int, default=9101, help="puerto TCP del canal de control")
    parser.add_argument("--scenario", default="normal", choices=SCENARIOS, help="escenario inicial")
    parser.add_argument("--speed", type=float, default=1.0, help="velocidad de la secuencia (0.5 a 1.5)")
    parser.add_argument("--log", default=None, help="archivo adicional donde duplicar el log")
    parser.add_argument("--no-stdin", action="store_true", help="no leer comandos por consola")
    parser.add_argument("--selftest", action="store_true", help="ejecuta la autoprueba del protocolo y termina")
    args = parser.parse_args()

    if args.selftest:
        return selftest()

    log = Log(args.log)
    log.line("SIM", "=" * 72)
    log.line("SIM", "Simulador de placa de vending (ZhongDa / Modbus RTU)")
    log.line("SIM", f"APK: compilar con -PvendingSerial=tcp y apuntar a 10.0.2.2:{args.port}")
    log.line("SIM", f"escenario inicial: {args.scenario} | velocidad: {args.speed:.2f}x")
    log.line("SIM", "=" * 72)

    machine = Machine(log, scenario=args.scenario, speed=args.speed)
    server = BoardServer(machine, log, args.host, args.port)
    server.start()
    control = Control(machine, log, args.control_port)
    control.start()
    log.line("SIM", "listo. Escribe 'help' + Enter para ver los comandos.")
    if not args.no_stdin:
        threading.Thread(target=control.stdin_loop, daemon=True).start()

    try:
        while True:
            time.sleep(0.5)
    except KeyboardInterrupt:
        log.line("SIM", "detenido por el usuario")
    finally:
        server.stop()
    return 0


if __name__ == "__main__":
    sys.exit(main())
