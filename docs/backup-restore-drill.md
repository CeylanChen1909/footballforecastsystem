# MySQL 备份与恢复演练（P0-1）

> 适用：footballforecastsystem，服务器 103.212.187.22，部署目录
> `/root/footballforecastsystem`，库名 `football`，容器 `football-mysql`。
> 备份脚本：`scripts/backup-mysql-docker.sh`（docker exec 方式；宿主机无
> mysqldump 客户端，故不用仓库原有的 `scripts/backup-mysql.sh`）。

## 目标

| 指标 | 目标 | 说明 |
|---|---|---|
| RPO | ≤ 24 小时 | 每日 04:00 全量备份；丢库最多丢一天（以最后一次成功备份为准） |
| RTO | ≤ 2 小时（待首次演练验证） | 从"发现需恢复"到"业务恢复可用"；首次演练后以实测值修正 |
| 保留期 | 本地 7 天 | `/opt/football-backup`，滚动清理；每周同步一份到 Chen 的 Google Drive（见下） |

现状基线（2026-11-23 实测）：库 `football` 仅约 55 MB，单份压缩备份预计个位数 MB；
磁盘 83%（5.0G 空闲），7 天备份占用可忽略。binlog 保留 7 天（`--binlog-expire-logs-seconds=604800`），
全量备份里含 `--source-data=2` 的 binlog 位点注释，将来可做 PITR；本次 P0-1 先只做全量恢复演练。

## 备份是怎么跑的

- cron：`0 4 * * * /root/footballforecastsystem/scripts/backup-mysql-docker.sh >> /var/log/football-backup.log 2>&1`
  （每天 04:00；与 02:00 积分榜、03:00 近期赛程错峰；04:00 无定时采集任务）
- 产物：`/opt/football-backup/football-YYYYMMDD-HHMMSS.sql.gz`，成功后校验
  gzip 完整性 + 非空 + 含 `CREATE TABLE`，失败退出非 0 并记日志。
- 异地同步：每周由 Vivian 把 `/opt/football-backup` 里最新的一份同步到 Chen 的
  Google Drive（"Vivian 备份"同类流程，另行安排）。同步源就是本目录；
  注意本地只留 7 天，同步必须每周跑，否则当周文件可能已被滚动清理。
  备份含用户数据（`t_user` 等），Drive 文件夹仅 Chen 可见，不要分享。
- 监控联动：如配了 Kuma push URL（`.env.kuma` 里 `KUMA_PUSH_URL_BACKUP`），
  备份成功/失败会推送到 Kuma；未配则只看 `/var/log/football-backup.log`。

## 日常检查（每月一次，5 分钟）

```bash
ls -lh /opt/football-backup/            # 应有近 7 天的文件，每天一个
tail -20 /var/log/football-backup.log   # 最近一次应为 done，retained 7
gzip -t /opt/football-backup/football-<最新>.sql.gz && echo OK
```

备份连续 2 天失败 = P0 故障：先看日志（MySQL 容器是否在跑、磁盘是否满），
修好后手动补跑一次 `bash scripts/backup-mysql-docker.sh`。

## 恢复演练（每月一次，隔离环境做，绝不碰生产库）

第一次演练：等备份跑起来、有至少 2 天成功备份后再约时间（待 Chen 定）。

步骤（全程在服务器上，但恢复目标是一次性临时容器）：

```bash
cd /root/footballforecastsystem
set -a; . ./.env; set +a

# 1. 选一份备份（用最新的一份；也可抽一张旧的验证保留期内的文件可用）
BACKUP=$(ls -t /opt/football-backup/football-*.sql.gz | head -1)
echo "restore from: $BACKUP"

# 2. 起一次性 MySQL 8.0 临时容器（独立卷、独立端口，不连业务网络）
docker run -d --name football-mysql-restore \
  -e MYSQL_ROOT_PASSWORD="$MYSQL_ROOT_PASSWORD" \
  -e MYSQL_DATABASE=football \
  -p 127.0.0.1:13306:3306 \
  -v football-restore-data:/var/lib/mysql \
  mysql:8.0 \
  --default-authentication-plugin=mysql_native_password \
  --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci

# 3. 等 healthy（最多 2 分钟）
for i in $(seq 1 24); do
  docker exec football-mysql-restore mysqladmin ping -h localhost -uroot -p"$MYSQL_ROOT_PASSWORD" --silent && break
  sleep 5
done

# 4. 导入备份
gzip -dc "$BACKUP" | docker exec -i football-mysql-restore mysql -uroot -p"$MYSQL_ROOT_PASSWORD"

# 5. 验证：表数量与关键表行数，对照生产库同一时刻的只读查询
docker exec football-mysql-restore mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -Dfootball -e \
  "SELECT COUNT(*) AS tables FROM information_schema.tables WHERE table_schema='football';"
docker exec football-mysql-restore mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -Dfootball -e \
  "SELECT (SELECT COUNT(*) FROM crawler_matches) AS matches, (SELECT COUNT(*) FROM t_user) AS users, (SELECT COUNT(*) FROM t_match_prediction) AS predictions;"
# 生产对照（只读）：
docker exec football-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -Dfootball -e \
  "SELECT (SELECT COUNT(*) FROM crawler_matches) AS matches, (SELECT COUNT(*) FROM t_user) AS users, (SELECT COUNT(*) FROM t_match_prediction) AS predictions;"
# 行数应一致（演练期间生产还在写入，比赛表差几行以内属正常；用户表应完全一致）

# 6. 计时并记录：从第 4 步开始到第 5 步验证通过的时间 = 实测 RTO 输入
# 7. 清理临时容器与卷（演练结束必须清掉，否则占盘）
docker rm -f football-mysql-restore
docker volume rm football-restore-data
```

记录每次演练：日期、备份文件、恢复耗时、行数对照结果、RTO 是否 ≤ 2 小时。
演练记录存在本文件末尾（追加一节）或运维报告里。

## 真实故障时的恢复（仅当生产库损坏/丢失时）

与演练的区别：恢复目标是生产容器本身，且恢复前必须先停业务写：

1. 先做一次"抢救性备份"尝试（若 MySQL 还能起来）：`bash scripts/backup-mysql-docker.sh`，
   产物另存，不要覆盖原目录。
2. 停业务服务（只停网关/业务/用户服务，不停 MySQL）：
   `docker compose -f docker-compose.prod.yml stop football-gateway football-business-service football-user-service`
3. 确认 `mysql_data` 卷现状；若卷本身损坏才重建卷，否则直接在现有 MySQL 上导入：
   `gzip -dc /opt/football-backup/football-<选定>.sql.gz | docker exec -i football-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD"`
   （备份含 `--databases football`，导入会重建库内表；若库已损坏到无法导入，
   先 `docker volume rm` 重建空卷再导入——此操作不可逆，执行前须两人确认。）
4. 按演练第 5 步验证行数；验证通过后按顺序起服务：
   `docker compose -f docker-compose.prod.yml up -d football-gateway football-business-service football-user-service`
5. 验收：公网首页、网关 actuator、今日比赛数；全程计时，事后复盘 RTO/RPO 实测值。

## 禁区

- 演练恢复一律用临时容器，绝不直接往生产 `football-mysql` 导入"练手"。
- 禁止 `docker volume prune` / `system prune -a` / 删除 `/var/lib/docker` 下任何内容。
- 备份目录 `/opt/football-backup` 不要挂到任何公开静态目录。
