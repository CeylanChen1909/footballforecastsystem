#!/bin/bash

# 足球预测系统爬虫服务启动脚本

# 配置
APP_NAME="football-crawler-service"
APP_JAR="target/football-crawler-service-1.0.0.jar"
PORT=9005
LOG_FILE="logs/crawler.log"

# 颜色输出
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}   足球预测系统 - 爬虫服务启动脚本${NC}"
echo -e "${GREEN}========================================${NC}"

# 检查 Java
if ! command -v java &> /dev/null; then
    echo -e "${RED}错误: 未找到 Java，请先安装 JDK 8+${NC}"
    exit 1
fi

# 创建日志目录
mkdir -p logs

# 编译项目（如果需要）
if [ ! -f "$APP_JAR" ]; then
    echo -e "${YELLOW}正在编译项目...${NC}"
    mvn clean package -DskipTests -q
fi

# 检查 JAR 文件
if [ ! -f "$APP_JAR" ]; then
    echo -e "${RED}错误: 未找到 JAR 文件，请先执行 mvn package${NC}"
    exit 1
fi

# 启动服务
echo -e "${YELLOW}正在启动爬虫服务...${NC}"
echo -e "端口: $PORT"
echo -e "日志: $LOG_FILE"
echo ""

java -jar "$APP_JAR" \
    --server.port=$PORT \
    --spring.data.redis.host=127.0.0.1 \
    --spring.data.redis.port=6379 \
    --spring.datasource.url="jdbc:mysql://127.0.0.1:3306/football_forecast?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai" \
    --spring.datasource.username=root \
    --spring.datasource.password=root \
    --crawler.request-interval-ms=2000 \
    > "$LOG_FILE" 2>&1 &

PID=$!
echo $PID > .crawler.pid

echo -e "${GREEN}✓ 爬虫服务已启动 (PID: $PID)${NC}"
echo -e "${GREEN}✓ API 地址: http://localhost:$PORT/api/crawler${NC}"
echo ""
echo -e "常用接口:"
echo -e "  - 今日比赛: GET  http://localhost:$PORT/api/crawler/matches/today"
echo -e "  - 指定日期: GET  http://localhost:$PORT/api/crawler/matches/date/2024-01-15"
echo -e "  - 触发爬取: POST http://localhost:$PORT/api/crawler/trigger?type=matches"
echo ""
echo -e "停止服务: kill \$(cat .crawler.pid)"
