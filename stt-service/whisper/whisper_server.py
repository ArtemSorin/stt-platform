#!/usr/bin/env python3
"""Локальный HTTP-сервер Whisper (faster-whisper) для stt-service.

Контракт (см. docs/api/whisper-helper.openapi.yaml):
  GET  /                  -> "ok"
  POST /transcribe        -> текст UTF-8
  POST /transcribe-json   -> {"text": "...", "words": [{"word","start","end","confidence"}]}
Тело запроса: сырой PCM 16 кГц, 16 бит, little-endian, моно; последний байт нечётной длины отбрасывается.

Модель: имя из faster-whisper (small, large-v3-turbo, ...) либо путь к модели в формате CTranslate2,
например сконвертированная coriollon/whisper-large-v3-turbo-russian-codeswitch:
  ct2-transformers-converter --model coriollon/whisper-large-v3-turbo-russian-codeswitch \
      --output_dir models/whisper-ru-ct2 --quantization int8
"""
import argparse
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Lock

MODEL = None
LOCK = Lock()  # одна модель — один запрос за раз
ARGS = None


def pcm_to_float(body: bytes):
    import numpy as np

    if len(body) % 2:
        body = body[:-1]
    return np.frombuffer(body, dtype="<i2").astype(np.float32) / 32768.0


def transcribe(body: bytes):
    if ARGS.backend == "fake":
        sample_count = len(body) // 2
        if sample_count == 0:
            return "", []
        duration = sample_count / 16000
        return "тестовая расшифровка", [{"word": "тестовая", "start": 0.0,
                                         "end": round(min(duration, 0.4), 3), "confidence": 1.0}]

    import numpy as np

    audio = pcm_to_float(body)
    if audio.size == 0:
        return "", []
    with LOCK:
        segments, _ = MODEL.transcribe(
            audio, language=ARGS.language, beam_size=ARGS.beam_size,
            word_timestamps=True, vad_filter=False, condition_on_previous_text=False)
        words, parts = [], []
        for segment in segments:
            parts.append(segment.text.strip())
            for w in segment.words or []:
                words.append({"word": w.word.strip(), "start": round(w.start, 3),
                              "end": round(w.end, 3), "confidence": round(float(w.probability), 3)})
    return " ".join(p for p in parts if p).strip(), words


class Handler(BaseHTTPRequestHandler):
    # заголовки и тело — одним пакетом, без Nagle: иначе каждый ответ стоит лишние ~40 мс
    wbufsize = -1
    disable_nagle_algorithm = True

    def _send(self, code, body: bytes, content_type: str):
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/":
            self._send(200, b"ok", "text/plain; charset=utf-8")
        else:
            self._send(404, b"not found", "text/plain; charset=utf-8")

    def do_POST(self):
        if self.path not in ("/transcribe", "/transcribe-json"):
            self._send(404, b"not found", "text/plain; charset=utf-8")
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            text, words = transcribe(self.rfile.read(length))
            if self.path == "/transcribe":
                self._send(200, text.encode("utf-8"), "text/plain; charset=utf-8")
            else:
                payload = json.dumps({"text": text, "words": words}, ensure_ascii=False)
                self._send(200, payload.encode("utf-8"), "application/json; charset=utf-8")
        except Exception as error:  # noqa: BLE001 — контракт: 500 с текстом исключения
            self._send(500, str(error).encode("utf-8"), "text/plain; charset=utf-8")

    def log_message(self, fmt, *args):
        sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))


def main():
    global MODEL, ARGS
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=9000)
    parser.add_argument("--backend", choices=("faster-whisper", "fake"), default="faster-whisper",
                        help="fake отвечает тестовой расшифровкой без загрузки модели")
    parser.add_argument("--model", default="small")
    parser.add_argument("--device", default="cpu")
    parser.add_argument("--compute-type", default="int8")
    parser.add_argument("--cpu-threads", type=int, default=2)
    parser.add_argument("--language", default="ru")
    parser.add_argument("--beam-size", type=int, default=5)
    ARGS = parser.parse_args()
    print(f"Загрузка модели {ARGS.model} ({ARGS.device}, {ARGS.compute_type})...", flush=True)
    if ARGS.backend == "faster-whisper":
        from faster_whisper import WhisperModel
        MODEL = WhisperModel(ARGS.model, device=ARGS.device, compute_type=ARGS.compute_type,
                             cpu_threads=ARGS.cpu_threads)
    server = ThreadingHTTPServer((ARGS.host, ARGS.port), Handler)
    print(f"Whisper слушает http://{ARGS.host}:{ARGS.port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
