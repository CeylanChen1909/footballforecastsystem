# ChenFootball 生产运行手册

## 发布前

1. 轮换所有曾经进入 Git 历史的凭证，并确认 `git log --all -- .env .env.prod` 不再有结果。可先运行 `scripts/rotate-secrets.ps1` 生成新的本地模板；历史重写必须由仓库所有者明确确认后执行 `-RewriteHistory -Confirm`，不能在应用发布脚本中隐式改写。
2. 设置 `ML_INTERNAL_TOKEN`，并让 `ML_REQUIRE_INTERNAL_AUTH=true`；业务服务的 `PYTHON_INFERENCE_TOKEN` 必须一致。
3. 设置非默认的 `MYSQL_ROOT_PASSWORD`、`MYSQL_PASSWORD`、`REDIS_PASSWORD` 和 32 字节以上 `JWT_SECRET`。
4. 生产设置 `SECURITY_REFRESH_TOKEN_COOKIE_ONLY=true`；refresh token 通过 `HttpOnly; SameSite=Lax` Cookie 轮换，前端不再需要读取它。若前置代理跨域部署，必须同时验收 Cookie 的 Secure/CORS/CSRF 配置。
5. 生产 Compose 默认不信任转发头。仅在前端 Nginx/负载均衡会清洗并重写 `X-Forwarded-For` 时，才显式设置 `GATEWAY_TRUST_PROXY_HEADERS=true`；网关直连公网时保持 false。
5. 在 CI 中运行 `scripts/quality-check.ps1`、依赖扫描、镜像扫描和 secret scan。
6. 先运行只读的 `scripts/schema-preflight.ps1`，检查表计数、来源分布及重复 fixture/external ID。
7. 再在备份副本执行 `sql/migrations`，确认迁移结果，再发布应用。
8. 生产必须设置 `APP_RUNTIME_DDL_ENABLED=false`、`APP_SCHEMA_REQUIRE_MIGRATIONS=true`；服务缺少迁移表时应拒绝启动，而不是自动修改结构。
9. Card Lab/幻想远征已从产品主链路下线，生产默认 `CARD_WORKSHOP_ENABLED=false`、`CARD_ROGUE_ENABLED=false`；如需恢复，必须单独完成数据迁移、内容审核和容量验收。

迁移执行：Windows 使用 `scripts/migration-check.ps1` / `scripts/apply-migrations.ps1 -DryRun`，Ubuntu 使用 `bash scripts/migration-check.sh` / `bash scripts/apply-migrations.sh --dry-run`；确认输出后执行正式迁移。脚本会在 `schema_migrations` 中记录版本、SHA-256、操作者和时间，并拒绝已执行版本的内容漂移。`V2026082402__production_source_scope_cleanup.sql` 会清理旧数据源比赛，必须先备份，再显式执行 `-AllowDestructive` 或 `--allow-destructive`，完成后复核 `crawler_matches.source` 分布。

## 备份与恢复

Windows 使用 `scripts/backup-mysql.ps1`，Ubuntu 使用 `bash scripts/backup-mysql.sh` 创建带事务一致性的备份。生产环境至少保留每日全量和 binlog/PITR；每月在隔离环境恢复一次并记录 RTO/RPO。不要把备份目录挂载到公开静态目录。

## 故障处理

- `SOURCE_LIMITED` / `SYNC_FAILED`：停止手动重复触发，查看数据源状态和任务历史，确认主源额度/封禁后再补数。
- `MODEL_UNAVAILABLE`：保持 ELO+Poisson 透明基线，不要手工把候选模型复制成 active。
- Agent 成本异常：先将 `AGENT_RATE_LIMIT_FAIL_CLOSED=true`，再检查供应商账单、fallback 和请求量。
- 数据异常：禁止直接删除比赛表；先导出问题批次、核对 `source/external_match_id/fixture_id`，再执行可回滚修复。
- 公开赛程/积分榜 GET 接口只读数据库快照；补数必须走管理员任务接口，避免页面刷新放大外部额度消耗。
- 赛事详情手动刷新需要登录，并对同一场比赛设置短期缓存；批量增强数据由定时任务负责。

## 上线验收

必须通过密钥扫描、迁移预检、登录/刷新/撤销测试、数据源回放去重测试、模型时间切分评估、Agent 额度和注入测试、备份恢复演练以及移动端核心流程 E2E。部署后运行 `scripts/production-smoke.ps1`；若要验收登录链路，设置 `SMOKE_ACCESS_TOKEN` 并加 `-RequireAuth`。
