"""Проверяет загрузку Vosk через WebSocket в запущенном STT-контейнере."""

import argparse
import base64
import os
import socket
import subprocess
import sys
import time


def open_stt_websocket():
    key = base64.b64encode(os.urandom(16)).decode("ascii")
    request = (
        "GET /ws/stt HTTP/1.1\r\n"
        "Host: 127.0.0.1:8080\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        f"Sec-WebSocket-Key: {key}\r\n"
        "Sec-WebSocket-Version: 13\r\n"
        "Origin: http://127.0.0.1:8080\r\n\r\n"
    )
    with socket.create_connection(("127.0.0.1", 8080), timeout=10) as connection:
        connection.settimeout(10)
        connection.sendall(request.encode("ascii"))
        response = bytearray()
        while b"\r\n\r\n" not in response:
            block = connection.recv(4096)
            if not block:
                raise RuntimeError("STT закрыл соединение до WebSocket handshake")
            response.extend(block)
        status = bytes(response).split(b"\r\n", 1)[0]
        if b" 101 " not in status:
            raise RuntimeError("WebSocket handshake отклонён: " + status.decode("ascii", "replace"))

        # Маскированный Close frame корректно освобождает созданную сессию Vosk.
        payload = (1000).to_bytes(2, "big")
        mask = os.urandom(4)
        masked = bytes(value ^ mask[index % 4] for index, value in enumerate(payload))
        connection.sendall(bytes((0x88, 0x80 | len(payload))) + mask + masked)


def wait_for_vosk(container, timeout_seconds):
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        result = subprocess.run(
            ["docker", "logs", container], capture_output=True, text=True, encoding="utf-8", errors="replace"
        )
        if "STT-клиент подключён" in result.stdout + result.stderr:
            return
        time.sleep(0.5)
    logs = subprocess.run(
        ["docker", "logs", "--tail=100", container], capture_output=True, text=True, encoding="utf-8", errors="replace"
    )
    sys.stderr.write(logs.stdout + logs.stderr)
    raise RuntimeError("Лог не подтвердил создание сессии Vosk")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--container", default="stt-ci", help="имя STT-контейнера")
    parser.add_argument("--timeout", type=int, default=15, help="сколько секунд ждать загрузку Vosk")
    args = parser.parse_args()
    open_stt_websocket()
    wait_for_vosk(args.container, args.timeout)
    print("OK: STT WebSocket подключился и создал сессию Vosk.")


if __name__ == "__main__":
    main()
