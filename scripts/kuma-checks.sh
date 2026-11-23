#!/usr/bin/env bash
# footballforecastsystem — Uptime Kuma push 型检查脚本
#
# Kuma 自身只能做 HTTP 关键字探针，下面的三项检查它做不了，所以由 cron 调
# 本脚本、再由脚本向 Kuma 的 Push monitor 上报 up/down：
#   disk     磁盘使用率（阈值 85%，与 D 组报告 P0-4 一致）
#   matches  今日比赛 0 场且非休赛日（休赛期判定：未来 7 天也 0 场则放行）
#   circuit  数据源熔断状态（读 Redis crawler:data-source:health:*，非 NORMAL 即告警）
#
# 用法：
#   scripts/kuma-checks.sh disk|matches|circuit|all
#   15 * * * * /root/footballforecastsystem/scripts/kuma-checks.sh disk    >> /var/log/football-kuma-checks.log 2>&1
#   */15 * * * * /root/footballforecastsystem/scripts/kuma-checks.sh circuit >> /var/log/football-kuma-checks.log 2>&1
#   0 9,20 * * * /root/footballforecastsystem/scripts/kuma-checks.sh matches >> /var/log/football-kuma-checks.log 2>&1
#
# Push URL 的来源：先在 Kuma UI 里建三个 Push monitor，拿到各自的 push URL 后
# 写进部署目录的 .env.kuma（不要进 Git），形如：
#   KUMA_PUSH_URL_DISK=https://kuma.example/push/xxxx?status=up&msg=OK&ping=
#   KUMA_PUSH_URL_MATCHES=...
#   KUMA_PUSH_URL_CIRCUIT=...
# 未配 URL 时脚本只打印结果、按检查结果退出（仍可当独立 cron 健康检查用）。
# 密码处理：只从 .env 读 MYSQL_ROOT_PASSWORD / REDIS_PASSWORD，不回显、不写日志。
# 注意：本服务器实测 Redis 未设密码（REDIS_PASSWORD 为空）；脚本两种情况都兼容。
set -uo pipefail

export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ENV_FILE="$DEPLOY_DIR/.env"
KUMA_ENV_FILE="$DEPLOY_DIR/.env.kuma"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-football-mysql}"
REDIS_CONTAINER="${REDIS_CONTAINER:-football-redis}"

DISK_THRESHOLD="${DISK_THRESHOLD:-85}"

log() { echo "$(date '+%Y-%m-%d %H:%M:%S %Z') [kuma-checks] $*"; }

# 读 .env 里的单个键（不 source 整个文件）。
env_get() { # $1 = KEY
  [[ -f "$ENV_FILE" ]] || return 0
  grep -E "^$1=" "$ENV_FILE" | tail -1 | cut -d= -f2- | sed -e 's/^[[:space:]]*//;s/[[:space:]]*$//' -e 's/^"//;s/"$//' -e "s/^'//;s/'$//"
}

kuma_push() { # $1 = URL, $2 = up|down, $3 = message
  local url="$1" status="$2" msg="$3"
  if [[ -z "$url" ]]; then log "no push URL configured, result: $status - $msg"; return 0; fi
  curl -fsS -m 10 --get "$url" \
    --data-urlencode "status=$status" --data-urlencode "msg=$msg" --data-urlencode "ping=" \
    >/dev/null 2>&1 || log "WARN: kuma push failed (ignored)"
}

# 加载 .env.kuma（只含 push URL，无敏感信息也可放这里；文件本身不进 Git）。
if [[ -f "$KUMA_ENV_FILE" ]]; then
  # shellcheck disable=SC1090
  set -a; . "$KUMA_ENV_FILE"; set +a
fi

check_disk() {
  local used
  used="$(df -P / | awk 'NR==2 {gsub(/%/,"",$5); print $5}')"
  if [[ -z "$used" ]]; then log "disk: cannot read df"; return 2; fi
  if [[ "$used" -ge "$DISK_THRESHOLD" ]]; then
    log "disk: DOWN ${used}% >= ${DISK_THRESHOLD}%"
    kuma_push "${KUMA_PUSH_URL_DISK:-}" down "disk ${used}% >= ${DISK_THRESHOLD}%"
    return 1
  fi
  log "disk: OK ${used}% < ${DISK_THRESHOLD}%"
  kuma_push "${KUMA_PUSH_URL_DISK:-}" up "disk ${used}%"
  return 0
}

