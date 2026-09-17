#!/usr/bin/env bash
# Re-attach football-nacos to the compose network with DNS aliases.
# Idempotent. Does not read or print .env secrets.
set -euo pipefail

NETWORK="${NACOS_DOCKER_NETWORK:-footballforecastsystem_football-network}"
CONTAINER="${NACOS_CONTAINER:-football-nacos}"
GATEWAY="${GATEWAY_CONTAINER:-football-gateway}"

if ! docker inspect "$CONTAINER" >/dev/null 2>&1; then
  echo "container missing: $CONTAINER" >&2
  exit 1
fi
if ! docker network inspect "$NETWORK" >/dev/null 2>&1; then
  echo "network missing: $NETWORK" >&2
  exit 1
fi

if docker exec "$GATEWAY" getent hosts nacos >/dev/null 2>&1; then
  echo "ok: nacos already resolves from $GATEWAY"
  docker exec "$GATEWAY" getent hosts nacos || true
  exit 0
fi

echo "re-attaching $CONTAINER to $NETWORK with aliases nacos,football-nacos"
docker network disconnect "$NETWORK" "$CONTAINER" 2>/dev/null || true
docker network connect --alias nacos --alias football-nacos "$NETWORK" "$CONTAINER"

echo "verify DNS:"
docker exec "$GATEWAY" getent hosts nacos

echo "local gateway health (best-effort):"
curl -fsS --max-time 5 "http://127.0.0.1:8082/actuator/health" || echo "(gateway health not ready yet)"
echo
echo "done. If gateway stays unhealthy: docker restart $GATEWAY"
