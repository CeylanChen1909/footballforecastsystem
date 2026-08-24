#!/bin/bash
# =============================================
# 足球比赛结果预测系统 - 启动脚本 (Linux/macOS)
# 模块: gateway:8082 / user:9001 / business:9000 / python:5001
# 前置: MySQL(3306) + Redis(6379) + Nacos(8848) 已运行, 数据库已导入 sql/football_forecast.sql
# =============================================

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  足球比赛结果预测系统 启动脚本${NC}"
echo -e "${BLUE}========================================${NC}"

if ! command -v java &> /dev/null; then
    echo -e "${RED}[错误] 未找到 Java，请先安装 JDK 17+${NC}"
    exit 1
fi

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_DIR"

# 读取项目根目录 .env；敏感值不再写入脚本
if [ -f .env ]; then set -a; . ./.env; set +a; fi
: "${MYSQL_HOST:=127.0.0.1}"
: "${MYSQL_PORT:=3306}"
: "${MYSQL_USER:=root}"
: "${REDIS_HOST:=127.0.0.1}"
: "${REDIS_PORT:=6379}"
: "${NACOS_ADDR:=127.0.0.1:8848}"
: "${CRAWLER_BASE_URL:=http://127.0.0.1:9000}"
if [ -z "${MYSQL_PASSWORD:-}" ] || [ -z "${JWT_SECRET:-}" ]; then
    echo -e "${RED}[错误] 请在 .env 或环境变量中设置 MYSQL_PASSWORD 和 JWT_SECRET${NC}"
    exit 1
fi
export MYSQL_HOST MYSQL_PORT MYSQL_USER MYSQL_PASSWORD REDIS_HOST REDIS_PORT NACOS_ADDR JWT_SECRET CRAWLER_BASE_URL

echo -e "${GREEN}[1/3] 编译项目...${NC}"
./mvnw clean package -DskipTests

echo -e "${GREEN}[2/3] 启动微服务...${NC}"
nohup java -jar football-gateway/target/football-gateway-1.0.0-SNAPSHOT.jar > logs/gateway.log 2>&1 &
sleep 3
# Gmail SMTP 在部分网络下 IPv4 地址轮换不稳定，用户服务优先使用 IPv6 并定期刷新 DNS 缓存
nohup java -Djava.net.preferIPv6Addresses=true -Dsun.net.inetaddr.ttl=60 -jar football-user-service/target/football-user-service-1.0.0-SNAPSHOT.jar > logs/user.log 2>&1 &
sleep 3
nohup java -jar football-business-service/target/football-business-service-1.0.0-SNAPSHOT.jar > logs/business.log 2>&1 &
sleep 3

if command -v python3 &> /dev/null; then
    (cd football-ml-service && nohup python3 -u app.py > ../logs/ml.log 2>&1 &)
fi

echo -e "${GREEN}[3/3] 前端...${NC}"
cd frontend
if [ ! -d node_modules ]; then npm install; fi
nohup npm run dev > ../logs/frontend.log 2>&1 &

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  启动完成！前端: http://localhost:5173  网关: http://localhost:8082${NC}"
echo -e "${BLUE}  默认账号请以数据库初始化脚本和实际配置为准，生产环境请立即修改${NC}"
echo -e "${BLUE}========================================${NC}"
