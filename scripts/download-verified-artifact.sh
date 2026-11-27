#!/bin/sh
set -eu

if [ "$#" -ne 3 ]; then
    printf 'Usage: %s URL DESTINATION SHA256\n' "$0" >&2
    exit 2
fi

url=$1
destination=$2
expected_sha256=$3
cache_dir=${DOWNLOAD_CACHE_DIR:-/var/cache/stt-downloads}
retry_delay=${DOWNLOAD_RETRY_DELAY_SECONDS:-5}
max_attempts=${DOWNLOAD_MAX_ATTEMPTS:-10}

case "$retry_delay" in
    ''|*[!0-9]*)
        printf 'DOWNLOAD_RETRY_DELAY_SECONDS must be a non-negative integer.\n' >&2
        exit 2
        ;;
esac
case "$max_attempts" in
    ''|*[!0-9]*|0)
        printf 'DOWNLOAD_MAX_ATTEMPTS must be a positive integer.\n' >&2
        exit 2
        ;;
esac

if [ "${#expected_sha256}" -ne 64 ]; then
    printf 'Expected SHA-256 must contain 64 lowercase hexadecimal characters.\n' >&2
    exit 2
fi
case "$expected_sha256" in
    *[!0-9a-f]*)
        printf 'Expected SHA-256 must contain 64 lowercase hexadecimal characters.\n' >&2
        exit 2
        ;;
esac

mkdir -p "$cache_dir" "$(dirname "$destination")"
cached_file="$cache_dir/$expected_sha256"
partial_file="$cached_file.partial"

is_verified() {
    file=$1
    [ -f "$file" ] || return 1
    printf '%s  %s\n' "$expected_sha256" "$file" | sha256sum --check --status -
}

if is_verified "$cached_file"; then
    cp "$cached_file" "$destination"
    exit 0
fi
rm -f "$cached_file"

if ! is_verified "$partial_file"; then
    attempt=1
    while [ "$attempt" -le "$max_attempts" ]; do
        if curl \
            --fail \
            --location \
            --http1.1 \
            --connect-timeout 30 \
            --speed-time 120 \
            --speed-limit 1024 \
            --continue-at - \
            --output "$partial_file" \
            "$url"; then
            if is_verified "$partial_file"; then
                break
            fi
            rm -f "$partial_file"
            printf 'Downloaded artifact failed SHA-256 verification (attempt %s/%s).\n' "$attempt" "$max_attempts" >&2
        elif is_verified "$partial_file"; then
            break
        else
            printf 'Download interrupted; retaining received bytes (attempt %s/%s).\n' "$attempt" "$max_attempts" >&2
        fi

        if [ "$attempt" -lt "$max_attempts" ]; then
            sleep "$retry_delay"
        fi
        attempt=$((attempt + 1))
    done

    if ! is_verified "$partial_file"; then
        printf 'Download failed after %s attempts; partial data is kept for the next build: %s\n' "$max_attempts" "$partial_file" >&2
        exit 1
    fi
fi

mv "$partial_file" "$cached_file"
cp "$cached_file" "$destination"
