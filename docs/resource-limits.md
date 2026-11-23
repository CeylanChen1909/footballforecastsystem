# 4c4g 资源限制核对与应用方案（P0-8）

> 结论先行：**不要原样应用 `docker-compose.4c4g.yml`**。2026-11-23 实测 3 个
> 服务已超过该文件的内存上限，原样应用会被 OOM-kill。改用本目录的
> `docker-compose.limits.yml`（在 4c4g 基础上只上调超限的三项 + 网关余量），
> 部署命令见下。

## 核对：服务器 compose 文件与分支一致

- 服务器 `/root/footballforecastsystem/docker-compose.prod.yml` 与
  `docker-compose.4c4g.yml`，与 ff-p0-optimizations 分支（HEAD 6223f2e）
  逐字节一致（2026-11-23 diff 验证）。问题不在文件版本，在于 4c4g
  从未被应用：8 个容器 `docker inspect` 的 Memory / NanoCpus 全为 0。
- 4c4g 文件按"4G 内存主机"设计，但本机实测 7.8 GiB
 （`docker stats` LIMIT 7.755GiB，`free` total 7.8Gi），且宿主机上还有
  nginx / MaiBot / SillyTavern / mihomo 等其他进程在跑。

## 核对：实测用量 vs 4c4g 上限（2026-11-23 约 14:00，`docker stats`）

| 服务 | 实测内存 | 4c4g 上限 | 结论 | 调整后上限 |
|---|---|---|---|---|
| football-mysql | 321.9 MiB | 768m | ✅ 够用 | 768m（不变） |
| football-redis | 3.8 MiB | 128m | ✅ | 128m（不变） |
| football-nacos | 533.8 MiB | 384m | ❌ 超 150 MiB；CPU 瞬时约 48% | 768m / cpus 0.60 |
| football-gateway | 333.3 MiB | 384m | ⚠️ 只剩 50 MiB 余量 | 512m |
| football-user-service | 450.4 MiB | 384m | ❌ 超 66 MiB | 512m |
| football-business-service | 821.6 MiB | 640m | ❌ 超 182 MiB | 1024m |
| football-ml-service | 43.2 MiB | 640m | ✅ | 640m（不变） |
| football-frontend | 4.7 MiB | 128m | ✅ | 128m（不变） |
| **合计** | **约 2512 MiB** | **3456 MB** | | **4480 MB（约 4.4 GiB）** |

补充说明：
- business-service 堆是 4c4g 的 `-Xmx512m`，实测 RSS 822 MiB 说明堆外约 300 MiB；
  若日后调大堆，`mem_limit` 必须同步上调。nacos 同理（JVM `-Xmx256m` 但 RSS 534 MiB）。
- CPU 上限合计 4.80（4c4g 原值 4.55），超过 4 核无碍：CPU limit 只是上限
  不是预留，平时各服务 CPU 均 <1%（nacos 例外，瞬时 48% 是其上限要放宽到
  0.60 的原因；nacos CPU 偏高本身值得另查，不在本 P0 范围）。
- 内存合计 4480 MB + Kuma 256m ≈ 4.6 GiB，主机剩余约 3.2 GiB 供其他进程，
  仍有余量。Swap 2G 目前已用 1.2G，属偏紧信号，扩容磁盘/内存时一并评估。

## 应用方案

部署命令（三个文件叠加，后加载优先）：

```bash
cd /root/footballforecastsystem
docker compose -f docker-compose.prod.yml -f docker-compose.4c4g.yml -f docker-compose.limits.yml config > /tmp/football-compose.rendered.yml
# 人工核对 rendered 文件：8 个服务的 mem_limit / cpus 与上表"调整后上限"一致
docker compose -f docker-compose.prod.yml -f docker-compose.4c4g.yml -f docker-compose.limits.yml up -d
```

为什么是三个文件而不是直接改 `docker-compose.4c4g.yml`：4c4g 是上游部署文档
（`docs/deploy-4c4g.md`）引用的标准文件，保持原样可让后人对照；修正量以独立
的 `docker-compose.limits.yml` 叠加，改动最小、可逆、原因写在文件头注释里。
若 Chen 倾向直接改 4c4g，复审时定——效果等价。

生效方式：改 limits 必须重建容器（`up -d` 会重建全部 8 个），有短暂停机，
约 5–10 分钟。部署窗口与回滚见 p0 运维报告 runbook；低峰时段执行
（建议凌晨 04:30–05:30，备份 04:00 跑完、早高峰未到；避开 02:00/03:00 爬虫）。

## 验证（重建后逐项核实）

```bash
docker compose -f docker-compose.prod.yml -f docker-compose.4c4g.yml -f docker-compose.limits.yml ps
# 8 个容器全部 Up；mysql/redis/nacos/gateway/frontend/ml 应为 (healthy)
docker inspect --format '{{.Name}} Memory={{.HostConfig.Memory}} NanoCpus={{.HostConfig.NanoCpus}}' $(docker ps -q)
# Memory 应为：mysql 805306368、redis 134217728、nacos 805306368、gateway 536870912、
# user 536870912、business 1073741824、ml 671088640、frontend 134217728（字节数）
docker stats --no-stream
curl -fsS http://127.0.0.1:8082/actuator/health   # {"status":"UP"}
curl -fsS http://127.0.0.1:3000/health.json      # {"status":"ok"}
curl -fsS "http://127.0.0.1/api/crawler/matches/date/$(date +%F)" | head -c 300
docker exec football-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -Dfootball \
  -e "SELECT COUNT(*) FROM crawler_matches WHERE DATE(match_time)=CURDATE();"
```

## 回滚

任一服务出现 `OOMKilled=true` 或起不来：

```bash
# 只回滚限制，不动代码/数据：去掉 limits 覆盖文件重建
docker compose -f docker-compose.prod.yml -f docker-compose.4c4g.yml up -d
# 若连 4c4g 上限也导致 OOM（不应发生，但以防万一）：
docker compose -f docker-compose.prod.yml up -d
docker inspect <容器> --format '{{.State.OOMKilled}}'   # 确认回滚后为 false
```

回滚不删卷、不删镜像、不动数据库。事后把出问题的服务上限再上调一档
（如 business 1024m → 1280m），改 `docker-compose.limits.yml` 后择机重上。

## 上线后观察（一周）

- 每天看一次 `docker stats`：任一服务内存持续贴近上限 → 上调该服务 256m。
- `docker inspect` 查 `OOMKilled`：出现 true 即按上节回滚并上调。
- 一周无 OOM、无重启、无业务异常，P0-8 视为落地。
