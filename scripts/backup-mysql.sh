#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUTPUT_DIR="${1:-$ROOT_DIR/backups}"
mkdir -p "$OUTPUT_DIR"

: "${MYSQL_HOST:?MYSQL_HOST must be configured}"
: "${MYSQL_PORT:?MYSQL_PORT must be configured}"
: "${MYSQL_DB:?MYSQL_DB must be configured}"
: "${MYSQL_USER:?MYSQL_USER must be configured}"
: "${MYSQL_PASSWORD:?MYSQL_PASSWORD must be configured}"

command -v mysqldump >/dev/null 2>&1 || {
  echo "mysqldump is required; install MySQL client tools first" >&2
  exit 1
}

stamp="$(date +%Y%m%d-%H%M%S)"
target="$OUTPUT_DIR/football_forecast-$stamp.sql"
export MYSQL_PWD="$MYSQL_PASSWORD"
trap 'unset MYSQL_PWD' EXIT

mysqldump \
  --host="$MYSQL_HOST" \
  --port="$MYSQL_PORT" \
  --user="$MYSQL_USER" \
  --single-transaction --quick --routines --events --triggers --hex-blob \
  --set-gtid-purged=OFF --databases "$MYSQL_DB" > "$target"

if [[ ! -s "$target" || "$(wc -c < "$target")" -lt 1024 ]]; then
  rm -f "$target"
  echo "backup file is unexpectedly small" >&2
  exit 1
fi

echo "Backup written to $target"
