"""Проверяет HTTP-контракт Whisper без скачивания модели."""
import json
import os
import sys
import threading
import unittest
import urllib.request
from http.server import ThreadingHTTPServer
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

sys.path.insert(0, os.path.dirname(__file__))
import whisper_server as server_module


class FakeBackendTest(unittest.TestCase):
    def test_json_endpoints_return_the_same_response_schema(self):
        server_module.ARGS = SimpleNamespace(backend="fake")
        server = ThreadingHTTPServer(("127.0.0.1", 0), server_module.Handler)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            for path in (server_module.TRANSCRIBE_JSON, server_module.TRANSCRIBE_JSON_VAD):
                with self.subTest(path=path):
                    request = urllib.request.Request(
                        f"http://127.0.0.1:{server.server_address[1]}{path}",
                        data=b"\x00\x00" * 16000,
                        headers={"Content-Type": "application/octet-stream"},
                        method="POST",
                    )
                    with urllib.request.urlopen(request, timeout=3) as response:
                        payload = json.loads(response.read())
                    self.assertEqual(set(payload), {"text", "words"})
                    self.assertEqual(payload["text"], "тестовая расшифровка")
                    self.assertEqual(payload["words"][0]["word"], "тестовая")
                    self.assertEqual(payload["words"][0]["start"], 0.0)
        finally:
            server.shutdown()
            server.server_close()

    def test_empty_audio_returns_empty_result(self):
        server_module.ARGS = SimpleNamespace(backend="fake")
        self.assertEqual(server_module.transcribe(b""), ("", []))

    def test_only_vad_endpoint_enables_filtering(self):
        server_module.ARGS = SimpleNamespace(backend="fake")
        server = ThreadingHTTPServer(("127.0.0.1", 0), server_module.Handler)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        body = b"\x00\x00"
        try:
            with patch.object(server_module, "transcribe", return_value=("", [])) as transcribe:
                for path in (server_module.TRANSCRIBE_JSON, server_module.TRANSCRIBE_JSON_VAD):
                    request = urllib.request.Request(
                        f"http://127.0.0.1:{server.server_address[1]}{path}",
                        data=body,
                        headers={"Content-Type": "application/octet-stream"},
                        method="POST",
                    )
                    with urllib.request.urlopen(request, timeout=3) as response:
                        self.assertEqual(response.status, 200)
                self.assertEqual(
                    [call.kwargs["vad_filter"] for call in transcribe.call_args_list],
                    [False, True],
                )
        finally:
            server.shutdown()
            server.server_close()

    def test_model_receives_vad_only_when_requested(self):
        server_module.ARGS = SimpleNamespace(backend="faster-whisper", language="ru", beam_size=5)
        server_module.MODEL = MagicMock()
        word = SimpleNamespace(word=" речь", start=0.1, end=0.3, probability=0.9)
        segment = SimpleNamespace(text="речь", words=[word])
        server_module.MODEL.transcribe.return_value = ([segment], None)
        audio = SimpleNamespace(size=1)

        with patch.object(server_module, "pcm_to_float", return_value=audio), \
                patch.dict(sys.modules, {"numpy": MagicMock()}):
            server_module.transcribe(b"\x00\x00")
            default_options = server_module.MODEL.transcribe.call_args.kwargs
            self.assertFalse(default_options["vad_filter"])
            self.assertNotIn("vad_parameters", default_options)

            server_module.MODEL.transcribe.reset_mock()
            server_module.transcribe(b"\x00\x00", vad_filter=True)
            vad_options = server_module.MODEL.transcribe.call_args.kwargs
            self.assertTrue(vad_options["vad_filter"])
            self.assertEqual(vad_options["vad_parameters"], server_module.VAD_PARAMETERS)


if __name__ == "__main__":
    unittest.main()
