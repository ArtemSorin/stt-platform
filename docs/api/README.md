# HTTP API

Контракты сохранены рядом с исходниками и проверяются в CI.

- [STT, браузерное интервью и Whisper](stt.openapi.yaml) — `127.0.0.1:8080`; диаризацией управляет STT внутри процесса.
- [Аналитика, отчёты и диагностика FRIDA](analytics.openapi.yaml) — `127.0.0.1:8083`.
- [WebSocket-события и сценарии обмена](interview-events.md) — потоковый Vosk внутри STT и закрытый поток STT → аналитика.

Локальная проверка той же версией Redocly CLI, что и в CI:

```powershell
npx --yes @redocly/cli@1.34.2 lint .\docs\api\stt.openapi.yaml .\docs\api\analytics.openapi.yaml
```

Для HTTP-аудио используется `application/octet-stream`: PCM16 little-endian, mono, 16 кГц. У браузерных маршрутов сессии идентификатор передаётся заголовком `X-Session-Id`, кадры упорядочиваются через `X-Sequence`. Внутренний канал аналитики требует `X-Internal-Token`; это WebSocket handshake, а не заголовок браузерного API.
