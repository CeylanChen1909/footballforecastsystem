# 4 核 4G 云服务器部署教程

本文按当前仓库的 `docker-compose.prod.yml` 编写，目标环境为一台带公网 IPv4 的 Ubuntu 22.04/24.04 64 位服务器：4 vCPU、4 GB RAM、至少 40 GB SSD。部署方式是单机 Docker Compose，MySQL、Redis、Nacos、三个 Java 服务、ML 服务和前端全部运行在同一台服务器上。

## 0. 先确认资源和边界

4G 内存可以运行当前项目，但不适合同时运行编译、训练、数据库、Nacos 和大量 Agent 请求。生产环境应使用仓库提供的 `docker-compose.4c4g.yml`，它会把 Java、MySQL、Nacos、Gunicorn 的运行时内存压在约 3.4 GB 以内，并把 ML 服务的 Gunicorn worker 从 2 个降为 1 个。

这套部署适合个人项目、小流量简历展示和内部使用，不适合高并发商业流量。训练模型不要在这台机器上执行，放到离线机器训练后只上传 `football-ml-service/models/`。

## 1. 云厂商控制台准备

创建服务器时建议选择：

- Ubuntu 22.04 LTS 或 Ubuntu 24.04 LTS，x86_64；
- 4 vCPU、4 GB RAM、40 GB 以上 SSD；如果预算允许，磁盘选 60 GB；
- 分配一个固定公网 IPv4；
- 创建 DNS A 记录，例如 `football.example.com -> 服务器公网 IPv4`；
- 安全组只开放 `22/tcp`、`80/tcp`、`443/tcp`。3306、6379、8848、8082、9000、9001、5001 不要对公网开放。

如果云厂商提供快照，部署完成后先创建一次系统盘快照。

## 2. 初始化 Ubuntu

以具有 sudo 权限的账号登录，不要用 root 长期运行应用：

```bash
sudo apt-get update
sudo apt-get upgrade -y
sudo apt-get install -y ca-certificates curl git unzip openssl ufw
```

4G 服务器建议配置 2G swap，防止镜像构建或 Java 启动时被 OOM killer 直接杀掉：

```bash
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
echo 'vm.swappiness=10' | sudo tee /etc/sysctl.d/99-football.conf
sudo sysctl --system
free -h
```

配置主机防火墙。云安全组仍然必须同步配置：

```bash
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw --force enable
sudo ufw status verbose
```

## 3. 安装 Docker Engine 和 Compose

按 Docker 官方 Ubuntu 安装方式执行：

```bash
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc

echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo \"$VERSION_CODENAME\") stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo systemctl enable --now docker
sudo usermod -aG docker "$USER"
newgrp docker

docker run --rm hello-world
docker compose version
```

Docker Compose Linux 插件的安装方式以 Docker 官方文档为准；不要安装已经被标记为兼容性用途的旧版 `docker-compose` 单文件。

## 4. 拉取代码并固定发布版本

建议使用 release tag 或明确 commit，不要直接部署不断变化的开发分支：

```bash
sudo mkdir -p /srv/footballforecast
sudo chown -R "$USER":"$USER" /srv/footballforecast
cd /srv/footballforecast
git clone <你的仓库地址> app
cd app
git checkout <release-tag-or-commit>
```

确认关键文件存在：

```bash
test -f docker-compose.prod.yml
test -f docker-compose.4c4g.yml
test -f sql/football_forecast.sql
test -f scripts/apply-migrations.sh
test -f scripts/backup-mysql.sh
```

## 5. 创建生产环境变量

不要把真实密钥写进 compose 文件、镜像或 Git。创建只允许当前用户读取的 `.env`：

```bash
cp .env.example .env
chmod 600 .env
```

编辑 `.env`，至少修改下面这些值。Compose 网络内的服务地址必须使用服务名，而不是 `127.0.0.1`：

