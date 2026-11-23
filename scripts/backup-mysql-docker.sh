#!/usr/bin/env bash
# footballforecastsystem — 每日 MySQL 全量备份（docker exec 方式）
#
# 为什么是这个脚本，而不是仓库已有的 scripts/backup-mysql.sh？
#   已有的 backup-mysql.sh 要求宿主机安装 mysqldump 客户端，但服务器实测
#   `which mysqldump` 无结果，宿主机上跑不起来。本脚本改用
#   `docker exec football-mysql mysqldump`，不依赖宿主机客户端。
#
# 用法（部署后由 cron 调用，见 docs/backup-restore-drill.md 与 runbook）：
#   0 4 * * * /root/footballforecastsystem/scripts/backup-mysql-docker.sh >> /var/log/football-backup.log 2>&1
#
# 行为：
#   - 对 football 库做 --single-transaction 一致性全量备份，经 gzip 压缩后落盘
#   - 备份文件：/opt/football-backup/football-YYYYMMDD-HHMMSS.sql.gz
#   - 保留 7 天滚动清理（mtime 超过 7 天的删除）
#   - 校验：gzip 完整性 + 文件非空 + 含 CREATE TABLE
#   - 失败时退出非 0（cron 会留痕；如配了 Kuma push 也会报 down）
#   - 成功后如环境变量 KUMA_PUSH_URL_BACKUP 已设置，向 Uptime Kuma push 上报
#
# 密码处理：只从部署目录 .env 读 MYSQL_ROOT_PASSWORD，不回显、不写日志。
# 恢复方法见 docs/backup-restore-drill.md；不要直接在生产库上练恢复。
set -euo pipefail

export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ENV_FILE="${BACKUP_ENV_FILE:-$DEPLOY_DIR/.env}"
BACKUP_DIR="${BACKUP_DIR:-/opt/football-backup}"
RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-7}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-football-mysql}"
KUMA_PUSH_URL="${KUMA_PUSH_URL_BACKUP:-}"

log() { echo "$(date '+%Y-%m-%d %H:%M:%S %Z') [backup] $*"; }
fail() { log "ERROR: $*"; }

kuma_push() { # $1 = up|down, $2 = message
  [[ -n "$KUMA_PUSH_URL" ]] || return 0
  curl -fsS -m 10 --get "$KUMA_PUSH_URL" \
    --data-urlencode "status=$1" --data-urlencode "msg=$2" --data-urlencode "ping=" \
    >/dev/null 2>&1 || log "WARN: kuma push failed (ignored)"
}

mkdir -p "$BACKUP_DIR" || { fail "cannot create $BACKUP_DIR"; exit 2; }

# 单实例保护：备份重叠跑没有意义，直接跳过（cron 下一次还会跑）。
exec 9>"$BACKUP_DIR/.backup.lock"
if ! flock -n 9; then log "another backup is running, skip"; exit 0; fi

# 磁盘预检：备份目录所在文件系统至少留 1G（当前库仅约 55MB，压缩后个位数 MB，
# 1G 是很宽松的底线，主要防磁盘 83% 已满的场景下备份把盘写爆）。
free_kb="$(df -P -k "$BACKUP_DIR" | awk 'NR==2 {print $4}')"
if [[ "$free_kb" -lt 1048576 ]]; then
  fail "free space under ${BACKUP_DIR} is ${free_kb}KB (< 1G), abort"
  kuma_push down "backup aborted: disk free ${free_kb}KB"
  exit 2
fi

# 读库名与 root 密码（只读这两个键，不 source 整个 .env，避免意外导出）。
if [[ -f "$ENV_FILE" ]]; then
  # 只去首尾空白与一层引号，不动值中间的字符（密码可能含空格）。
  MYSQL_DB="$(grep -E '^MYSQL_DB=' "$ENV_FILE" | tail -1 | cut -d= -f2- | sed -e 's/^[[:space:]]*//;s/[[:space:]]*$//' -e 's/^"//;s/"$//' -e "s/^'//;s/'$//")"
  MYSQL_ROOT_PASSWORD="$(grep -E '^MYSQL_ROOT_PASSWORD=' "$ENV_FILE" | tail -1 | cut -d= -f2- | sed -e 's/^[[:space:]]*//;s/[[:space:]]*$//' -e 's/^"//;s/"$//' -e "s/^'//;s/'$//")"
