#!/usr/bin/env bash
set -Eeuo pipefail

PROJECT_ROOT="$(CDPATH= cd "$(dirname "$0")/.." && pwd)"
TEMP_PARENT="$(mktemp -d)"
TEMP_ROOT="$TEMP_PARENT/проект с пробелами"
trap 'rm -rf "$TEMP_PARENT"' EXIT

mkdir -p "$TEMP_ROOT/project/scripts" "$TEMP_ROOT/bin"
cp "$PROJECT_ROOT/scripts/start-pilot.sh" "$TEMP_ROOT/project/scripts/start-pilot.sh"

cat > "$TEMP_ROOT/bin/docker" <<'MOCK_DOCKER'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$DOCKER_CALL_LOG"
if [ "${1:-}" = info ]; then
    printf 'linux\n'
fi
if [ "${1:-}" = compose ] && [ "${2:-}" = ps ]; then
    printf 'fake-container-id\n'
fi
if [ "${1:-}" = inspect ]; then
    printf 'healthy\n'
fi
if [ "${1:-}" = compose ] && [ "${2:-}" = up ]; then
    printf 'compose-up-whisper-model=%s\n' "${WHISPER_MODEL:-}" >> "$DOCKER_CALL_LOG"
fi
if [ -n "${COMPOSE_FILE:-}" ] || [ -n "${COMPOSE_PATH_SEPARATOR:-}" ] || [ -n "${DOCKER_DEFAULT_PLATFORM:-}" ]; then
    touch "$OVERRIDE_SEEN"
fi
exit 0
MOCK_DOCKER
cat > "$TEMP_ROOT/bin/curl" <<'MOCK_CURL'
#!/usr/bin/env bash
exit 0
MOCK_CURL
cat > "$TEMP_ROOT/bin/sleep" <<'MOCK_SLEEP'
#!/usr/bin/env bash
exit 0
MOCK_SLEEP
chmod +x "$TEMP_ROOT/bin/docker" "$TEMP_ROOT/bin/curl" "$TEMP_ROOT/bin/sleep"

export PATH="$TEMP_ROOT/bin:$PATH"
export DOCKER_CALL_LOG="$TEMP_ROOT/docker-calls.log"
export OVERRIDE_SEEN="$TEMP_ROOT/compose-override-seen"
export COMPOSE_FILE=compose.mac-amd64.yaml
export COMPOSE_PATH_SEPARATOR=:
export DOCKER_DEFAULT_PLATFORM=linux/amd64
export WHISPER_MODEL='C:\Users\test\whisper-model'
cd "$TEMP_ROOT/project"
PILOT_STARTUP_TIMEOUT_SECONDS=5 sh scripts/start-pilot.sh >/dev/null
[ ! -e "$OVERRIDE_SEEN" ]

[ "$(wc -l < .env | tr -d ' ')" = 4 ]
for name in POSTGRES_PASSWORD S3_ACCESS_KEY S3_SECRET_KEY INTERVIEW_EVENTS_TOKEN; do
    grep -Eq "^${name}=[0-9a-f]{72}$" .env
done
grep -Fq 'compose config --quiet' "$DOCKER_CALL_LOG"
grep -Fq 'compose up -d --build' "$DOCKER_CALL_LOG"
grep -Fq 'compose-up-whisper-model=small' "$DOCKER_CALL_LOG"
[ "$WHISPER_MODEL" = 'C:\Users\test\whisper-model' ]

cp .env "$TEMP_ROOT/env.expected"
: > "$DOCKER_CALL_LOG"
PILOT_STARTUP_TIMEOUT_SECONDS=5 sh scripts/start-pilot.sh >/dev/null
cmp .env "$TEMP_ROOT/env.expected"

printf 'WHISPER_MODEL=C:\\Users\\test\\whisper-model\n' >> .env
cp .env "$TEMP_ROOT/env.expected"
unset WHISPER_MODEL
: > "$DOCKER_CALL_LOG"
PILOT_STARTUP_TIMEOUT_SECONDS=5 sh scripts/start-pilot.sh >/dev/null
cmp .env "$TEMP_ROOT/env.expected"
grep -Fq 'compose-up-whisper-model=small' "$DOCKER_CALL_LOG"

printf 'Проверка shell-запуска пройдена.\n'
