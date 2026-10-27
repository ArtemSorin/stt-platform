"""Проверяет HTTP-контракт Whisper без скачивания модели."""
import json
import os
import sys
import threading
import unittest
import urllib.request
from http.server import ThreadingHTTPServer
from types import SimpleNamespace

sys.path.insert(0, os.path.dirname(__file__))
import whisper_server as server_module


class FakeBackendTest(unittest.TestCase):
    def test_json_endpoint_returns_deterministic_text_and_word_times(self):
        server_module.ARGS = SimpleNamespace(backend="fake")
        server = ThreadingHTTPServer(("127.0.0.1", 0), server_module.Handler)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            request = urllib.request.Request(
                f"http://127.0.0.1:{server.server_address[1]}/transcribe-json",
                data=b"\x00\x00" * 16000,
                headers={"Content-Type": "application/octet-stream"},
                method="POST",
            )
            with urllib.request.urlopen(request, timeout=3) as response:
                payload = json.loads(response.read())
            self.assertEqual(payload["text"], "тестовая расшифровка")
            self.assertEqual(payload["words"][0]["word"], "тестовая")
            self.assertEqual(payload["words"][0]["start"], 0.0)
        finally:
            server.shutdown()
            server.server_close()

    def test_empty_audio_returns_empty_result(self):
        server_module.ARGS = SimpleNamespace(backend="fake")
        self.assertEqual(server_module.transcribe(b""), ("", []))


if __name__ == "__main__":
    unittest.main()
