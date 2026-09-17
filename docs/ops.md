# ChenFootball ops checks (light monitoring)

Short curl checks for the live stack. Prefer container-local or `127.0.0.1` binds; public HTTPS goes through the host nginx / Cloudflare edge.

## Frontend

```bash
# Static shell health (served from frontend container / CDN origin)
curl -fsS https://chenfootball.asia/health.json
curl -fsS http://127.0.0.1:3000/health.json

# Prerendered SEO shells (title/description in first HTML bytes)
curl -fsS https://chenfootball.asia/matches | head -n 40
curl -fsS https://chenfootball.asia/competitions | head -n 40
curl -fsS https://chenfootball.asia/privacy | head -n 40
```

## Gateway / actuator

Gateway exposes Spring Actuator on the service port (not under `/api` by default).
Frontend nginx maps `GET /api/actuator/health` to gateway `/actuator/health` for a single public check.

```bash
curl -fsS http://127.0.0.1:8082/actuator/health
curl -fsS https://chenfootball.asia/api/actuator/health
```

Expect JSON with `"status":"UP"` (details may be hidden when unauthorized).

## MySQL / Redis (docker)

```bash
docker exec football-mysql mysqladmin ping -h localhost -u root -p"$MYSQL_ROOT_PASSWORD"
docker exec football-redis redis-cli ping
```

Compose already defines `healthcheck` for mysql, redis, nacos, and ml. Frontend also ships `/health.json`; gateway health is via actuator above.

## Admin analytics

Authenticated admin UI / API (requires admin JWT):

```bash
# Example — replace TOKEN; confirm path in Admin → Analytics network tab
curl -fsS -H "Authorization: Bearer $TOKEN" https://chenfootball.asia/api/analytics/summary
```

## Notes

- Do not scrape credentials from `.env` into tickets; run checks on the host that already has env loaded.
- Client-side route meta (title/description) still updates after SPA hydration; prerender shells are for crawlers/share bots only.

## Host nginx (edge)

Public HTTPS terminates at `/etc/nginx/sites-enabled/chenfootball.asia`.
`/api/` proxies to gateway `:8082/api/`. Actuator lives at gateway `/actuator/health`, so the edge maps:

```
location = /api/actuator/health { proxy_pass http://127.0.0.1:8082/actuator/health; ... }
```

Frontend container nginx has the same map for direct `:3000` checks. Static `/health.json` is served by the SPA container via `location /`.

## Troubleshooting: `/api/actuator/health` returns 502

Edge and frontend nginx maps are correct when present:

- host: `location = /api/actuator/health` → `http://127.0.0.1:8082/actuator/health`
- frontend container: `location = /api/actuator/health` → `http://football-gateway:8082/actuator/health`

If public/local checks still 502 while `football-frontend` is healthy:

1. Confirm gateway process answers locally: `curl -fsS http://127.0.0.1:8082/actuator/health`
2. If connection reset / gateway restarting, check Nacos DNS from gateway: `docker exec football-gateway getent hosts nacos`
3. If `nacos` does not resolve but `football-nacos` is running, the container may have lost its compose network endpoint (`Networks: {}` while `HostConfig.NetworkMode` still names the network). Re-attach without recreating the stack:

```bash
docker network connect --alias nacos footballforecastsystem_football-network football-nacos
docker exec football-gateway getent hosts nacos
# wait for gateway health; restart only the gateway if it stays down:
# docker restart football-gateway
curl -fsS http://127.0.0.1:8082/actuator/health
curl -fsS https://chenfootball.asia/api/actuator/health
```

Do not scrape `.env` secrets into tickets while debugging.

