#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mapfile -t files < <(find "$root/sql/migrations" -maxdepth 1 -type f -name 'V*.sql' -printf '%f\n' | sort -V)
last=""
for file in "${files[@]}"; do
  version="${file#V}"
  version="${version%%__*}"
  if [[ -n "$last" && "$version" == "$last" ]]; then
    echo "duplicate migration version: $version" >&2
    exit 1
  fi
  last="$version"
  grep -Eq '^--|START TRANSACTION|CREATE TABLE|ALTER TABLE|UPDATE|DELETE|INSERT' "$root/sql/migrations/$file" || {
    echo "migration has no SQL statements: $file" >&2
    exit 1
  }
done
echo "Migration checks passed: ${#files[@]} files"
