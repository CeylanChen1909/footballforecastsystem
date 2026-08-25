# 开发与生产分支、配置约定

项目固定使用两条长期分支：

- `develop`：本地开发、联调和测试。默认使用 Spring `dev` 配置，网关通过 Vite 代理访问本机 `8082`。
- `production`：云服务器生产环境。Docker Compose 显式使用 Spring `prod` profile，服务地址使用 Compose 服务名，不使用 `localhost`。

历史分支 `deploy/20260824` 暂时保留，便于已经部署的服务器平滑迁移；后续发布以 `production` 为准。

## 本地开发

```powershell
git switch develop
Copy-Item .env.example .env
.\\mvnw.cmd -DskipTests package
.\\start-dev.bat
```

本地配置入口：

- `football-gateway/src/main/resources/application.yml`
- `football-gateway/src/main/resources/application-prod.yml` 不参与本地默认启动
- `frontend/vite.config.js` 的 `/api` 代理目标固定为 `http://localhost:8082`
- 本地密钥只放 `.env`，该文件被 `.gitignore` 忽略

## 生产发布

生产服务器只拉取 `production`，不要在服务器上直接修改 Java/YAML 配置：

```bash
git switch production
git pull --ff-only origin production
cp .env.production.example .env
set -a
source .env
set +a
bash ./scripts/backup-mysql.sh
bash ./scripts/apply-migrations.sh
docker compose -f docker-compose.prod.yml up -d --build
```

`docker-compose.prod.yml` 会为所有 Java 服务注入 `SPRING_PROFILES_ACTIVE=prod`，并使用 `mysql`、`redis`、`nacos` 等容器服务名。生产配置来源只有：

- `football-*/src/main/resources/application-prod.yml`
- 服务器根目录 `.env`
- `docker-compose.prod.yml`

不要把服务器 `.env`、API Key、SMTP 密码或 JWT Secret 提交到 Git。

## 发布流程

```bash
git switch develop
git pull --ff-only origin develop
# 开发、测试、提交
git push origin develop

git switch production
git pull --ff-only origin production
git merge --no-ff develop -m "release: merge develop into production"
git push origin production
```

服务器发布只执行 `git pull --ff-only origin production`，不再跟随日期分支。若需要回滚，切换到上一个已验证的 production commit 后重新构建容器。

## 防止环境串用

1. 本地不要设置 `SPRING_PROFILES_ACTIVE=prod`。
2. 生产不要使用 `.env.example`，必须使用服务器自己的 `.env`。
3. 修改网关路由、CORS、Nacos 地址或服务 IP 时，先确认当前分支；开发修改必须在 `develop`，生产变更通过审查后的合并进入 `production`。
4. 不要直接在服务器编辑 `application-prod.yml`；需要变更时修改代码、测试后从 `develop` 合并。
