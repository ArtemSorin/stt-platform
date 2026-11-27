"""Проверяет контейнер обработчика с S3 и отказами на границах фиксации."""

from __future__ import annotations

import argparse
import hashlib
import http.server
import io
import json
import os
import socket
import struct
import subprocess
import tempfile
import threading
import time
import uuid
import wave
import zipfile

import boto3
from botocore.config import Config


TOKEN = "ci-finalization-test-token-0123456789abcdef"
ACCESS_KEY = "local-test-key"
SECRET_KEY = "local-test-secret"
BUCKET = "interviews"
INTERVIEW_ID = str(uuid.uuid4())


class State:
    """Состояние заглушки аналитики между последовательными запросами обработчика."""

    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.job_state = "pending"
        self.attempt = 0
        self.lease = None
        self.input_manifest = None
        self.result_manifest = None
        self.registered = {}
        self.register_counts = {}
        self.fail_registration_once = True
        self.lose_completion_ack_once = True
        self.score_answers = None
        self.restart_requeued = False
        self.second_claimed = threading.Event()
        self.failure_reported = threading.Event()
        self.failure_count = 0
        self.status_checks = 0
        self.ready = threading.Event()

    def requeue_after_worker_kill(self) -> None:
        with self.lock:
            if self.job_state != "running" or self.attempt != 1:
                raise AssertionError("Контейнер остановлен вне первой аренды")
            self.job_state = "pending"
            self.lease = None
            self.restart_requeued = True

    def resume_after_s3_recovery(self) -> None:
        with self.lock:
            if self.job_state != "retry_wait" or self.failure_count != 1:
                raise AssertionError("Задание не ожидало восстановления S3")
            self.job_state = "pending"


class JsonHandler(http.server.BaseHTTPRequestHandler):
    state: State
    report: bytes

    def log_message(self, _format: str, *_args) -> None:
        return

    def body(self) -> bytes:
        return self.rfile.read(int(self.headers.get("Content-Length", "0")))

    def send(self, status: int, body: bytes = b"", content_type: str = "application/json") -> None:
        try:
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            # Ожидаемо для первого Whisper-запроса, когда его контейнер завершён.
            pass

    def json(self, status: int, value: object) -> None:
        self.send(status, json.dumps(value, ensure_ascii=False).encode(), "application/json")


