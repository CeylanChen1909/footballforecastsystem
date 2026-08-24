#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MIGRATION_DIR="$ROOT_DIR/sql/migrations"
DRY_RUN=0
ALLOW_DESTRUCTIVE=0

for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=1 ;;
    --allow-destructive) ALLOW_DESTRUCTIVE=1 ;;
    *) echo "Usage: $0 [--dry-run] [--allow-destructive]" >&2; exit 2 ;;
  esac
done

command -v mysql >/dev/null 2>&1 || { echo "mysql client is required" >&2; exit 1; }
command -v sha256sum >/dev/null 2>&1 || { echo "sha256sum is required" >&2; exit 1; }

: "${MYSQL_PASSWORD:?MYSQL_PASSWORD must be set; refusing to prompt or print credentials}"
DB_HOST="${MYSQL_HOST:-127.0.0.1}"
DB_PORT="${MYSQL_PORT:-3306}"
DB_NAME="${MYSQL_DB:-football_forecast}"
DB_USER="${MYSQL_USER:-root}"
ACTOR="${DEPLOY_ACTOR:-${USER:-release}}"
export MYSQL_PWD="$MYSQL_PASSWORD"
trap 'unset MYSQL_PWD' EXIT

mysql_cmd=(mysql --host="$DB_HOST" --port="$DB_PORT" --user="$DB_USER" --database="$DB_NAME")
mysql_batch=("${mysql_cmd[@]}" --batch --skip-column-names)

"${mysql_cmd[@]}" <<'SQL'
CREATE TABLE IF NOT EXISTS schema_migrations (
  version VARCHAR(32) NOT NULL PRIMARY KEY,
  description VARCHAR(255) NOT NULL,
  checksum CHAR(64) NOT NULL,
  applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  applied_by VARCHAR(128) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
SQL

mapfile -t files < <(find "$MIGRATION_DIR" -maxdepth 1 -type f -name 'V*.sql' -print | sort)
[[ ${#files[@]} -gt 0 ]] || { echo "No migrations found in $MIGRATION_DIR" >&2; exit 1; }

for file in "${files[@]}"; do
  name="$(basename "$file")"
  if [[ ! "$name" =~ ^V([0-9]+)__([A-Za-z0-9][A-Za-z0-9_-]*)\.sql$ ]]; then
    echo "Invalid migration filename: $name" >&2
    exit 1
  fi
  version="${BASH_REMATCH[1]}"
  description="${BASH_REMATCH[2]}"
  checksum="$(sha256sum "$file" | awk '{print tolower($1)}')"
  existing="$("${mysql_batch[@]}" -e "SELECT checksum FROM schema_migrations WHERE version='$version' LIMIT 1")"
  if [[ -n "$existing" ]]; then
    [[ "$existing" == "$checksum" ]] || { echo "Checksum mismatch for $name" >&2; exit 1; }
    echo "SKIP $name"
    continue
  fi

  if grep -Eiq '\bDROP[[:space:]]+(DATABASE|TABLE)\b|\bTRUNCATE[[:space:]]+TABLE\b|\bDELETE[[:space:]]+' "$file" && [[ "$ALLOW_DESTRUCTIVE" -ne 1 ]]; then
    echo "Destructive migration blocked: $name. Run backup first, then add --allow-destructive." >&2
    exit 1
  fi
  if [[ "$DRY_RUN" -eq 1 ]]; then
    echo "WOULD APPLY $name"
    continue
  fi

  echo "APPLY $name"
  "${mysql_cmd[@]}" < "$file"
  safe_actor="$(printf '%s' "$ACTOR" | tr -cd '[:alnum:]_.-')"
  safe_actor="${safe_actor:-release}"
  "${mysql_cmd[@]}" -e "INSERT INTO schema_migrations(version,description,checksum,applied_by) VALUES('$version','$description','$checksum','$safe_actor')"
done

echo "Migration run completed."
