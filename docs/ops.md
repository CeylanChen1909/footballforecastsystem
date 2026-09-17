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

## Nacos network alias persistence

Java services use `NACOS_ADDR=nacos:8848`. Compose service name is `nacos` with `container_name: football-nacos`.

`docker-compose.prod.yml` pins both DNS aliases on the `football-network` attachment:

```yaml
nacos:
  networks:
    football-network:
      aliases:
        - nacos
        - football-nacos
```

Gateway / user / business `depends_on` nacos with `condition: service_healthy`, so they wait for Nacos before joining.

### After recreate / if `/api/actuator/health` is 502

1. Check DNS: `docker exec football-gateway getent hosts nacos`
2. Preferred repair (idempotent script):

```bash
./scripts/repair-nacos-network.sh
```

3. Manual equivalent — re-attach **with both aliases** (do not recreate the whole stack):

```bash
NET=footballforecastsystem_football-network
docker network disconnect "$NET" football-nacos 2>/dev/null || true
docker network connect --alias nacos --alias football-nacos "$NET" football-nacos
docker exec football-gateway getent hosts nacos
# restart gateway only if it stays down:
# docker compose -f docker-compose.prod.yml restart football-gateway
curl -fsS http://127.0.0.1:8082/actuator/health
```

Compose also pins `networks.football-network.name: footballforecastsystem_football-network` so the repair script target stays stable across recreate.

Prefer `docker compose … up -d nacos` (uses compose aliases) over raw `docker run` so aliases stay declared in the project file.


## Frontend nginx security headers

`frontend/nginx.conf` sets `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy`, `Permissions-Policy`, and a moderate CSP on the SPA container (`:3000`).

Nginx does **not** inherit `add_header` from `server` into a `location` that defines its own `add_header` (e.g. Cache-Control). Those locations repeat the security headers so `/assets/`, HTML shells, and health endpoints stay covered.

Host edge nginx (`/etc/nginx/sites-enabled/chenfootball.asia`) already sets XCTO / XFO / Referrer-Policy. Do not stack a second, stricter CSP at the edge without verifying the SPA + API still works.


## Round 6 — CSP Report-Only & Permissions-Policy

Frontend container nginx (`frontend/nginx.conf`) now:

- Tightens `Permissions-Policy` (camera/mic/geo/payment/usb/topics/sensors denied; `fullscreen=(self)`).
- Keeps the **moderate enforcing CSP** (allows `'unsafe-inline'` scripts/styles for the Vue SPA).
- Adds a **stricter `Content-Security-Policy-Report-Only`** (no `'unsafe-inline'` scripts, `connect-src` https-only, `object-src 'none'`) so violations can be observed without breaking the app.

### Edge host nginx limits

Public HTTPS terminates at `/etc/nginx/sites-enabled/chenfootball.asia`. That edge config already emits HSTS + XCTO + XFO + Referrer-Policy. Because nginx **accumulates duplicate `add_header` from proxy layers**, do **not** also attach an enforcing CSP (or a second Permissions-Policy) on the edge unless you intentionally own the full header set there and have removed the container duplicates. Prefer:

1. Container frontend nginx as the source of truth for CSP / Permissions-Policy (this file).
2. Edge keeps transport + baseline frame/nosniff/referrer only.
3. If Cloudflare sits in front, avoid stacking yet another CSP in the dashboard unless Report-Only is used for experiments.

Canonical PWA manifest is `/site.webmanifest`. Legacy `/manifest.webmanifest` is aliased to the same file.