class AnalyticsHandler(JsonHandler):
    def do_GET(self) -> None:
        if self.headers.get("X-Internal-Token") != TOKEN:
            self.json(403, {"error": "forbidden"})
            return
        if self.path == f"/internal/finalization/{INTERVIEW_ID}/status":
            with self.state.lock:
                self.state.status_checks += 1
                value = {"state": self.state.job_state,
                         "resultManifest": json.dumps(self.state.result_manifest, separators=(",", ":"))
                         if self.state.result_manifest is not None else None}
            self.json(200, value)
        else:
            self.json(404, {"error": "not_found"})

    def do_POST(self) -> None:
        if self.headers.get("X-Internal-Token") != TOKEN:
            self.json(403, {"error": "forbidden"})
            return
        if self.path == "/internal/finalization/claim":
            with self.state.lock:
                if self.state.job_state != "pending":
                    self.send(204)
                    return
                self.state.attempt += 1
                self.state.lease = str(uuid.uuid4())
                self.state.job_state = "running"
                if self.state.attempt == 2:
                    self.state.second_claimed.set()
                value = {"interviewId": INTERVIEW_ID, "snapshot": "{}",
                         "inputManifest": json.dumps(self.state.input_manifest),
                         "resultManifest": json.dumps(self.state.result_manifest, separators=(",", ":"))
                         if self.state.result_manifest is not None else None,
                         "leaseToken": self.state.lease, "leaseUntil": "2099-01-01T00:00:00Z",
                         "attemptCount": self.state.attempt}
            self.json(200, value)
            return
        if self.path == f"/internal/finalization/{INTERVIEW_ID}/renew":
            with self.state.lock:
                valid = self.headers.get("X-Lease-Token") == self.state.lease
            self.send(200 if valid else 409)
            return
        if self.path == f"/internal/finalization/{INTERVIEW_ID}/score-with-report":
            payload = json.loads(self.body())
            with self.state.lock:
                self.state.score_answers = payload["answers"]
            import base64
            self.json(200, {"xlsx": base64.b64encode(self.report).decode(),
                            "report": {"report": {"total": 8, "questions": []},
                                       "transcriptSource": "whisper"}})
            return
        if self.path == f"/internal/finalization/{INTERVIEW_ID}/complete":
            payload = json.loads(self.body())
            with self.state.lock:
                if payload.get("leaseToken") != self.state.lease or self.state.job_state != "running":
                    self.json(409, {"error": "stale_lease"})
                    return
                expected = {item["kind"] for item in self.state.result_manifest["artifacts"]}
                if set(self.state.registered) != expected:
                    self.json(409, {"error": "artifacts_not_registered"})
                    return
                self.state.job_state = "ready"
                self.state.ready.set()
                lose_ack = self.state.lose_completion_ack_once
                self.state.lose_completion_ack_once = False
            # Состояние уже зафиксировано, хотя клиент увидит 503.
            self.send(503 if lose_ack else 200)
            return
        if self.path == f"/internal/finalization/{INTERVIEW_ID}/failure":
            payload = json.loads(self.body())
            with self.state.lock:
                if payload.get("leaseToken") != self.state.lease or self.state.job_state != "running":
                    self.json(409, {"error": "stale_lease"})
                    return
                self.state.lease = None
                # Удерживаем первую ошибку до возврата S3, как это делает retry_wait в PostgreSQL.
                self.state.job_state = "retry_wait" if self.state.failure_count == 0 else "pending"
                self.state.failure_count += 1
                self.state.failure_reported.set()
            self.send(200)
            return
        self.json(404, {"error": "not_found"})

    def do_PUT(self) -> None:
        if self.headers.get("X-Internal-Token") != TOKEN:
            self.json(403, {"error": "forbidden"})
            return
        payload = json.loads(self.body())
        if self.path == f"/internal/finalization/{INTERVIEW_ID}/result":
            with self.state.lock:
                manifest = payload["manifest"]
                if self.state.result_manifest not in (None, manifest):
                    self.json(409, {"error": "manifest_changed"})
                    return
                self.state.result_manifest = manifest
            self.send(200)
            return
        if self.path == f"/internal/archive/interviews/{INTERVIEW_ID}/artifacts":
            with self.state.lock:
                kind = payload["kind"]
                value = (payload["objectKey"], payload["sizeBytes"], payload["sha256"])
                previous = self.state.registered.get(kind)
                if previous not in (None, value):
                    self.json(409, {"error": "artifact_changed"})
                    return
                self.state.registered[kind] = value
                self.state.register_counts[kind] = self.state.register_counts.get(kind, 0) + 1
                lose_ack = kind == "report_xlsx" and self.state.fail_registration_once
                if lose_ack:
                    self.state.fail_registration_once = False
            # Реестр уже записал объект, но первый ответ о регистрации теряется.
            self.send(503 if lose_ack else 200)
            return
        self.json(404, {"error": "not_found"})


class WhisperHandler(JsonHandler):
    entered: threading.Event
    release: threading.Event
    requests: int = 0
    request_lock = threading.Lock()

    def do_POST(self) -> None:
        self.body()
        with self.request_lock:
            type(self).requests += 1
            number = type(self).requests
        if number == 1:
            self.entered.set()
            self.release.wait(90)
        self.json(200, {"words": [{"word": "контрольный", "start": 0.1, "end": 0.4}]})


def free_port() -> int:
    with socket.socket() as server:
        server.bind(("127.0.0.1", 0))
        return server.getsockname()[1]


def xlsx_fixture() -> bytes:
    result = io.BytesIO()
    with zipfile.ZipFile(result, "w", zipfile.ZIP_DEFLATED) as workbook:
        workbook.writestr("[Content_Types].xml", """<?xml version="1.0"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
              <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
              <Default Extension="xml" ContentType="application/xml"/>
              <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
              <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
            </Types>""")
        workbook.writestr("_rels/.rels", """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
            </Relationships>""")
        workbook.writestr("xl/workbook.xml", """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
            xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>
            <sheet name="Отчёт" sheetId="1" r:id="rId1"/></sheets></workbook>""")
        workbook.writestr("xl/_rels/workbook.xml.rels", """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
            </Relationships>""")
        workbook.writestr("xl/worksheets/sheet1.xml", """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
            <row r="1"><c r="A1" t="inlineStr"><is><t>Ожидаемый ответ: контрольный</t></is></c></row>
            </sheetData></worksheet>""")
    return result.getvalue()