```dotenv
MYSQL_HOST=mysql
MYSQL_PORT=3306
MYSQL_DB=football_forecast
MYSQL_USER=football_app
MYSQL_PASSWORD=<随机生成的数据库应用密码>
MYSQL_ROOT_PASSWORD=<随机生成的数据库 root 密码>

REDIS_HOST=redis
REDIS_PORT=6379
REDIS_PASSWORD=<随机生成的 Redis 密码>
NACOS_ADDR=nacos:8848

JWT_SECRET=<至少 32 字节的随机值>
ML_INTERNAL_TOKEN=<另一组独立的随机值>
ML_REQUIRE_INTERNAL_AUTH=true
SECURITY_REFRESH_TOKEN_COOKIE_ONLY=true
AUTH_RATE_LIMIT_FAIL_CLOSED=true
AGENT_RATE_LIMIT_FAIL_CLOSED=true
GATEWAY_TRUST_PROXY_HEADERS=false
GATEWAY_CORS_ALLOWED_ORIGINS=https://football.example.com

APP_RUNTIME_DDL_ENABLED=false
APP_SCHEMA_REQUIRE_MIGRATIONS=true

CRAWLER_PRIMARY_ONLY=true
CRAWLER_PRIMARY_SOURCE=bbc-scores
CARD_WORKSHOP_ENABLED=false
CARD_ROGUE_ENABLED=false
```

可以用 OpenSSL 生成随机值，不要把命令输出贴到聊天或日志：

```bash
openssl rand -base64 48
openssl rand -base64 48
openssl rand -base64 32
```

### SMTP 注册验证码

生产必须配置真实 SMTP，否则注册验证码无法发送：

```dotenv
MAIL_HOST=smtp.gmail.com
MAIL_PORT=587
MAIL_USERNAME=<专用发件邮箱>
MAIL_PASSWORD=<应用专用密码或 SMTP 授权码>
MAIL_SMTP_AUTH=true
MAIL_SMTP_STARTTLS=true
MAIL_SMTP_STARTTLS_REQUIRED=true
MAIL_SMTP_SSL_ENABLE=false
EMAIL_VERIFICATION_ENABLED=true
EMAIL_VERIFICATION_CONSOLE_MODE=false
```

Gmail 不要使用主账号密码，使用应用专用密码；如果云厂商限制 SMTP 连接，优先使用 587/STARTTLS 或 465/SSL，并先确认服务器可以出站访问该端口。

### Agent 模型

如果要启用 Agent，填写 `SCNET_API_KEY`、`SCNET_BASE_URL`、`SCNET_MODEL` 或 OpenRouter 对应变量。密钥只放 `.env`，不要提交到仓库。建议先把 `AGENT_DAILY_TOKEN_BUDGET` 设为 10000～20000，确认费用后再提高。

## 6. 先启动基础设施，不要马上启动业务服务

使用 4G 运行时覆盖文件，并限制构建并发：

```bash
cd /srv/footballforecast/app
set -a; . ./.env; set +a
export COMPOSE_PARALLEL_LIMIT=1
docker compose -f docker-compose.prod.yml -f docker-compose.4c4g.yml config >/tmp/football-compose.rendered.yml
docker compose -f docker-compose.prod.yml -f docker-compose.4c4g.yml up -d mysql redis nacos
docker compose -f docker-compose.prod.yml -f docker-compose.4c4g.yml ps
```

等待 MySQL、Redis、Nacos 都变为 healthy：

```bash
docker compose -f docker-compose.prod.yml -f docker-compose.4c4g.yml logs --tail=100 mysql redis nacos
```

## 7. 初始化数据库和执行迁移

### 全新数据库

当 `mysql_data` 是空卷时，Compose 会自动执行 `sql/football_forecast.sql`。该初始化脚本只执行一次；以后即使修改了 SQL 文件，已有数据卷也不会再次导入。

先确认基础表存在：

```bash
docker compose -f docker-compose.prod.yml -f docker-compose.4c4g.yml exec -T mysql \
  mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e \
  "SHOW TABLES FROM ${MYSQL_DB};"
```

### 已有数据库

先做备份。脚本运行在宿主机上时，数据库地址是映射到本机的 `127.0.0.1:3306`，不要使用 Compose 内部的 `mysql` 地址：

```bash
set -a; . ./.env; set +a
export MYSQL_HOST=127.0.0.1
export MYSQL_USER=root
export MYSQL_PASSWORD="$MYSQL_ROOT_PASSWORD"
bash scripts/backup-mysql.sh /srv/football-backups
```

然后检查迁移文件并预览：

```bash
bash scripts/migration-check.sh
bash scripts/apply-migrations.sh --dry-run
```

如果输出符合预期，正式执行。当前仓库包含清理旧数据源和去重预测快照的迁移，必须显式允许破坏性 SQL：

