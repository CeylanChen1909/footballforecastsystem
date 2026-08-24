# 足球比赛结果预测系统 (Football Match Forecast System)

基于 Spring Boot 3 + Spring Cloud Alibaba 的足球比赛结果预测系统，集成 XGBoost 机器学习模型预测比赛结果。

---

## 项目架构（当前实际结构）

```
┌────────────┐      /api/*      ┌────────────────────┐
│  前端 Vue3  │ ──────────────▶ │ football-gateway    │  :8082
│  (Vite)     │                 │ (路由/CORS/限流)     │
└────────────┘                 └──────┬─────────────┘
                                      │ lb:// (Nacos 注册发现)
                    ┌─────────────────┴──────────────────┐
                    │ football-user-service   :9001       │
                    │ football-business-service :9000     │
                    │   ├─ match / team / news / prediction
                    │   ├─ crawler / datasync / agent / proxy
                    │   └─ video / config / stats
                    │ football-common (共享 DTO/工具/异常处理)
                    └─────────────────┬──────────────────┘
                                      │
              MySQL :3306 · Redis :6379 · Nacos :8848 · Python ML :5001
```

- **football-gateway** (`:8082`)：Spring Cloud Gateway，统一路由 `/api/**`
- **football-user-service** (`:9001`)：邮箱验证码注册、密码登录（BCrypt）、登录失败验证码、收藏、角色管理
- **football-business-service** (`:9000`)：比赛、球队、资讯、预测、爬虫、数据同步、Agent、代理接口（原 match/news/team/prediction/sync/crawler 等独立服务已合并至此）
- **football-common**：共享 DTO、JWT、全局异常处理、Redis 缓存
- **football-ml-service/** (`:5001`)：混合预测推理服务（Flask，XGBoost + Logistic + ELO/Poisson）

## 技术栈

| 层次 | 技术 |
|------|------|
| 前端 | Vue3 + Element Plus + Vite + Axios + Pinia |
| 网关 | Spring Cloud Gateway 2023.0.x |
| 微服务 | Spring Boot 3.2 + Spring MVC |
| 注册/配置 | Nacos 2.x（standalone，:8848） |
| 数据库 | MySQL（:3306，库名 `football_forecast`） |
| 缓存 | Redis（:6379） |
| 机器学习 | Python 3 + XGBoost / Logistic / ELO / Poisson，CatBoost challenger + Flask（:5001） |
| 认证 | JWT（jjwt 0.12），密码 BCrypt（兼容旧 SHA-256 自动升级） |
| 邮箱验证 | SMTP 邮件验证码；本地未配置 SMTP 时可写入 user-service 开发日志 |

## 快速启动

部署到 4 核 4G Ubuntu 云服务器请阅读：[4 核 4G 云服务器部署教程](docs/deploy-4c4g.md)。教程包含 Docker Compose 资源限制、生产密钥、数据库迁移、HTTPS、备份和回滚流程。

### 前置条件

- JDK 17+
- Maven 3.8+（或使用项目自带 `mvnw`）
- MySQL（本机或 docker-compose）已导入 `sql/football_forecast.sql`
- Redis
- Nacos（standalone 模式，:8848）
- Python 3 + `pip install -r football-ml-service/requirements.txt`（可选，用于 ML 推理）

### 启动步骤

```bash
# 1. 导入数据库（首次）
mysql -uroot -p < sql/football_forecast.sql

# 已有数据库升级（先备份，再预览和执行版本化迁移）
$env:MYSQL_PASSWORD='...'; .\scripts\migration-check.ps1; .\scripts\apply-migrations.ps1 -DryRun
.\scripts\apply-migrations.ps1

# 若迁移报告昵称或埋点 event_id 存在重复，先按业务确认并清理重复记录，
# 再重新执行 schema_hardening；迁移不会静默改名或删除数据。

# 2. 构建
mvn clean package -DskipTests
# 或 Windows: mvnw.cmd clean package -DskipTests

# 3. 启动基础组件：MySQL / Redis / Nacos / Python 推理
# 4. 启动微服务（每个服务一个终端，或直接 IDEA 运行）
java -jar football-gateway/target/football-gateway-1.0.0-SNAPSHOT.jar
java -Djava.net.preferIPv6Addresses=true -Dsun.net.inetaddr.ttl=60 -jar football-user-service/target/football-user-service-1.0.0-SNAPSHOT.jar
java -jar football-business-service/target/football-business-service-1.0.0-SNAPSHOT.jar
cd football-ml-service
python app.py

# 提交前运行本地质量门禁（语法、后端编译、前端构建与路由 smoke）
powershell -ExecutionPolicy Bypass -File scripts/quality-check.ps1

# 5. 前端
cd frontend && npm install && npm run dev   # http://localhost:5173
```

> Windows 下可用 `scripts/start.bat`（构建 + 启动全部服务）。

### 环境变量（密钥务必走环境变量注入）

| 变量 | 说明 |
|------|------|
| `MYSQL_HOST/PORT/DB/USER/PASSWORD` | MySQL 连接（默认主机 127.0.0.1:3306、库 football_forecast、用户 root；密码必须通过 `.env`/环境变量注入） |
| `JWT_SECRET` | JWT 签名密钥（生产必换） |
| `OPENROUTER_API_KEY` | OpenRouter 密钥 |
| `API_FOOTBALL_KEY` | API-Football 密钥 |
| `JUHE_API_KEY` | 聚合数据足球 API 密钥 |
| `DEEPSEEK_API_KEY` | DeepSeek 密钥（可选） |
| `SCNET_API_KEY` | SCNet OpenAI 兼容接口密钥（可选；默认模型 `GLM-5-Base`） |
| `SCNET_BASE_URL/SCNET_MODEL/SCNET_MODELS` | SCNet 接口地址与模型策略，默认 `https://api.scnet.cn/api/llm/v1` / `GLM-5-Base` |
| `NACOS_ADDR` | Nacos 地址（默认 127.0.0.1:8848） |
| `CRAWLER_BASE_URL` | 爬虫接口地址（已内聚到 business-service，默认 http://127.0.0.1:9000） |
| `MAIL_HOST/MAIL_PORT/MAIL_USERNAME/MAIL_PASSWORD` | SMTP 邮箱配置；用于注册和密码找回验证码 |
| `MAIL_SMTP_STARTTLS/MAIL_SMTP_SSL_ENABLE` | SMTP 加密方式；587 通常为 STARTTLS，465 通常为 SSL |
| `EMAIL_VERIFICATION_ENABLED` | 是否启用邮箱验证，默认 true |
| `EMAIL_VERIFICATION_CONSOLE_MODE` | SMTP 未配置时是否将验证码写入开发日志；生产环境应设为 false |
| `ALLOW_NON_POINT_IN_TIME_API_TRAINING` | 仅在完成 API-Football 历史快照后才可设为 true；默认 false，防止赛季累计统计造成训练时间泄漏 |
| `UNDERSTAT_ENRICHMENT_ENABLED` | 使用 Understat 公开赛季数据补充历史逐场 xG/xGA；只作为训练增强源，不替代主爬虫赛程 |
| `UNDERSTAT_CACHE_TTL_HOURS` / `UNDERSTAT_MATCH_DETAIL_LIMIT` | Understat 缓存有效期与每赛季比赛详情请求上限；详情上限默认 4，避免无界抓取 |
| `UNDERSTAT_MAX_REQUESTS_PER_RUN` / `UNDERSTAT_SEASONS` | 运行时 xG 导入每轮最多请求数与赛季列表；结果写入本地供应商缓存，失败会记录状态并降级 |

密钥集中放在项目根目录 `.env`（已被 .gitignore 忽略，禁止提交）。如果 `.env` 曾经进入 Git 历史，必须先轮换所有密钥，再使用 `git filter-repo` 或 BFG 清理历史；仅删除工作区文件不能解决泄漏。启动时需手动加载，例如：

生产数据库结构不再依赖服务启动时静默建表：先执行 `scripts/migration-check.ps1`，再用 `scripts/apply-migrations.ps1 -DryRun` 预览、备份后正式执行。生产设置 `APP_RUNTIME_DDL_ENABLED=false` 与 `APP_SCHEMA_REQUIRE_MIGRATIONS=true`，迁移缺表会快速失败；本地默认仍保留旧库兼容 DDL。

`V2026082302__production_match_scope.sql` 会把 `crawler_matches` 收敛到生产支持的八个联赛，并清理孤立的预测/详情快照；这是有意的数据清理迁移，正式执行前必须备份并先在副本验证。

上线前可运行 `scripts/production-smoke.ps1 -BaseUrl https://你的域名` 检查网关、主爬虫和 Agent 健康接口；`frontend` 的构建会自动执行路由 smoke、首页包体和全局性能预算。

Agent 的默认通道和模型白名单由管理员后台配置；API Key 只允许通过服务端环境变量注入，不会保存到数据库。

```bash
# Linux
set -a; source .env; set +a
# Windows PowerShell
Get-Content .env | ForEach-Object { if ($_ -match '^\s*([^#][^=]*)=(.*)$') { [Environment]::SetEnvironmentVariable($matches[1].Trim(), $matches[2].Trim(), 'Process') } }
```

### 注册与登录

- 新用户使用邮箱注册，邮箱验证码验证成功后设置昵称和密码；邮箱即登录账号，昵称仅用于展示。
- 登录连续失败 3 次后会要求完成算术验证码，接口同时按 IP 和账号限流。
- 本地未配置 SMTP 时，验证码不会返回给浏览器，只会写入 `football-user-service` 日志；生产环境请配置真实 SMTP。
- 生产 Docker 部署会将根目录 `.env` 中的 SMTP 参数透传到用户服务；修改后执行 `docker compose -f docker-compose.prod.yml up -d --build football-user-service football-gateway`。

## 主要接口

| 模块 | 前缀 | 说明 |
|------|------|------|
| 用户 | `/api/users/*` | 注册/登录/我的/收藏/批量用户名 |
| 认证辅助 | `/api/users/email/verification-code`、`/api/users/captcha` | 邮箱验证码与登录安全挑战 |
| 行为埋点 | `/api/analytics/events` | 页面与关键行为事件 |
| 比赛 | `/api/matches/*`、`/api/crawler/matches/*` | 今日/按日期/详情/联赛 |
| 资讯（兼容接口） | `/api/news/*` | 保留历史聚合接口；前台主流程已切换为赛事资料 |
| 预测 | `/api/predictions/*` | 今日/热门/按比赛/历史/统计/预测 |
| 球队 | `/api/teams/*`、`/api/crawler/teams/*` | 详情/联赛/搜索 |
| 代理 | `/api/proxy/*` | H2H/预测/分析 |
| 管理 | `/api/admin/*` | 比赛维护、用户运营、Agent 模型配置、审计日志；旧资讯/视频接口仅保留兼容 |

Card Lab 与幻想远征接口默认关闭（`CARD_WORKSHOP_ENABLED=false`、`CARD_ROGUE_ENABLED=false`），旧地址只保留前端兼容重定向；重新启用前需完成独立迁移与审核验收。

## 常见问题

- **登录报"系统错误"**：检查数据库是否已导入最新 `sql/football_forecast.sql`（含 `t_user` 表与种子数据）。
- **接口 404**：确认网关路由（`football-gateway` 的 application.yml）与后端 Controller 路径一致。
- **错误响应语义**：业务异常 422、参数错误 400、资源不存在 404、方法不支持 405、未知异常 500；响应体 `{success:false, message, data:{code,message}}`。
### 预测质量流水线

- 训练默认使用 `football-data` 的最近三个赛季缓存/数据，并按时间顺序构建 point-in-time 特征；训练前会去重，生产推理不会读取目标比赛的赛后统计。
- `HistoricalBackfillService` 提供管理员接口 `POST /api/crawler/task/backfill?from=2025-08-01&to=2025-08-31&maxDays=31&resume=true`，按日期复用主爬虫源、数据库幂等写入并保存断点；`GET /api/crawler/task/backfill/status` 查看进度。
- 预测质量分为 `FULL`、`LIMITED`、`INSUFFICIENT`：完整样本使用生产模型，有限样本使用可解释的 ELO+Poisson 基线，无历史样本才拒绝预测。
- 训练会在 XGBoost + Logistic 基础上可选训练 CatBoost challenger（`ENABLE_CATBOOST_CANDIDATE=true`，依赖 `catboost`），只有在验证集优于现有配方且通过 ELO、平局召回、稳定性、上一版本等门槛时才会进入候选/生产模型；否则保留候选文件，不替换线上模型。
- 训练默认读取 Understat 的历史逐场 xG/xGA 快照（英超、西甲、德甲、意甲、法甲），并写入 `football-ml-service/data_cache/understat-*.json`；业务服务也会将已匹配的 xG 导入 `t_match_detail_snapshot(detail_type='xg', source='understat')`，供滚动特征和 Agent 读取。目标比赛的赛后数据只会在目标行生成后进入滚动历史，避免泄漏。首发、伤停和赔率只有在 API-Football 的赛前快照状态为 `NORMAL` 且 `fetched_at < kickoff` 时才会进入训练/推理；没有可靠数据时明确标记缺失，不用模型猜测。
