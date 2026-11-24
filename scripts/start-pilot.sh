#!/bin/sh
# Запуск пилотного стенда в macOS и Linux.
set -eu
cd "$(dirname "$0")/.."
command -v docker >/dev/null 2>&1 || { echo 'Установите Docker с Compose.' >&2; exit 1; }
command -v openssl >/dev/null 2>&1 || { echo 'Для создания секретов требуется openssl.' >&2; exit 1; }
command -v curl >/dev/null 2>&1 || { echo 'Установите curl для проверки готовности.' >&2; exit 1; }
docker info >/dev/null 2>&1 || { echo 'Запустите Docker Engine и проверьте доступ к нему.' >&2; exit 1; }
docker compose version >/dev/null

if [ ! -e .env ]; then
  umask 077
  tmp_env="$(mktemp .env.XXXXXXXX)"
  trap 'rm -f "$tmp_env"' EXIT HUP INT TERM
  for name in POSTGRES_PASSWORD S3_ACCESS_KEY S3_SECRET_KEY INTERVIEW_EVENTS_TOKEN; do
    printf '%s=%s\n' "$name" "$(openssl rand -hex 36)" >> "$tmp_env"
  done
  if ! ln "$tmp_env" .env 2>/dev/null && [ ! -s .env ]; then
    echo 'Не удалось создать .env.' >&2
    exit 1
  fi
  rm -f "$tmp_env"
  trap - EXIT HUP INT TERM
fi

model=${WHISPER_MODEL:-}
if [ -z "$model" ]; then
  model=$(sed -n 's/^[[:space:]]*WHISPER_MODEL[[:space:]]*=[[:space:]]*//p' .env | tail -n 1 | tr -d "\"'")
fi
case "$model" in
  [A-Za-z]:[\\/]*|\\\\*)
    echo 'WHISPER_MODEL содержит путь Windows; для контейнера выбрана модель small.' >&2
    WHISPER_MODEL=small
    export WHISPER_MODEL
    ;;
esac

docker compose up -d --build || { docker compose ps; docker compose logs --tail=80 finalization-worker analytics whisper stt-service; exit 1; }
deadline=$(( $(date +%s) + 900 ))
while [ "$(date +%s)" -lt "$deadline" ]; do
  worker_id=$(docker compose ps -q finalization-worker)
  health=''
  if [ -n "$worker_id" ]; then health=$(docker inspect --format '{{.State.Health.Status}}' "$worker_id" 2>/dev/null || true); fi
  if [ "$health" = healthy ] && curl -fsS --max-time 3 http://127.0.0.1:8080/ >/dev/null 2>&1; then
    echo 'Стенд готов: http://127.0.0.1:8080/'
    exit 0
  fi
  sleep 5
done
docker compose ps
docker compose logs --tail=80 finalization-worker stt-service analytics whisper
echo 'Сервисы не стали готовы за 15 минут. Проверьте логи.' >&2
exit 1
