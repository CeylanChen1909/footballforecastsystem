pmall# 足球比赛结果预测系统 (Football Match Forecast System)

基于微服务架构的足球比赛结果预测系统，采用 Spring Cloud Alibaba + Nacos + Gateway + Sentinel，集成 XGBoost 机器学习模型预测比赛结果。

---

## 项目架构

```
┌─────────────────────────────────────────────────────────┐
│                     用户浏览器                           │
│                   http://localhost:3000                   │
└─────────────────────┬───────────────────────────────────┘
                      │ /api/* → Gateway :8080
┌─────────────────────▼───────────────────────────────────┐
│                    Spring Cloud Gateway                   │
│               (路由 + 限流 + 熔断 + CORS)                 │
└─┬─────────┬───────��─┬─────────┬─────────┬─────────┬──────┘
  │         │         │         │         │         │
  ▼         ▼         ▼         ▼         ▼         ▼
┌────────┐┌────────┐┌────────┐┌────────┐┌────────┐┌────────┐
│User Svc││Match Svc││News Svc││Team Svc││Pred Svc││Sync Svc│
│ :9001  ││ :9002  ││ :9003  ││ :9004  ││ :9005  ││ :9006  │
└───┬────┘└───┬────┘└───┬────┘└───┬────┘└───┬────┘└───┬────┘
    │         │         │         │         │         │
    ▼         ▼         ▼         ▼         ▼         ▼
┌────────┐┌────────┐┌────────┐┌────────┐┌────────┐┌────────┐
│  MySQL ││ Redis  ││ API-F  ││  ML    ││ Nacos  ││Sentinel│
│ :3307  ││ :6379  ││ football│ :5001 ││ :8848  ││ :8858  │
└────────┘└────────┘└────────┘└────────┘└────────┘└────────┘
```

## 技术栈

| 层级 | 技术 |
|------|------|
| 前端 | Vue3 + Element Plus + Vite + Axios + Pinia |
| 网关 | Spring Cloud Gateway |
| 微服务 | Spring Boot 3 + Spring MVC |
| 注册/配置 | Nacos 2.3.2 |
| 熔断/限流 | Sentinel 1.8.8 |
| 数据库 | MySQL 5.7 |
| 缓存 | Redis 7.2 |
| 机器学习 | Python 3.11 + XGBoost + Flask |
| 数据同步 | Spring Scheduling + WebClient |

## 微服务清单

| 服务 | 端口 | 说明 |
|------|------|------|
| `football-gateway` | 8080 | API 网关，路由转发 |
| `football-user-service` | 9001 | 用户注册、登录、收藏 |
| `football-match-service` | 9002 | 比赛数据查询 |
| `football-news-service` | 9003 | 资讯、积分榜、历史交锋 |
| `football-team-service` | 9004 | 球队信息查询 |
| `football-prediction-service` | 9005 | XGBoost 预测请求 |
| `football-data-sync-service` | 9006 | 定时同步 API-Football 数据 |
| `football-ml-service` | 5001 | Python ML 推理 API |

---

## 快速启动

### 前置条件

- JDK 17+
- Maven 3.8+
- Docker & Docker Compose
- Python 3.11（可选，用于训练 ML 模型）

### 方式一：Docker Compose 一键启动（推荐）

```bash
# 克隆项���
git clone <your-repo>
cd footballforecastsystem

# 设置 API Key
export API_FOOTBALL_API_KEY=7294a6ff586cc5f3531171bc154ced85

# 启动所有服务（MySQL、Redis、Nacos、Sentinel、各微服务、前端）
docker-compose --env-file .env up -d

# 查看日志
docker-compose logs -f football-gateway
```

启动后访问：
- 前端：http://localhost:3000
- Nacos：http://localhost:8848/nacos (用户名: nacos, 密码: nacos)
- Sentinel：http://localhost:8858

### 方式二：本地开发启动

#### 1. 启动基础设施

```bash
docker-compose up -d mysql redis nacos sentinel
```

#### 2. 启动后端微服务

在项目根目录：

```bash
# 编译项目
mvn clean install -DskipTests

# 启动各个服务（每个终端一个）
# 终端 1: Gateway
java -jar football-gateway/target/football-gateway-1.0.0-SNAPSHOT.jar

# 终端 2-7: 各微服务
java -jar football-user-service/target/football-user-service-1.0.0-SNAPSHOT.jar
java -jar football-match-service/target/football-match-service-1.0.0-SNAPSHOT.jar
java -jar football-news-service/target/football-news-service-1.0.0-SNAPSHOT.jar
java -jar football-team-service/target/football-team-service-1.0.0-SNAPSHOT.jar
java -jar football-prediction-service/target/football-prediction-service-1.0.0-SNAPSHOT.jar
java -jar football-data-sync-service/target/football-data-sync-service-1.0.0-SNAPSHOT.jar
```

