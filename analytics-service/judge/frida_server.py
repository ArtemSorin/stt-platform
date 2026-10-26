#!/usr/bin/env python3
"""HTTP-сервер модели FRIDA-Decisions для analytics-service.

Модель грузится один раз. Запросы к ней идут строго по одному, но с приоритетами: «высокий» (решение
«уточнить или дальше», X-Priority: high) обгоняет «низкий» (фоновая оценка для отчёта, X-Priority: low).

  GET  /          -> ok
  GET  /status    -> бэкенд, модель, счётчики, задержки
  POST /judge     -> тело: запрос FRIDA-Decisions {"state", "questions"}; ответ: ответ модели {"answers", "margins", "usage"}
  POST /judge-batch -> список запросов -> список ответов
  POST /check     -> без запуска модели: размер запроса в токенах и инструкции, не влезающие в лимит

Бэкенды (--backend):
  torch  PyTorch, GPU (bf16) если есть, иначе CPU (fp32); точный, с кэшем текста между вопросами
  onnx   int8 ONNX на CPU без torch; в ~2,5 раза быстрее torch на CPU, но не побитово точный
  heuristic  НЕ модель: подсчёт общих слов вместо логитов. Только чтобы проверить порты и протокол без весов.

Установка:  pip install -r requirements.txt   (в файле выбран бэкенд)
Запуск:     python judge/frida_server.py --backend torch --device cuda
"""
import argparse
import json
import logging
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

log = logging.getLogger("frida_server")

WARMUP = {
    "state": "Вопрос: что такое JVM? Ответ кандидата: виртуальная машина, исполняет байткод.",
    "questions": {
        "full": {"type": "noul", "instructions": "Кандидат сообщает: «JVM исполняет байткод»?"},
        "level": {"type": "score", "instructions": "Оцени полноту ответа кандидата.",
                  "criteria": ["Нет ответа.", "Минимальный.", "Частичный.", "Хороший.", "Полный."]},
    },
}


class PriorityGate:
    """Пускает к модели по одному; низкий приоритет ждёт, пока есть ожидающие или работающие высокие."""

    def __init__(self):
        self._cond = threading.Condition()
        self._busy = False
        self._high_waiting = 0

    def acquire(self, high: bool):
        with self._cond:
            if high:
                self._high_waiting += 1
                try:
                    while self._busy:
                        self._cond.wait()
                finally:
                    self._high_waiting -= 1
            else:
                while self._busy or self._high_waiting > 0:
                    self._cond.wait()
            self._busy = True

    def release(self):
        with self._cond:
            self._busy = False
            self._cond.notify_all()


class Service:
    def __init__(self, judge, backend: str, model: str):
        self.judge, self.backend, self.model = judge, backend, model
        self.gate = PriorityGate()
        self.requests = 0
        self.errors = 0
        self.total_ms = 0.0
        self.recent = []
        self._lock = threading.Lock()
        self._warned = set()

    def run(self, request: dict, high: bool) -> dict:
        self.gate.acquire(high)
        started = time.perf_counter()
        try:
            self._warn_long_instructions(request)
            response = self.judge(request)
        except Exception:
            with self._lock:
                self.errors += 1
            raise
        finally:
            self.gate.release()
        elapsed = (time.perf_counter() - started) * 1000
        with self._lock:
            self.requests += 1
            self.total_ms += elapsed
            self.recent = (self.recent + [elapsed])[-200:]
        return response

    def run_batch(self, requests: list, high: bool) -> list:
        self.gate.acquire(high)
        try:
            return self.judge.judge_batch(requests)
        finally:
            self.gate.release()

    def status(self) -> dict:
        with self._lock:
            recent = sorted(self.recent)
            p95 = recent[int(len(recent) * 0.95) - 1] if recent else None
            return {
                "backend": getattr(self.judge, "backend", self.backend), "model": self.model,
                "requests": self.requests, "errors": self.errors,
                "avg_ms": round(self.total_ms / self.requests, 1) if self.requests else None,
                "p95_ms": round(p95, 1) if p95 is not None else None,
                "state_max_tokens": getattr(self.judge, "state_max", None),
            }

    def check(self, request: dict) -> dict:
        """Размеры в токенах без запуска модели; инструкции длиннее лимита обрезаются справа вместе с суффиксом."""
        parsed, candidates, tok = self.judge.compile(request)
        cap = self.judge.config.instruction_max_tokens
        over = sorted({c.question_id for c in candidates if self.judge.text.count(c.instruction) > cap})
        return {"tokens": self.judge.token_counts(request), "state_max_tokens": self.judge.state_max,
                "instruction_cap": cap, "instructions_over_cap": over}

    def _warn_long_instructions(self, request: dict):
        """Один раз на инструкцию предупреждаем, что она не помещается в лимит токенов модели."""
        compile_fn = getattr(self.judge, "compile", None)
        text = getattr(self.judge, "text", None)
        if compile_fn is None or text is None:
            return
        try:
            _, candidates, _ = compile_fn(request)
            cap = self.judge.config.instruction_max_tokens
            for c in candidates:
                key = c.instruction
                if key in self._warned or len(self._warned) > 500:
                    continue
                self._warned.add(key)
                if text.count(key) > cap:
                    log.warning("Инструкция вопроса %s длиннее %d токенов и будет обрезана: %.80s…", c.question_id, cap, key)
        except Exception:  # проверка вспомогательная и не должна ломать запрос
            pass