check_matches() {
  local pw db today next7d
  pw="$(env_get MYSQL_ROOT_PASSWORD)"
  db="$(env_get MYSQL_DB)"; db="${db:-football}"
  if [[ -z "$pw" ]]; then log "matches: no MYSQL_ROOT_PASSWORD"; return 2; fi
  if [[ "$(docker inspect -f '{{.State.Running}}' "$MYSQL_CONTAINER" 2>/dev/null)" != "true" ]]; then
    log "matches: mysql container not running"
    kuma_push "${KUMA_PUSH_URL_MATCHES:-}" down "mysql container not running"
    return 1
  fi
  read -r today next7d <<< "$(
    docker exec -i -e MYSQL_PWD="$pw" "$MYSQL_CONTAINER" mysql -uroot -N -B -D"$db" -e \
      "SELECT COUNT(*) FROM crawler_matches WHERE DATE(match_time)=CURDATE(); SELECT COUNT(*) FROM crawler_matches WHERE DATE(match_time) BETWEEN CURDATE() AND DATE_ADD(CURDATE(), INTERVAL 7 DAY);" \
      2>/dev/null | tr '\n' ' '
  )"
  if [[ -z "${today:-}" || -z "${next7d:-}" ]]; then log "matches: query failed"; return 2; fi
  if [[ "$today" -gt 0 ]]; then
    log "matches: OK today=$today next7d=$next7d"
    kuma_push "${KUMA_PUSH_URL_MATCHES:-}" up "today $today matches"
    return 0
  fi
  if [[ "$next7d" -eq 0 ]]; then
    # 未来 7 天也 0 场：休赛期，0 场是正常的，不告警。
    log "matches: OK today=0 but next7d=0 (off-season)"
    kuma_push "${KUMA_PUSH_URL_MATCHES:-}" up "today 0, off-season (next7d 0)"
    return 0
  fi
  log "matches: DOWN today=0 but next7d=$next7d (season running)"
  kuma_push "${KUMA_PUSH_URL_MATCHES:-}" down "today 0 matches, next7d $next7d"
  return 1
}

check_circuit() {
  local rdp args key status bad=0 detail=""
  rdp="$(env_get REDIS_PASSWORD)"
  if [[ -n "$rdp" ]]; then args=(-a "$rdp"); else args=(); fi
  if [[ "$(docker inspect -f '{{.State.Running}}' "$REDIS_CONTAINER" 2>/dev/null)" != "true" ]]; then
    log "circuit: redis container not running"
    kuma_push "${KUMA_PUSH_URL_CIRCUIT:-}" down "redis container not running"
    return 1
  fi
  while IFS= read -r key; do
    [[ -n "$key" ]] || continue
    # 值格式（2026-11-23 实测）：success|failure|...|STATUS|...，第 7 字段为状态；
    # 若 tracker 格式变化导致字段漂移，以 Kuma 里看到的实际值为准再调。
    status="$(docker exec "$REDIS_CONTAINER" redis-cli "${args[@]}" GET "$key" 2>/dev/null | awk -F'|' '{print $7}')"
    if [[ "$status" != "NORMAL" ]]; then
      bad=1; detail="$detail $key=${status:-UNKNOWN}"
    fi
  done < <(docker exec "$REDIS_CONTAINER" redis-cli "${args[@]}" --scan --pattern "crawler:data-source:health:*" 2>/dev/null)
  if [[ "$bad" -eq 1 ]]; then
    log "circuit: DOWN$detail"
    kuma_push "${KUMA_PUSH_URL_CIRCUIT:-}" down "circuit tripped:$detail"
    return 1
  fi
  log "circuit: OK all sources NORMAL"
  kuma_push "${KUMA_PUSH_URL_CIRCUIT:-}" up "all sources NORMAL"
  return 0
}

rc=0
case "${1:-all}" in
  disk)    check_disk; rc=$? ;;
  matches) check_matches; rc=$? ;;
  circuit) check_circuit; rc=$? ;;
  all)     check_disk; r1=$?; check_matches; r2=$?; check_circuit; r3=$?
           [[ "$r1" -eq 0 && "$r2" -eq 0 && "$r3" -eq 0 ]] && rc=0 || rc=1 ;;
  *) echo "usage: $0 disk|matches|circuit|all" >&2; exit 2 ;;
esac
exit "$rc"
