"""Проверяет двухканальное интервью на локальном стенде с контрольным WAV."""

import argparse
import json
import io
import struct
import subprocess
import time
import urllib.error
import urllib.request
import wave

BASE = "http://127.0.0.1:8080"


def request(path, body=None, headers=None):
    req = urllib.request.Request(BASE + path, data=body, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=180) as response:
            content = response.read()
            return json.loads(content) if content and "json" in response.headers.get("Content-Type", "") else content
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"{path}: HTTP {error.code}: {error.read().decode('utf-8', 'replace')}") from error


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("reference", help="неперсональный моно WAV PCM16 16 кГц с голосом кандидата")
    parser.add_argument("--container", help="имя контейнера STT для проверки распознавания и диаризации")
    args = parser.parse_args()
    with wave.open(args.reference, "rb") as audio:
        if (audio.getnchannels(), audio.getsampwidth(), audio.getframerate()) != (1, 2, 16000):
            raise ValueError("Требуется моно PCM16 16 кГц")
        reference = audio.readframes(16000 * 10)
        audio.rewind()
        speech = audio.readframes(16000 * 8)
    if len(reference) < 16000 * 2 * 5:
        raise ValueError("Эталон должен содержать не менее 5 секунд")
    state = request("/api/enrollment/start", b"")
    session = state["session_id"]
    headers = {"X-Session-Id": session, "Content-Type": "application/octet-stream"}
    for sequence, position in enumerate(range(0, len(reference), 20480)):
        request("/api/enrollment/audio", reference[position:position + 20480], {**headers, "X-Sequence": str(sequence)})
    state = request("/api/enrollment/finish", b"", {**headers, "X-Audio-Mode": "channels"})
    if state.get("phase") != "live":
        raise RuntimeError("Эталон не принят: " + json.dumps(state, ensure_ascii=False))
    for sequence, position in enumerate(range(0, len(speech), 20480)):
        chunk = speech[position:position + 20480]
        samples = struct.unpack("<" + "h" * (len(chunk) // 2), chunk)
        stereo = bytearray()
        for sample in samples:
            stereo.extend(struct.pack("<hh", sample, ~sample))
        request("/api/audio/channels", bytes(stereo), {**headers, "X-Sequence": str(sequence)})
        time.sleep(0.64)
    request("/api/finish", b"", headers)
    deadline = time.monotonic() + 240
    while time.monotonic() < deadline:
        time.sleep(2)
        try:
            state = request("/api/interviews/" + session)
        except (RuntimeError, urllib.error.URLError) as error:
            print(f"Ожидание сервиса: {error}", flush=True)
            continue
        print(json.dumps(state, ensure_ascii=False), flush=True)
        if state["audioStatus"] == "saved" and state["reportStatus"] == "ready":
            recording = request("/api/interviews/" + session + "/artifacts/audio_wav")
            if recording[:4] != b"RIFF" or recording[8:12] != b"WAVE":
                raise RuntimeError("Архив не вернул WAV")
            with wave.open(io.BytesIO(recording), "rb") as archived:
                if (archived.getnchannels(), archived.getsampwidth(), archived.getframerate()) != (2, 2, 16000):
                    raise RuntimeError("Неверный формат архивной записи")
                frames = archived.readframes(archived.getnframes())
                values = struct.unpack("<" + "h" * (len(frames) // 2), frames)
                actual_system = struct.pack("<" + "h" * (len(values) // 2), *values[::2])
                actual_microphone = struct.pack("<" + "h" * (len(values) // 2), *values[1::2])
                samples_for_check = struct.unpack("<" + "h" * (len(speech) // 2), speech)
                expected_microphone = struct.pack("<" + "h" * len(samples_for_check),
                                                  *(~sample for sample in samples_for_check))
                if actual_system != speech or actual_microphone != expected_microphone:
                    raise RuntimeError("Каналы WAV отличаются от отправленных")
            report = request("/api/interviews/" + session + "/artifacts/report_xlsx")
            if not report.startswith(b"PK"):
                raise RuntimeError("Архив не вернул XLSX")
            if args.container:
                command = "cat /data/runs/*" + session[:8] + "*/stt-events.jsonl"
                output = subprocess.check_output(["docker", "exec", args.container, "sh", "-c", command], text=True, encoding="utf-8")
                events = [json.loads(line) for line in output.splitlines()]
                if not any(event.get("text", "").strip() for event in events):
                    raise RuntimeError("Vosk не распознал речь")
                command = "cat /data/diarization/*" + session[:8] + "*/segments.json"
                segments = json.loads(subprocess.check_output(
                    ["docker", "exec", args.container, "sh", "-c", command], text=True, encoding="utf-8"))
                if not any(segment.get("label") == "Кандидат" for segment in segments):
                    raise RuntimeError("Нет подтверждённого сегмента кандидата")
            print("OK: session=" + session + ", WAV и XLSX доступны")
            return
    raise RuntimeError("Истекло ожидание архива: " + session)


if __name__ == "__main__":
    main()