def make_handler(service: Service):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"
        # заголовки и тело — одним пакетом, без Nagle: иначе каждый ответ стоит лишние ~40 мс (delayed ACK клиента)
        wbufsize = -1
        disable_nagle_algorithm = True

        def _send(self, code: int, payload, content_type="application/json; charset=utf-8"):
            body = payload if isinstance(payload, bytes) else json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            if self.path == "/":
                self._send(200, b"ok", "text/plain; charset=utf-8")
            elif self.path == "/status":
                self._send(200, service.status())
            else:
                self._send(404, {"error": {"type": "not_found", "message": self.path}})

        def do_POST(self):
            try:
                length = int(self.headers.get("Content-Length", "0"))
                body = json.loads(self.rfile.read(length).decode("utf-8"))
                high = self.headers.get("X-Priority", "high").lower() != "low"
                if self.path == "/judge":
                    self._send(200, service.run(body, high))
                elif self.path == "/judge-batch":
                    self._send(200, service.run_batch(body, high))
                elif self.path == "/check":
                    self._send(200, service.check(body))
                else:
                    self._send(404, {"error": {"type": "not_found", "message": self.path}})
            except json.JSONDecodeError as error:
                self._send(400, {"error": {"type": "invalid_json", "message": str(error)}})
            except Exception as error:  # noqa: BLE001
                payload = getattr(error, "payload", None)
                if callable(payload):                       # frida_decisions.RequestError
                    self._send(getattr(error, "status", 400), payload())
                else:
                    log.exception("Ошибка модели")
                    self._send(500, {"error": {"type": "internal", "message": str(error)}})

        def log_message(self, fmt, *args):
            log.debug("%s - %s", self.address_string(), fmt % args)

    return Handler


def load_judge(args):
    if args.backend == "heuristic":
        log.warning("=" * 70)
        log.warning("ВНИМАНИЕ: режим heuristic — это НЕ FRIDA-Decisions, оценки не имеют отношения к модели.")
        log.warning("=" * 70)
        from heuristic_backend import HeuristicJudge
        return HeuristicJudge()
    if args.backend == "onnx":
        from frida_decisions import OnnxJudge
        return OnnxJudge.from_pretrained(args.model, state_max=args.state_max, threads=args.threads)
    from frida_decisions import Judge
    return Judge.from_pretrained(args.model, device=args.device, state_max=args.state_max)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=9100)
    parser.add_argument("--backend", choices=("torch", "onnx", "heuristic"), default="torch")
    parser.add_argument("--model", default="ai-forever/FRIDA-Decisions", help="репозиторий Hugging Face или локальная папка")
    parser.add_argument("--device", default=None, help="torch: cuda | cpu (по умолчанию cuda, если есть)")
    parser.add_argument("--threads", type=int, default=None, help="onnx: число потоков CPU")
    parser.add_argument("--state-max", type=int, default=512, help="токенов текста запроса (обучено до 512)")
    args = parser.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

    log.info("Загрузка модели %s (%s)…", args.model, args.backend)
    judge = load_judge(args)
    started = time.perf_counter()
    judge(WARMUP)
    log.info("Прогрев: %.0f мс", (time.perf_counter() - started) * 1000)
    service = Service(judge, args.backend, args.model)
    server = ThreadingHTTPServer((args.host, args.port), make_handler(service))
    log.info("FRIDA-Decisions слушает http://%s:%d", args.host, args.port)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    sys.exit(main())
