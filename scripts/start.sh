#!/bin/bash
# =============================================
# 足球比赛结果预测系统 - 启动脚本 (Linux/macOS)
# =============================================

set -e

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  足球比赛结果预测系统 启动脚本${NC}"
echo -e "${BLUE}========================================${NC}"

# 检查 Java
if ! command -v java &> /dev/null; then
    echo -e "${RED}错误: 未找到 Java，请先安装 JDK 17+${NC}"
    exit 1
fi

echo -e "${GREEN}Java 版本:${NC}"
java -version

# 检查 Maven
if ! command -v mvn &> /dev/null; then
    echo -e "${RED}错误: 未找到 Maven，请先安装 Maven${NC}"
    exit 1
fi

echo -e "${GREEN}Maven 版本:${NC}"
mvn -version

# 项目根目录
PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

# 环境变量设置
export API_FOOTBALL_API_KEY="${API_FOOTBALL_API_KEY:-7294a6ff586cc5f3531171bc154ced85}"
export JWT_SECRET="${JWT_SECRET:-football-forecast-secret-key-change-in-production}"
export MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
export MYSQL_PORT="${MYSQL_PORT:-3307}"
export MYSQL_USER="${MYSQL_USER:-root}"
export MYSQL_PASSWORD="${MYSQL_PASSWORD:-root}"
export REDIS_HOST="${REDIS_HOST:-127.0.0.1}"
export REDIS_PORT="${REDIS_PORT:-6379}"

echo -e "\n${YELLOW}[1/4] 启动基础设施 (Docker)...${NC}"
if command -v docker &> /dev/null; then
    docker compose up -d
    echo -e "${GREEN}等待 MySQL 和 Redis 启动...${NC}"
    sleep 10
else
    echo -e "${YELLOW}警告: Docker 未安装，请手动启动 MySQL 和 Redis${NC}"
fi

echo -e "\n${YELLOW}[2/4] 编译项目...${NC}"
mvn clean package -DskipTests

echo -e "\n${YELLOW}[3/4] 启动微服务...${NC}"

# 启动网关
echo -e "${GREEN}启动 Gateway (端口 8080)...${NC}"
java -jar football-gateway/target/football-gateway-1.0.0-SNAPSHOT.jar &
sleep 3

# 启动用户服务
echo -e "${GREEN}启动 User Service (端口 9001)...${NC}"
java -jar football-user-service/target/football-user-service-1.0.0-SNAPSHOT.jar &
sleep 3

# 启动比赛服务
echo -e "${GREEN}启动 Match Service (端口 9002)...${NC}"
java -jar football-match-service/target/football-match-service-1.0.0-SNAPSHOT.jar &
sleep 3

# 启动资讯服务
echo -e "${GREEN}启动 News Service (端口 9003)...${NC}"
java -jar football-news-service/target/football-news-service-1.0.0-SNAPSHOT.jar &
sleep 3

# 启动球队服务
echo -e "${GREEN}启动 Team Service (端口 9004)...${NC}"
java -jar football-team-service/target/football-team-service-1.0.0-SNAPSHOT.jar &
sleep 3

# 启动预测服务
echo -e "${GREEN}启动 Prediction Service (端口 9005)...${NC}"
java -jar football-prediction-service/target/football-prediction-service-1.0.0-SNAPSHOT.jar &
sleep 3

echo -e "\n${YELLOW}[4/4] 启动前端...${NC}"
cd frontend
if command -v npm &> /dev/null; then
    npm install
    npm run dev &
else
    echo -e "${YELLOW}警告: npm 未安装，跳过前端启动${NC}"
fi

echo -e "\n${GREEN}========================================${NC}"
echo -e "${GREEN}  所有服务启动完成！${NC}"
echo -e "${GREEN}========================================${NC}"
echo -e "前端: ${BLUE}http://localhost:5173${NC}"
echo -e "网关: ${BLUE}http://localhost:8080${NC}"
echo -e "Nacos: ${BLUE}http://localhost:8848/nacos${NC}"
echo -e "Sentinel: ${BLUE}http://localhost:8858${NC}"
echo -e "\n默认测试账号: test / 123456"