```bash
bash scripts/apply-migrations.sh --allow-destructive
```

执行后复核来源和重复 ID：

```bash
docker compose -f docker-compose.prod.yml -f docker-compose.4c4g.yml exec -T mysql \
  mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -D"$MYSQL_DB" -e \
  "SELECT source, COUNT(*) AS total FROM crawler_matches GROUP BY source;"
```

### 上传历史预测缓存

历史比赛 JSON 不写入 Git，也不包含在 Java 镜像中；它们是生产预测构造近期样本的只读数据。部署前必须把训练机或本机的 `football-ml-service/data_cache/` 上传到服务器项目目录：

```bash
mkdir -p /srv/footballforecastsystem/football-ml-service/data_cache
# 在本机执行，将路径替换为服务器 SSH 用户、地址和项目目录
scp -r football-ml-service/data_cache/. user@SERVER:/srv/footballforecastsystem/football-ml-service/data_cache/
```

上传后检查文件数量和大小：

```bash
find football-ml-service/data_cache -type f -name 'football-data-*.json' | wc -l
du -sh football-ml-service/data_cache
```

生产 Compose 会把该目录挂载为 `/app/historical-cache`。目录不存在或为空时，业务服务会主动拒绝启动，避免所有比赛悄悄变成“历史样本不足”。

## 8. 构建并启动业务服务

第一次构建会下载 Maven、Node、Python 依赖，4G 服务器可能需要数分钟：

```bash
export COMPOSE_PARALLEL_LIMIT=1
docker compose -f docker-compose.prod.yml -f docker-compose.4c4g.yml build --pull
docker compose -f docker-compose.prod.yml -f docker-compose.4c4g.yml up -d
docker compose -f docker-compose.prod.yml -f docker-compose.4c4g.yml ps
```

查看启动日志：

```bash
docker compose -f docker-compose.prod.yml -f docker-compose.4c4g.yml logs -f --tail=200 football-gateway football-user-service football-business-service football-ml-service frontend
```

看到业务服务因迁移或依赖未就绪而重启时，先不要反复 `up`；检查 MySQL/Nacos/Redis 的 health 状态和对应日志。

## 9. 首次验收

当前 Compose 只把前端映射到宿主机 80，浏览器访问：

```text
http://服务器公网 IP/
```

本机执行公开接口检查：

```bash
curl -fsS http://127.0.0.1/api/crawler/public-status
curl -fsS "http://127.0.0.1/api/crawler/matches/date/$(date +%F)"
```

管理员健康接口需要登录权限，不要把 `/api/crawler/health`、数据库端口或 Nacos 控制台暴露到公网。生产 smoke 检查：

```bash
APP_BASE_URL=http://127.0.0.1 pwsh -File scripts/production-smoke.ps1
```

如果服务器没有 PowerShell，只执行上面的两个 `curl`，或安装 PowerShell 后再执行完整 smoke。需要验证登录链路时，在当前 shell 临时设置 `SMOKE_ACCESS_TOKEN` 并加 `-RequireAuth`，不要把 token 写进脚本。

## 10. 配置域名和 HTTPS

推荐让宿主机 Caddy 或 Nginx 占用 80/443，前端容器只监听本机 8080。创建未提交到 Git 的 `docker-compose.edge.yml`：

```yaml
services:
  frontend:
    ports:
      - "127.0.0.1:8080:3000"
```

停止当前前端并以三个文件重新启动：

```bash
docker compose -f docker-compose.prod.yml -f docker-compose.4c4g.yml -f docker-compose.edge.yml up -d frontend
```

安装 Caddy 后创建 `/etc/caddy/Caddyfile`：

```caddyfile
football.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

重载 Caddy：

```bash
sudo systemctl reload caddy
```

确认 DNS 已指向服务器且 80/443 可从公网访问，Caddy 才能自动申请和续期证书。启用 HTTPS 后，将 `.env` 中的 `GATEWAY_CORS_ALLOWED_ORIGINS` 改成准确的 `https://football.example.com`，再重建 gateway 和 user service：

```bash
docker compose -f docker-compose.prod.yml -f docker-compose.4c4g.yml up -d --build football-gateway football-user-service
```

只有确认 Caddy、前端 Nginx 和网关会清洗/重写转发头后，才考虑把 `GATEWAY_TRUST_PROXY_HEADERS` 改成 `true`；否则保持 `false`。