fi
MYSQL_DB="${MYSQL_DB:-football}"
if [[ -z "${MYSQL_ROOT_PASSWORD:-}" ]]; then
  fail "MYSQL_ROOT_PASSWORD not found in $ENV_FILE"
  kuma_push down "backup aborted: no MYSQL_ROOT_PASSWORD"
  exit 2
fi

if [[ "$(docker inspect -f '{{.State.Running}}' "$MYSQL_CONTAINER" 2>/dev/null)" != "true" ]]; then
  fail "container $MYSQL_CONTAINER is not running"
  kuma_push down "backup aborted: mysql container not running"
  exit 2
fi

stamp="$(date +%Y%m%d-%H%M%S)"
target="$BACKUP_DIR/football-$stamp.sql.gz"
tmp_target="$target.tmp"

log "start backup db=$MYSQL_DB -> $target"
# --source-data=2 把 binlog 位点以注释写进备份，供将来 PITR 用（当前只做全量恢复演练）。
if docker exec -i -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" "$MYSQL_CONTAINER" \
    mysqldump -uroot \
    --single-transaction --quick --routines --events --triggers --hex-blob \
    --source-data=2 --set-gtid-purged=OFF --databases "$MYSQL_DB" \
  | gzip -9 > "$tmp_target"; then
  dump_ok=1
else
  dump_ok=0
fi

if [[ "$dump_ok" != "1" ]]; then
  rm -f "$tmp_target"
  fail "mysqldump failed"
  kuma_push down "mysqldump failed"
  exit 3
fi

# 校验：gzip 完整 + 非空 + 含表结构（空库的 schema 也有几十 KB，10KB 是下限）。
if ! gzip -t "$tmp_target" 2>/dev/null; then
  rm -f "$tmp_target"; fail "gzip integrity check failed"; kuma_push down "gzip check failed"; exit 3
fi
size_bytes="$(wc -c < "$tmp_target")"
# 注意：不能用 `gzip -dc | grep -q`——grep -q 首行命中即退出，gzip 写关闭的
# 管道会收 SIGPIPE；在 `set -o pipefail` 下整个管道返回 141，即使转储完全
# 正常也会误判失败（2026-11-23 部署实测：4.2MB 有效转储、42 个 CREATE TABLE
# 仍报失败）。grep -c 会读完整流，不提前退出，是可靠的计数检查。
create_count="$(gzip -dc "$tmp_target" | grep -c "CREATE TABLE" || true)"
if [[ "$size_bytes" -lt 10240 ]] || [[ "$create_count" -eq 0 ]]; then
  rm -f "$tmp_target"; fail "backup unexpectedly small (${size_bytes}B) or no CREATE TABLE"
  kuma_push down "backup too small (${size_bytes}B)"
  exit 3
fi

mv "$tmp_target" "$target"
log "backup written: $target (${size_bytes}B)"

# 保留 7 天滚动清理。
deleted="$(find "$BACKUP_DIR" -maxdepth 1 -name 'football-*.sql.gz' -type f -mtime +"$RETENTION_DAYS" -print -delete | wc -l)"
[[ "$deleted" -gt 0 ]] && log "pruned $deleted file(s) older than $RETENTION_DAYS days"
remaining="$(find "$BACKUP_DIR" -maxdepth 1 -name 'football-*.sql.gz' -type f | wc -l)"
log "done: $remaining backup(s) retained in $BACKUP_DIR"
kuma_push up "backup ok ${size_bytes}B, ${remaining} retained"
exit 0
