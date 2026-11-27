#!/bin/sh
set -eu

PROJECT_ROOT="$(CDPATH= cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"

# Пилот всегда использует основной Compose-файл и платформу Docker Engine хоста.
unset COMPOSE_FILE COMPOSE_PATH_SEPARATOR DOCKER_DEFAULT_PLATFORM

fail() {
    printf 'Ошибка: %s\n' "$1" >&2
    exit 1
}

command -v docker >/dev/null 2>&1 || fail 'Установите Docker Desktop или Docker Engine.'
command -v openssl >/dev/null 2>&1 || fail 'Для первого запуска нужен openssl, чтобы создать локальные секреты.'
command -v curl >/dev/null 2>&1 || fail 'Для проверки готовности нужен curl.'
docker compose version >/dev/null 2>&1 || fail 'Установите Docker Compose V2.'
docker_os="$(docker info --format '{{.OSType}}' 2>/dev/null)" || fail 'Не удалось подключиться к Docker. Запустите Docker Desktop или Docker Engine.'
[ "$docker_os" = linux ] || fail 'Для проекта нужен Docker с Linux-контейнерами.'

if [ ! -f .env ]; then
    temp_env="$(mktemp "$PROJECT_ROOT/.env.XXXXXX")"
    trap 'rm -f "${temp_env:-}"' EXIT
    umask 077
    for name in POSTGRES_PASSWORD S3_ACCESS_KEY S3_SECRET_KEY INTERVIEW_EVENTS_TOKEN; do
        secret="$(openssl rand -hex 36)" || fail 'Не удалось создать локальные секреты.'
        printf '%s=%s\n' "$name" "$secret" >> "$temp_env"
    done
    if [ ! -e .env ]; then
        mv "$temp_env" .env
    else
        rm -f "$temp_env"
    fi
    temp_env=
    trap - EXIT
fi

previous_whisper_model="${WHISPER_MODEL-}"
model_for_compose="$previous_whisper_model"
if [ -z "$model_for_compose" ]; then
    model_for_compose="$(sed -n 's/^[[:space:]]*WHISPER_MODEL[[:space:]]*=[[:space:]]*//p' .env | tail -n 1 | tr -d "\"'")"
fi
case "$model_for_compose" in
    [A-Za-z]:[\\/]*|\\\\*)
        printf 'WHISPER_MODEL содержит путь Windows; в контейнере будет использована модель small.\n' >&2
        export WHISPER_MODEL=small
        ;;
esac

docker compose config --quiet || fail 'Проверка compose.yaml завершилась ошибкой.'
if ! docker compose up -d --build; then
    docker compose ps || true
    docker compose logs --tail=100 finalization-worker analytics whisper stt-service || true
    fail 'Сборка или запуск Compose завершились ошибкой.'
fi
if [ -n "$previous_whisper_model" ]; then
    export WHISPER_MODEL="$previous_whisper_model"
else
    unset WHISPER_MODEL
fi

timeout_seconds="${PILOT_STARTUP_TIMEOUT_SECONDS:-1200}"
case "$timeout_seconds" in
    ''|*[!0-9]*) fail 'PILOT_STARTUP_TIMEOUT_SECONDS должен быть целым числом секунд.' ;;
esac
attempts=$(( (timeout_seconds + 4) / 5 ))
attempt=0
ready=false
while [ "$attempt" -lt "$attempts" ]; do
    worker_id="$(docker compose ps -q finalization-worker 2>/dev/null || true)"
    worker_health=''
    if [ -n "$worker_id" ]; then
        worker_health="$(docker inspect --format '{{.State.Health.Status}}' "$worker_id" 2>/dev/null || true)"
    fi
    if [ "$worker_health" = healthy ] \
        && curl --fail --silent --show-error --max-time 3 http://127.0.0.1:8080/api/status >/dev/null 2>&1; then
        ready=true
        break
    fi
    sleep 5
    attempt=$((attempt + 1))
done

if [ "$ready" != true ]; then
    docker compose ps || true
    docker compose logs --tail=100 finalization-worker stt-service analytics whisper || true
    fail "STT или finalization-worker не стали готовы за ${timeout_seconds} секунд. Проверьте логи выше."
fi

docker compose ps
printf '\nСтенд готов: http://127.0.0.1:8080/\nЛоги: docker compose logs -f\n'
