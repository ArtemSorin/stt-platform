#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
testdir=$(mktemp -d)
trap 'rm -rf "$testdir"' EXIT HUP INT TERM
mkdir -p "$testdir/scripts" "$testdir/bin"
cp "$root/scripts/start-pilot.sh" "$testdir/scripts/"
cat > "$testdir/bin/docker" <<'EOF'
#!/bin/sh
case "$*" in
  'compose ps -q finalization-worker') echo worker ;;
  'inspect '*) echo healthy ;;
esac
EOF
cat > "$testdir/bin/curl" <<'EOF'
#!/bin/sh
exit 0
EOF
chmod +x "$testdir/bin/docker" "$testdir/bin/curl"
PATH="$testdir/bin:$PATH" sh "$testdir/scripts/start-pilot.sh"
[ "$(wc -l < "$testdir/.env" | tr -d ' ')" = 4 ]
cp "$testdir/.env" "$testdir/expected"
PATH="$testdir/bin:$PATH" sh "$testdir/scripts/start-pilot.sh"
cmp "$testdir/.env" "$testdir/expected"
