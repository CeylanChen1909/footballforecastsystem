# Uptime Kuma 监控配置（P0-7）

> 适用：footballforecastsystem，服务器 103.212.187.22。
> Kuma 服务定义：`docker-compose.monitoring.yml`（容器 `football-uptime-kuma`，
> 宿主机回环 `127.0.0.1:3001`，数据卷 `uptime-kuma-data`）。
> 首次访问用 SSH 隧道：`ssh -L 3001:127.0.0.1:3001 root@103.212.187.22`，
> 再打开 http://127.0.0.1:3001 建管理员账号。

## 监控项清单

### HTTP 关键字探针（Kuma 内置，间隔 60 秒，重试 3 次）

| # | 名称 | URL（Kuma 容器内视角） | 判定 |
|---|---|---|---|
| 1 | 公网首页 | `https://chenfootball.asia/` | HTTP 200 |
| 2 | 前端健康 | `https://chenfootball.asia/health.json` | HTTP 200 且含 `ok` |
| 3 | 网关 actuator | `http://football-gateway:8082/actuator/health` | HTTP 200 且含 `UP` |
| 4 | 业务 actuator | `http://football-business-service:9000/actuator/health` | HTTP 200 且含 `UP` |
| 5 | 用户 actuator | `http://football-user-service:9001/actuator/health` | HTTP 200 且含 `UP`（部署时验证；D 组报告未核实 9001 actuator 是否生效） |
| 6 | ML 健康 | `http://football-ml-service:5001/health` | HTTP 200 |

说明：3–6 用容器内服务名直连，因为 9000/9001 未发布到宿主机，宿主机视角
探不到；Kuma 已加入 `football-network`。公网项 1–2 走宿主机 nginx，与用户
视角一致；若公网项挂而内网项正常，先查宿主机 nginx / Cloudflare。

### Push 型探针（`scripts/kuma-checks.sh` + 备份脚本推送，Kuma 里建 Push monitor）

| # | 名称 | 来源 | 判定 / 频率 |
|---|---|---|---|
| 7 | 每日备份 | `backup-mysql-docker.sh` 成功后 push `up`，失败 push `down` | 超过 26 小时无 push 即 down（每天 04:00 跑一次，26h 容忍一次失败 + 排查时间） |
| 8 | 磁盘使用率 | `kuma-checks.sh disk`，每小时 | ≥ 85% 即 down（2026-11-23 实测 83%，余量很小） |
| 9 | 今日比赛 0 场 | `kuma-checks.sh matches`，每天 09:00、20:00 | 今日 0 场且未来 7 天有比赛即 down；未来 7 天也 0 场视为休赛期，不告警 |
| 10 | 数据源熔断 | `kuma-checks.sh circuit`，每 15 分钟 | 任一 `crawler:data-source:health:*` 状态 ≠ NORMAL 即 down（含 QUOTA_LIMITED / REQUEST_FAILED / 熔断） |

Push URL 配置：Kuma UI 里建好 4 个 Push monitor 后，把 URL 写进部署目录的
`.env.kuma`（不进 Git），键名 `KUMA_PUSH_URL_BACKUP` / `KUMA_PUSH_URL_DISK` /
`KUMA_PUSH_URL_MATCHES` / `KUMA_PUSH_URL_CIRCUIT`。未配 URL 时脚本只打印
结果、不推送，仍可当独立 cron 检查用。

### 暂不进 Kuma 的项（原因）

- user-service / business-service 无 healthcheck、进程假死：属 P1-4，改 compose
  加 healthcheck 后 Kuma 的 actuator 探针自然能覆盖到"假死"（actuator 挂则 down）。
- 日志 ERROR 告警：无日志聚合（D 组 P2-9），本次不做；先靠探针 + 人工巡检。
- Nacos 断网手动恢复（`docs/ops.md` 已有步骤）：属 P2-10，本次只把"网关 502"
  覆盖进公网探针，自动修复以后再说。

## 告警通道（待 Chen 确认 —— 见本节末尾）

Kuma 在 Settings → Notifications 里配，配好后每个 monitor 勾选该通道。
通道未定之前 Kuma 只记录状态变化、不推送；部署当天务必用 Kuma 的 Test 按钮
验证一次端到端（故意停一个探针或点 Test）。

### 选项 A：邮件

Chen 需提供：
- SMTP 服务器地址与端口（已有 `MAIL_HOST`/`MAIL_USERNAME` 配置的 Gmail SMTP
  可复用：smtp.gmail.com:587 + 发件邮箱 + 应用专用密码；不要用主账号密码）
- 收件邮箱（告警发给谁；可与发件邮箱相同）
- Kuma 里填：SMTP 主机/端口、用户名、密码、发件人、收件人；STARTTLS 按 587 开启

### 选项 B：Telegram

Chen 需提供：
- Bot Token：找 @BotFather 新建 bot 拿到（`123456:ABC-...` 格式）
- Chat ID：给 bot 发一条消息后，用 `https://api.telegram.org/bot<token>/getUpdates`
  查 `chat.id`（个人号是一串数字；群组是负数）
- Kuma 里填：Bot Token、Chat ID；如需 @ 所有人可再配

### 对比（供 Chen 选）

| | 邮件 | Telegram |
|---|---|---|
| 优点 | 已有 Gmail SMTP 配置，现成 | 推送最及时，手机提醒最醒目；D 组倾向二选一 |
| 缺点 | 可能进垃圾箱、提醒弱 | 需 Chen 有 Telegram 并新建 bot |
| 需 Chen 提供的东西 | 收件邮箱（SMTP 凭证已有） | Bot Token + Chat ID |

## 已知局限

- Kuma 与业务同机：宿主机挂则探针同挂，届时告警也发不出。这是本次接受的
  折中（D 组报告已记录），异地探针以后再议；先解决"服务挂了无人知"。
- 备份失败、磁盘、今日 0 场、熔断四项依赖 cron 脚本推送，cron 本身挂了则
  Kuma 只会看到"超时无 push"（配了 26h/对应间隔的超时阈值后会变 down，
  但发现时间取决于阈值）。
