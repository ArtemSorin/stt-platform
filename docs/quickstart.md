# Быстрый запуск на Windows, macOS и Linux

Для каждой системы нужен отдельный короткий набор команд. Windows использует PowerShell, macOS и Linux — один POSIX-скрипт `sh`. Скрипты запускают один и тот же Docker Compose стенд.

## Общие требования

- Git.
- Docker с Compose V2. На Windows и macOS установите и запустите Docker Desktop; на Windows включите Linux-контейнеры. На Linux нужен Docker Engine и Compose plugin.
- Интернет и несколько гигабайт свободного места для первой загрузки образов и моделей.
- На macOS/Linux нужны `openssl` и `curl`; Windows-скрипт использует PowerShell и .NET.

Скрипт проверяет доступ к Docker, создаёт `.env` со случайными секретами при первом запуске, проверяет Compose, собирает образы, запускает семь сервисов и ждёт ответа STT и готовности `finalization-worker`. Первый запуск может занять заметное время: загружаются образы и модели. Не закрывайте терминал до сообщения `Стенд готов`.

## Windows

Откройте Windows PowerShell 5.1. PowerShell 7 тоже поддерживается.

```powershell
git clone -b refactor/add-fisrt-containerization https://github.com/ArtemSorin/stt-platform.git
Set-Location stt-platform
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-pilot.ps1
```

Если PowerShell 7 уже установлен, последнюю команду можно выполнить как `pwsh -NoProfile -File .\scripts\start-pilot.ps1`. Ошибка `pwsh не распознано` означает, что PowerShell 7 отсутствует; используйте `powershell`, установленный вместе с Windows.

## macOS

Запустите Docker Desktop и откройте Terminal:

```sh
git clone -b refactor/add-fisrt-containerization https://github.com/ArtemSorin/stt-platform.git
cd stt-platform
sh ./scripts/start-pilot.sh
```

На Apple Silicon STT автоматически собирается для `arm64`; на Mac с Intel — для `amd64`. Не задавайте `DOCKER_DEFAULT_PLATFORM=linux/amd64` и не подключайте старый amd64 override на Apple Silicon.

## Linux

Убедитесь, что Docker Engine запущен и текущий пользователь может выполнять `docker` из своего терминала:

```sh
git clone -b refactor/add-fisrt-containerization https://github.com/ArtemSorin/stt-platform.git
cd stt-platform
sh ./scripts/start-pilot.sh
```

Скрипт использует архитектуру Docker Engine: `amd64` или `arm64`.

## После запуска

Откройте http://127.0.0.1:8080/.

```sh
docker compose ps
docker compose logs -f
```

На Windows эти же команды выполняются в PowerShell. Проверить архитектуру STT можно так:

```sh
docker compose exec stt-service uname -m
```

На Apple Silicon ожидается `aarch64`, на Intel/AMD — `x86_64`. Остановить сервисы, сохранив данные, можно командой `docker compose down`. Не используйте `docker compose down -v`, если нужно сохранить интервью: `-v` удаляет тома с данными.

Секреты находятся в локальном `.env`; он исключён из Git и нужен для последующих запусков. Не удаляйте его вместе с томами.

## Частые ошибки

- **Docker не запущен или недоступен:** запустите Docker Desktop либо Docker Engine и проверьте `docker info`.
- **`pwsh` не найден на Windows:** выполните команду через Windows PowerShell 5.1: `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-pilot.ps1`.
- **Порт занят:** стенд использует `127.0.0.1:8080` и `127.0.0.1:8083`. Освободите соответствующий порт и повторите запуск. Порт S3 `8333` наружу не публикуется.
- **`git pull` остановился из-за локального `compose.yaml`:** обновление не произошло. Сначала проверьте свои изменения и сохраните нужные в stash, затем выполните `git pull --ff-only`. Запускайте стенд только после успешного обновления.
- **Очередь аудио переполнена во время интервью:** сначала проверьте архитектуру контейнера командой выше и логи `docker compose logs -f stt-service`. Увеличение очереди само по себе не ускоряет распознавание.

При сбое запуска скрипт показывает состояние контейнеров и последние логи ключевых сервисов. Их можно запросить отдельно: `docker compose logs --tail=200 stt-service analytics whisper finalization-worker`.