#### 3. 启动 ML 服务

```bash
cd football-ml-service
pip install -r requirements.txt

# 首次运行先训练模型（可选）
python train.py

# 启动推理服务
python app.py
```

#### 4. 启动前端

```bash
cd frontend
npm install
npm run dev
```

访问 http://localhost:3000

---

## API 接口说明

所有 API 均通过 Gateway 转发，根路径 `/api`

### 用户模块 `/api/users`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/users/register` | 注册用户 |
| POST | `/users/login` | 用户登录 |
| GET | `/users/me` | 获取当前用户 |
| GET | `/users/favorites` | 获取收藏列表 |
| POST | `/users/favorites` | 添加收藏 |
| DELETE | `/users/favorites/{teamId}` | 取消收藏 |

### 比赛模块 `/api/matches`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/matches/today` | 今日比赛 |
| GET | `/matches/date/{date}` | 指定日期比赛 |
| GET | `/matches/{fixtureId}` | 比赛详情 |
| GET | `/matches/leagues` | 支持的联赛列表 |

### 资讯模块 `/api/news`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/news/latest` | 最新资讯 |
| GET | `/news/standings` | 积分榜 |
| GET | `/news/h2h` | 历史交锋 |
| GET | `/news/leagues` | 联赛列表 |

### 球队模块 `/api/teams`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/teams/{teamId}` | 球队详情 |
| GET | `/teams/league/{leagueId}` | 联赛球队列表 |
| GET | `/teams/{teamId}/statistics` | 球队统计 |
| GET | `/teams/search` | 搜索球队 |

### 预测模块 `/api/predictions`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/predictions/match-result` | 提交预测 |
| GET | `/predictions/history` | 预测历史 |

---

## ML 模型训练

```bash
cd football-ml-service

# 配置 API Key
export API_FOOTBALL_API_KEY=7294a6ff586cc5f3531171bc154ced85

# 运行训练（会从 API 拉取真实数据 + 生成模拟数据）
python train.py

# 训练完成后检查模型文件
ls models/
# xgboost_model.json     - XGBoost 模型
# feature_scaler.joblib   - 特征标准化器
# train_results.json      - 训练评估报告
```

---

## 数据同步服务

`football-data-sync-service` 自动定时执行以下任务：

- **每 6 小时**：同步 6 大联赛（英超、西甲、意甲、德甲、法甲、欧冠）的球队和比赛数据到本地 MySQL
- **每天凌晨 2 点**：验证已结束比赛的预测准确性

日志路径：`football-data-sync-service/logs/`

---

## 默认账号

| 用户名 | 密码 | 角色 |
|--------|------|------|
| test | 123456 | 普通用户 |
| admin | 123456 | 管理员 |

---

## 目录结构

```
footballforecastsystem/
├── football-common/          # 公共模块（DTO、工具类、Redis、API客户端）
├── football-gateway/          # 网关
├── football-user-service/     # 用户服务
├── football-match-service/    # 比赛服务
├── football-news-service/     # 资讯服务
├── football-team-service/     # 球队服务
├── football-prediction-service/# 预测服务
├── football-data-sync-service/# 数据同步服务
├── football-ml-service/       # Python ML 服务
├── frontend/                  # Vue3 前端
├── sql/                       # 数据库初始化脚本
├── docker-compose.yml        # 容器编排
└── README.md
```

---

## 常见问题

**Q: 启动后前端无法访问后端？**
A: 检查 Gateway 是否在 8080 端口启动，检查 `vite.config.js` 的 proxy 配置是否指向正确地址。

**Q: 预测准确率很低？**
A: 需要先运行 `python train.py` 训练真实模型，或等待 data-sync-service 从 API 获取足够的历史数据后重新训练。

**Q: API 限流？**
A: API-Football 免费版有请求限制（每分钟 10 次，每天 100 次），系统已配置 Redis 缓存降低 API 调用频率。

**Q: MySQL 连接失败？**
A: 确认 Docker MySQL 已启动，数据库 `football_forecast` 已创建（参考 `sql/football_forecast_schema.sql`）。