def fixture(state: State, s3, directory: str) -> None:
    samples = 16_000
    pcm = b"".join(struct.pack("<hh", 1200 + i % 100, -2400 - i % 100) for i in range(samples))
    audio_path = os.path.join(directory, "interview.wav")
    with wave.open(audio_path, "wb") as audio:
        audio.setnchannels(2)
        audio.setsampwidth(2)
        audio.setframerate(16_000)
        audio.writeframes(pcm)
    with open(audio_path, "rb") as source:
        audio_bytes = source.read()
    events = "\n".join([
        json.dumps({"sessionId": INTERVIEW_ID, "eventSequence": 0, "audioSample": 0,
                    "attemptId": 1, "type": "ready", "questionId": "Q1"}),
        json.dumps({"sessionId": INTERVIEW_ID, "eventSequence": 1, "audioSample": samples,
                    "attemptId": 1, "type": "decision", "questionId": "Q1", "answerId": 1}),
        json.dumps({"sessionId": INTERVIEW_ID, "eventSequence": 2, "audioSample": samples,
                    "attemptId": 1, "type": "journal_complete"}),
    ]) + "\n"
    replay = json.dumps({"schemaVersion": 1, "sessionId": INTERVIEW_ID, "sampleRate": 16_000,
                         "samples": samples, "channels": 2, "testMode": False, "words": [],
                         "roles": [{"start": 0, "end": samples, "role": "Кандидат", "speaker": "candidate-1"}]})
    files = {"audio_wav": audio_bytes, "interview_events_jsonl": events.encode(),
             "replay_inputs_json": replay.encode()}
    state.input_manifest = {}
    for kind, content in files.items():
        key = f"interviews/{INTERVIEW_ID}/{kind}"
        s3.put_object(Bucket=BUCKET, Key=key, Body=content)
        state.input_manifest[kind] = {"kind": kind, "objectKey": key, "sizeBytes": len(content),
                                      "sha256": hashlib.sha256(content).hexdigest()}


