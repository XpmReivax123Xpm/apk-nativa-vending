#!/usr/bin/env python3
"""
Cliente del canal de control del simulador de placa.

Permite cambiar el escenario o consultar el estado sin tocar la consola del
servicio (que puede estar minimizada o corriendo en segundo plano).

    python simctl.py status
    python simctl.py scenario stuck
    python simctl.py scenario normal speed 1.5
    python simctl.py                      # modo interactivo
"""

from __future__ import annotations

import argparse
import socket
import sys

HOST = "127.0.0.1"
PORT = 9101


def send(commands: list[str], host: str, port: int) -> int:
    command_line = " ".join(commands)
    try:
        with socket.create_connection((host, port), timeout=5) as conn:
            conn.settimeout(2)
            conn.recv(4096)  # saludo/ayuda inicial
            conn.sendall((command_line + "\n").encode("utf-8"))
            try:
                answer = conn.recv(4096).decode("utf-8", "replace").strip()
            except socket.timeout:
                answer = "(sin respuesta)"
            print(f"> {command_line}\n{answer}")
    except OSError as exc:
        print(f"No se pudo hablar con el simulador en {host}:{port}: {exc}", file=sys.stderr)
        print("Verifica que el servicio este corriendo (start-sim.bat).", file=sys.stderr)
        return 1
    return 0


def interactive(host: str, port: int) -> int:
    try:
        with socket.create_connection((host, port), timeout=5) as conn:
            conn.settimeout(2)
            print(conn.recv(4096).decode("utf-8", "replace").strip())
            while True:
                try:
                    command = input("sim> ").strip()
                except (EOFError, KeyboardInterrupt):
                    print()
                    return 0
                if not command:
                    continue
                conn.sendall((command + "\n").encode("utf-8"))
                try:
                    print(conn.recv(4096).decode("utf-8", "replace").strip())
                except socket.timeout:
                    print("(sin respuesta)")
    except OSError as exc:
        print(f"No se pudo hablar con el simulador en {host}:{port}: {exc}", file=sys.stderr)
        return 1


def main() -> int:
    parser = argparse.ArgumentParser(description="Envia comandos al canal de control del simulador")
    parser.add_argument("commands", nargs="*", help="comandos a enviar (status, scenario <nombre>, speed <x>, cell <n>, reset)")
    parser.add_argument("--host", default=HOST)
    parser.add_argument("--port", type=int, default=PORT)
    args = parser.parse_args()
    if not args.commands:
        return interactive(args.host, args.port)
    return send(args.commands, args.host, args.port)


if __name__ == "__main__":
    sys.exit(main())
