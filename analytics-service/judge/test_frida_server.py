"""Тесты сервера без модели: подменяем Judge фиктивным. Запуск:  python -m unittest judge/test_frida_server.py"""
import json
import os
import sys
import threading
import time
import unittest
import urllib.error
import urllib.request
from argparse import Namespace
from http.server import ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(__file__))
import frida_server as fs  # noqa: E402

try:
    from frida_decisions import RequestError, parse_request
except ImportError:  # пакет не установлен — проверки формата пропускаются
    RequestError = parse_request = None


class FakeJudge:
    backend = "fake"
    state_max = 512

    def __init__(self, delay=0.0):
        self.delay, self.calls = delay, []

    def __call__(self, request):
        if parse_request:
            parse_request(request)  # настоящая проверка формата запроса, как у модели
        self.calls.append(request)
        time.sleep(self.delay)
        return {"model": "FRIDA-Decisions", "answers": {}, "usage": {"milliseconds": 1.0}}

    def judge_batch(self, requests):
        return [self(r) for r in requests]


def serve(judge):
    service = fs.Service(judge, "fake", "fake")
    server = ThreadingHTTPServer(("127.0.0.1", 0), fs.make_handler(service))
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server, service, f"http://127.0.0.1:{server.server_address[1]}"


def post(url, body, headers=None):
    req = urllib.request.Request(url, json.dumps(body).encode("utf-8"), {"Content-Type": "application/json", **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read())


REQUEST = {"state": "текст", "questions": {"q": {"type": "noul", "instructions": "Это тест?"}}}


class ServerTest(unittest.TestCase):
    def test_fake_backend_works_without_optional_model_package(self):
        judge = fs.load_judge(Namespace(backend="fake"))
        response = judge(REQUEST)
        self.assertEqual(judge.backend, "fake")
        self.assertEqual(response["answers"]["q"], {"type": "noul", "noul": 0.5})

    def test_judge_status_and_health(self):
        server, service, url = serve(FakeJudge())
        try:
            self.assertEqual(post(url + "/judge", REQUEST)[0], 200)
            with urllib.request.urlopen(url + "/") as r:
                self.assertEqual(r.read(), b"ok")
            with urllib.request.urlopen(url + "/status") as r:
                status = json.loads(r.read())
            self.assertEqual(status["requests"], 1)
            self.assertEqual(status["backend"], "fake")
        finally:
            server.shutdown()

    def test_bad_json_is_400_and_unknown_path_404(self):
        server, _, url = serve(FakeJudge())
        try:
            req = urllib.request.Request(url + "/judge", "{не json".encode("utf-8"), {"Content-Type": "application/json"})
            with self.assertRaises(urllib.error.HTTPError) as ctx:
                urllib.request.urlopen(req)
            self.assertEqual(ctx.exception.code, 400)
            self.assertEqual(post(url + "/nowhere", REQUEST)[0], 404)
        finally:
            server.shutdown()

    @unittest.skipUnless(parse_request, "frida_decisions не установлен")
    def test_invalid_request_is_reported_as_400_with_field(self):
        server, _, url = serve(FakeJudge())
        try:
            status, body = post(url + "/judge", {"state": "x", "questions": {"q": {"type": "score", "instructions": "?"}}})
            self.assertEqual(status, 400)
            self.assertEqual(body["error"]["field"], "questions.q.criteria")
        finally:
            server.shutdown()

    def test_high_priority_overtakes_queued_low_priority(self):
        judge = FakeJudge(delay=0.15)
        server, _, url = serve(judge)
        order = []

        def call(name, priority):
            req = dict(REQUEST, state=name)
            post(url + "/judge", req, {"X-Priority": priority})
            order.append(name)

        try:
            first = threading.Thread(target=call, args=("busy", "low"))
            first.start()
            time.sleep(0.05)                       # модель занята первым запросом
            low = threading.Thread(target=call, args=("low", "low"))
            low.start()
            time.sleep(0.03)
            high = threading.Thread(target=call, args=("high", "high"))
            high.start()
            for t in (first, low, high):
                t.join()
            self.assertEqual(order, ["busy", "high", "low"])
        finally:
            server.shutdown()


class GateTest(unittest.TestCase):
    def test_low_waits_for_waiting_high(self):
        gate = fs.PriorityGate()
        gate.acquire(True)
        events = []

        def worker(name, high):
            gate.acquire(high)
            events.append(name)
            gate.release()

        low = threading.Thread(target=worker, args=("low", False))
        low.start()
        time.sleep(0.05)
        high = threading.Thread(target=worker, args=("high", True))
        high.start()
        time.sleep(0.05)
        gate.release()
        low.join()
        high.join()
        self.assertEqual(events, ["high", "low"])


if __name__ == "__main__":
    unittest.main()