def wait_for(predicate, timeout: float, description: str) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(0.25)
    raise TimeoutError(f"Истекло ожидание: {description}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Проверяет восстановление контейнера финализации и повтор публикации.")
    parser.add_argument("--image", default="finalization-worker:ci")
    args = parser.parse_args()

    suffix = uuid.uuid4().hex[:8]
    s3_name = "finalization-s3-" + suffix
    worker_name = "finalization-worker-" + suffix
    network_name = "finalization-test-" + suffix
    s3_port, analytics_port, whisper_port = free_port(), free_port(), free_port()
    token = TOKEN
    state = State()
    report = xlsx_fixture()
    analytics_type = type("TestAnalyticsHandler", (AnalyticsHandler,), {"state": state, "report": report})
    whisper_entered, whisper_release = threading.Event(), threading.Event()
    whisper_type = type("TestWhisperHandler", (WhisperHandler,), {
        "entered": whisper_entered, "release": whisper_release, "requests": 0})
    analytics_server = http.server.ThreadingHTTPServer(("0.0.0.0", analytics_port), analytics_type)
    whisper_server = http.server.ThreadingHTTPServer(("0.0.0.0", whisper_port), whisper_type)
    for server in (analytics_server, whisper_server):
        threading.Thread(target=server.serve_forever, daemon=True).start()

    worker_started = False
    network_created = False
    try:
        subprocess.run(["docker", "network", "create", network_name],
                       check=True, capture_output=True, text=True)
        network_created = True
        subprocess.run([
            "docker", "run", "-d", "--name", s3_name,
            "--network", network_name, "--network-alias", "finalization-s3",
            "-p", f"127.0.0.1:{s3_port}:8333",
            "-e", f"AWS_ACCESS_KEY_ID={ACCESS_KEY}", "-e", f"AWS_SECRET_ACCESS_KEY={SECRET_KEY}",
            "chrislusf/seaweedfs:4.05", "mini", "-dir=/data",
        ], check=True, capture_output=True, text=True)
        s3 = boto3.client("s3", endpoint_url=f"http://127.0.0.1:{s3_port}",
                          aws_access_key_id=ACCESS_KEY, aws_secret_access_key=SECRET_KEY,
                          region_name="us-east-1", config=Config(s3={"addressing_style": "path"}))
        wait_for(lambda: _create_bucket(s3), 60, "SeaweedFS S3")
        with tempfile.TemporaryDirectory(prefix="finalization-worker-test-") as directory:
            fixture(state, s3, directory)
            subprocess.run([
                "docker", "run", "-d", "--name", worker_name, "--network", network_name,
                "--add-host", "host.docker.internal:host-gateway",
                "-e", f"WORKER_ANALYTICS_URL=http://host.docker.internal:{analytics_port}",
                "-e", f"INTERVIEW_EVENTS_TOKEN={token}",
                "-e", "ARCHIVE_S3_ENDPOINT=http://finalization-s3:8333",
                "-e", f"ARCHIVE_S3_BUCKET={BUCKET}", "-e", f"ARCHIVE_S3_ACCESS_KEY={ACCESS_KEY}",
                "-e", f"ARCHIVE_S3_SECRET_KEY={SECRET_KEY}",
                "-e", f"WHISPER_URL=http://host.docker.internal:{whisper_port}", args.image,
            ], check=True, capture_output=True, text=True)
            worker_started = True

            if not whisper_entered.wait(60):
                logs = subprocess.run(["docker", "logs", worker_name], capture_output=True, text=True).stdout
                with state.lock:
                    observed = f"state={state.job_state}, попытка={state.attempt}, ошибок={state.failure_count}"
                raise TimeoutError("Обработчик не дошёл до итогового Whisper (" + observed + "). Логи:\n" + logs[-8000:])
            subprocess.run(["docker", "kill", worker_name], check=True, capture_output=True)
            state.requeue_after_worker_kill()
            subprocess.run(["docker", "start", worker_name], check=True, capture_output=True)
            whisper_release.set()

            if not state.second_claimed.wait(60):
                raise TimeoutError("Обработчик не запросил задание после перезапуска")
            subprocess.run(["docker", "stop", s3_name], check=True, capture_output=True)
            if not state.failure_reported.wait(60):
                raise TimeoutError("Обработчик не зафиксировал временную недоступность S3")
            subprocess.run(["docker", "start", s3_name], check=True, capture_output=True)
            wait_for(lambda: _bucket_ready(s3), 60, "повторная готовность S3")
            state.resume_after_s3_recovery()

            if not state.ready.wait(120):
                logs = subprocess.run(["docker", "logs", worker_name], capture_output=True, text=True).stdout
                raise TimeoutError("Задание не стало готовым. Логи обработчика:\n" + logs[-8000:])
            wait_for(lambda: _staging_is_empty(s3), 20, "очистка временных результатов")

            with state.lock:
                assert state.job_state == "ready"
                assert state.attempt == 4, f"Ожидались рестарт, повтор после S3 и публикации, попыток: {state.attempt}"
                assert state.restart_requeued
                assert state.failure_count == 2, f"Не ожидалось дополнительных ошибок: {state.failure_count}"
                assert state.status_checks >= 1, "обработчик не сверил потерянное подтверждение"
                assert state.result_manifest is not None
                assert set(state.registered) == {"report_xlsx", "report_json", "transcript_md", "transcript_timed_md", "transcripts_zip"}
                assert state.register_counts == {"report_xlsx": 2, "report_json": 1, "transcript_md": 1,
                                                 "transcript_timed_md": 1, "transcripts_zip": 1}, state.register_counts
                assert state.score_answers and state.score_answers[0]["text"] == "контрольный"
                artifacts = list(state.result_manifest["artifacts"])

            for artifact in artifacts:
                obj = s3.get_object(Bucket=BUCKET, Key=f"interviews/{INTERVIEW_ID}/{artifact['kind']}")
                content = obj["Body"].read()
                assert len(content) == artifact["sizeBytes"], artifact["kind"]
                assert hashlib.sha256(content).hexdigest() == artifact["sha256"], artifact["kind"]
            xlsx = s3.get_object(Bucket=BUCKET, Key=f"interviews/{INTERVIEW_ID}/report_xlsx")["Body"].read()
            report = json.loads(s3.get_object(Bucket=BUCKET, Key=f"interviews/{INTERVIEW_ID}/report_json")["Body"].read())
            assert report["report"]["total"] == 8
            with zipfile.ZipFile(io.BytesIO(xlsx)) as workbook:
                sheet = workbook.read("xl/worksheets/sheet1.xml").decode()
                assert "контрольный" in sheet
            transcript = s3.get_object(Bucket=BUCKET, Key=f"interviews/{INTERVIEW_ID}/transcript_md")["Body"].read().decode()
            assert "контрольный" in transcript
            print("OK: обработчик восстановился после остановки во время Whisper и отказа S3, "
                  "повторил публикацию тех же файлов, сверил потерянное подтверждение и очистил staging")
    finally:
        whisper_release.set()
        if worker_started:
            subprocess.run(["docker", "rm", "-f", worker_name], capture_output=True)
        subprocess.run(["docker", "rm", "-f", s3_name], capture_output=True)
        if network_created:
            subprocess.run(["docker", "network", "rm", network_name], capture_output=True)
        analytics_server.shutdown()
        whisper_server.shutdown()


def _create_bucket(client) -> bool:
    try:
        client.create_bucket(Bucket=BUCKET)
        return True
    except Exception as error:
        if "BucketAlreadyOwnedByYou" in str(error):
            return True
        return False


def _staging_is_empty(client) -> bool:
    try:
        return not client.list_objects_v2(Bucket=BUCKET, Prefix=f"staging/{INTERVIEW_ID}/").get("Contents")
    except Exception:
        return False


def _bucket_ready(client) -> bool:
    try:
        client.head_bucket(Bucket=BUCKET)
        return True
    except Exception:
        return False


if __name__ == "__main__":
    main()
