"""Проверяет фоновый отчёт по тестовому интервью без открытого браузера."""

import argparse
import io
import json
import os
import re
import subprocess
import time
import urllib.error
import urllib.request
import wave
import zipfile

BASE = os.environ.get("STT_SMOKE_BASE_URL", "http://127.0.0.1:8080").rstrip("/")


def request(path, body=None, headers=None):
    req = urllib.request.Request(BASE + path, data=body, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=45) as response:
            data = response.read()
            return json.loads(data) if "json" in response.headers.get("Content-Type", "") else data
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"{path}: HTTP {error.code}: {error.read().decode('utf-8', 'replace')}") from error


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("reference", help="неперсональный WAV PCM16 mono 16 кГц с речью")
    parser.add_argument("--restart-worker", metavar="CONTAINER",
                        help="остановить и перезапустить указанный тестовый контейнер во время обработки")
    args = parser.parse_args()
    with wave.open(args.reference, "rb") as audio:
        if (audio.getnchannels(), audio.getsampwidth(), audio.getframerate()) != (1, 2, 16000):
            raise ValueError("Требуется WAV PCM16 mono 16 кГц")
        speech = audio.readframes(16000 * 8)
    start = request("/api/test/start", json.dumps({"candidate": "Тест", "whisperEnabled": True}).encode(),
                    {"Content-Type": "application/json"})
    session = start["session_id"]
    headers = {"X-Session-Id": session}
    print("session=" + session, flush=True)
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        state = request("/api/status", headers=headers)
        if state.get("integration", {}).get("question"):
            break
        time.sleep(0.5)
    else:
        raise RuntimeError("Не получен вопрос")
    request("/api/question/asked", b"", headers)
    audio = speech + bytes(32000 * 3)
    for sequence, offset in enumerate(range(0, len(audio), 20480)):
        request("/api/audio", audio[offset:offset + 20480],
                {**headers, "Content-Type": "application/octet-stream", "X-Sequence": str(sequence)})
        time.sleep(0.64)
    request("/api/finish", b"", headers)
    deadline = time.monotonic() + 600
    restarted = False
    while time.monotonic() < deadline:
        state = request("/api/interviews/" + session)
        print(json.dumps(state, ensure_ascii=False), flush=True)
        if args.restart_worker and not restarted and state.get("finalizationStatus") == "running":
            subprocess.run(["docker", "kill", args.restart_worker], check=True, stdout=subprocess.DEVNULL)
            subprocess.run(["docker", "start", args.restart_worker], check=True, stdout=subprocess.DEVNULL)
            restarted = True
            print("Обработчик принудительно перезапущен во время обработки", flush=True)
        if state.get("reportStatus") == "error":
            raise RuntimeError("Ошибка фоновой обработки")
        if state.get("audioStatus") == "saved" and state.get("reportStatus") == "ready":
            if args.restart_worker and not restarted:
                raise RuntimeError("Обработка завершилась до проверки перезапуска")
            report = request("/api/interviews/" + session + "/artifacts/report_xlsx")
            summary = request("/api/interviews/" + session + "/artifacts/report_json")
            if not isinstance(summary, dict) or not isinstance(summary.get("report"), dict):
                raise RuntimeError("Нет структурированного отчёта для интерфейса")
            transcript = request("/api/interviews/" + session + "/artifacts/transcript_md").decode("utf-8")
            with zipfile.ZipFile(io.BytesIO(report)) as document:
                if not any(name.startswith("xl/worksheets/") for name in document.namelist()):
                    raise RuntimeError("В XLSX отсутствует лист отчёта")
                xml = " ".join(document.read(name).decode("utf-8", "ignore")
                               for name in document.namelist() if name.endswith(".xml")).lower()
            words = [word.lower() for word in re.findall(r"[А-Яа-яЁё]{5,}", transcript)
                     if word.lower() not in {"кандидат", "интервьюер"}]
            if not words or not any(word in xml for word in words):
                raise RuntimeError("В XLSX не обнаружен текст подтверждённого ответа из транскрипта")
            print("OK: архив и XLSX доступны по прежнему ID", flush=True)
            return
        time.sleep(3)
    raise RuntimeError("Отчёт не готов за 10 минут: " + session)


if __name__ == "__main__":
    main()