## 11. 日常运维

查看资源：

```bash
docker stats --no-stream
docker system df
df -h
free -h
```

查看单个服务：

```bash
docker compose -f docker-compose.prod.yml -f docker-compose.4c4g.yml logs --since=30m football-business-service
docker compose -f docker-compose.prod.yml -f docker-compose.4c4g.yml restart football-business-service
```

每日备份可用 cron（请按实际路径修改，备份目录不要放在 Web 根目录）：

```cron
0 3 * * * cd /srv/footballforecast/app && set -a && . ./.env && set +a && export MYSQL_HOST=127.0.0.1 MYSQL_USER=root MYSQL_PASSWORD="$MYSQL_ROOT_PASSWORD" && bash scripts/backup-mysql.sh /srv/football-backups >> /var/log/football-backup.log 2>&1
```

至少保留 7～14 天备份，并每月在另一台临时服务器做一次恢复演练。不要直接在唯一生产库上测试恢复。

## 12. 更新和回滚

发布更新前先备份数据库，再固定到新 tag：

```bash
cd /srv/footballforecast/app
set -a; . ./.env; set +a
export MYSQL_HOST=127.0.0.1 MYSQL_USER=root MYSQL_PASSWORD="$MYSQL_ROOT_PASSWORD"
bash scripts/backup-mysql.sh /srv/football-backups
git fetch --tags
git checkout <new-release-tag>
docker compose -f docker-compose.prod.yml -f docker-compose.4c4g.yml build --pull
bash scripts/apply-migrations.sh --dry-run
bash scripts/apply-migrations.sh --allow-destructive
docker compose -f docker-compose.prod.yml -f docker-compose.4c4g.yml up -d
```

如果新版本只改应用代码，可回到旧 tag 后重新构建；如果已经执行不可逆数据库迁移，不要盲目回滚应用，先恢复数据库副本并按迁移兼容性处理。

## 13. 常见故障

### 容器反复重启或被 OOM 杀掉

```bash
docker stats --no-stream
docker inspect football-business-service --format '{{.State.OOMKilled}}'
docker compose -f docker-compose.prod.yml -f docker-compose.4c4g.yml logs --tail=200 football-business-service
```

确认使用了 `docker-compose.4c4g.yml`、swap 已启用，并先降低 Agent 日预算；不要先盲目增加 Java 堆。

### 页面能打开但 API 502

检查 `frontend` 能否解析 `football-gateway`，以及 gateway 是否 healthy；前端 Nginx 的 `/api/` 代理目标是 Compose 网络中的 `football-gateway:8082`，不是宿主机 `127.0.0.1`。

### 启动提示缺少迁移表

先确认 MySQL healthy，再用 root 凭据执行 `apply-migrations.sh --dry-run` 和正式迁移。不要把 `APP_RUNTIME_DDL_ENABLED` 改成 true 来绕过生产门禁。

### 注册验证码收不到

检查用户服务日志、SMTP 主机/端口、应用专用密码和服务器出站防火墙。生产不要开启 `EMAIL_VERIFICATION_CONSOLE_MODE`，也不要把验证码返回给前端。

### 比赛数据为空或延迟

主链路只使用 BBC 主爬虫源；先查看管理员数据源状态和任务历史，区分空数据、源未覆盖、请求失败和额度受限，不要连续触发采集任务。

## 14. 发布完成检查清单

- [ ] 云安全组和 UFW 只开放 22、80、443；
- [ ] `.env` 权限为 600，所有密钥均为新生成值；
- [ ] `JWT_SECRET`、`ML_INTERNAL_TOKEN`、数据库和 Redis 密码彼此独立；
- [ ] `APP_RUNTIME_DDL_ENABLED=false`、`APP_SCHEMA_REQUIRE_MIGRATIONS=true`；
- [ ] 已执行备份、迁移预览、正式迁移和来源/重复 ID 复核；
- [ ] SMTP 注册验证码成功；
- [ ] HTTPS、Cookie、CORS 和刷新登录流程成功；
- [ ] Agent 每日预算和请求限流已设置；
- [ ] `production-smoke.ps1` 或等价 curl 检查通过；
- [ ] 已创建首次云盘快照和数据库备份；
- [ ] 已记录当前 Git commit、镜像构建时间和迁移版本。
