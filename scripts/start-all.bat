@echo off
chcp 65001 >nul
echo =========================================
echo   足球比赛结果预测系统 - 一键启动
echo =========================================
echo.

REM 检查Docker是否安装
docker --version >nul 2>&1
if errorlevel 1 (
    echo [错误] Docker 未安装，请先安装 Docker Desktop
    pause
    exit /b 1
)

echo [1/5] 启动基础设施服务（MySQL, Redis, Nacos, Sentinel）...
docker compose up -d
echo.

echo [2/5] 等待基础设施启动（30秒）...
timeout /t 30 /nobreak >nul
echo.

echo [3/5] 设置环境变量...
set API_FOOTBALL_API_KEY=7294a6ff586cc5f3531171bc154ced85
set JWT_SECRET=change-me-in-env-min-32-chars
set NACOS_ADDR=127.0.0.1:8848
set SENTINEL_DASHBOARD=127.0.0.1:8858
set MYSQL_HOST=127.0.0.1
set MYSQL_PORT=3307
set REDIS_HOST=127.0.0.1
echo.

echo [4/5] 编译打包...
call mvn clean package -DskipTests
if errorlevel 1 (
    echo [错误] 编译失败
    pause
    exit /b 1
)
echo.

echo [5/5] 启动微服务...
echo 启动 Gateway...
start "Gateway" java -jar football-gateway\target\football-gateway-1.0.0-SNAPSHOT.jar

echo 启动 User Service...
start "UserService" java -jar football-user-service\target\football-user-service-1.0.0-SNAPSHOT.jar

echo 启动 Match Service...
start "MatchService" java -jar football-match-service\target\football-match-service-1.0.0-SNAPSHOT.jar

echo 启动 Team Service...
start "TeamService" java -jar football-team-service\target\football-team-service-1.0.0-SNAPSHOT.jar

echo 启动 News Service...
start "NewsService" java -jar football-news-service\target\football-news-service-1.0.0-SNAPSHOT.jar

echo 启动 Prediction Service...
start "PredictionService" java -jar football-prediction-service\target\football-prediction-service-1.0.0-SNAPSHOT.jar
echo.

echo =========================================
echo   启动完成！
echo =========================================
echo.
echo 访问地址：
echo   - 前端:    http://localhost:5173
echo   - Gateway: http://localhost:8080
echo   - Nacos:   http://localhost:8848/nacos (nacos/nacos)
echo   - Sentinel: http://localhost:8858 (sentinel/sentinel)
echo.
echo 按任意键退出此窗口（服务将继续在后台运行）
pause >nul
