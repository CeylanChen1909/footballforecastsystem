@echo off
REM ==================== 足球预测系统 - 快速启动脚本 ====================
REM 前置条件：
REM   1. Docker Desktop 已启动
REM   2. JDK 17+ 已配置环境变量
REM   3. Maven 已配置环境变量
REM   4. Python 3.11+ 已安装

echo ================================================
echo   足球比赛结果预测系统 - 本地开发启动
echo ================================================
echo.

REM 检查 Docker
echo [1/5] 检查 Docker...
docker info >nul 2>&1
if errorlevel 1 (
    echo [错误] Docker 未启动，请先启动 Docker Desktop
    pause
    exit /b 1
)
echo [OK] Docker 已启动

REM 启动基础设施容器
echo [2/5] 启动 MySQL、Redis、Nacos、Sentinel...
docker-compose up -d mysql redis nacos sentinel
echo [OK] 基础设施已启动

REM 等待 Nacos 启动
echo [3/5] 等待 Nacos 启动（约30秒）...
timeout /t 30 /nobreak >nul
echo [OK] Nacos 已就绪

REM 编译项目
echo [4/5] 编译项目...
cd /d "%~dp0"
call mvn clean package -DskipTests
if errorlevel 1 (
    echo [错误] Maven 编译失败
    pause
    exit /b 1
)
echo [OK] 项目编译完成

REM 启动后端服务
echo [5/5] 启动后端微服务...
echo 启动顺序：Gateway -> User -> Match -> News -> Team -> Prediction -> DataSync
echo 注意：每个服务需要独立终端，以下仅作并发启动参考
set COMMON_ARGS=--spring.cloud.nacos.discovery.server-addr=127.0.0.1:8848 --spring.cloud.nacos.discovery.ip=127.0.0.1
start "Gateway" cmd /k "cd /d %~dp0 && java -jar football-gateway\target\football-gateway-1.0.0-SNAPSHOT.jar %COMMON_ARGS%"
timeout /t 5 /nobreak >nul
start "UserService" cmd /k "cd /d %~dp0 && java -jar football-user-service\target\football-user-service-1.0.0-SNAPSHOT.jar %COMMON_ARGS%"
start "MatchService" cmd /k "cd /d %~dp0 && java -jar football-match-service\target\football-match-service-1.0.0-SNAPSHOT.jar %COMMON_ARGS%"
start "NewsService" cmd /k "cd /d %~dp0 && java -jar football-news-service\target\football-news-service-1.0.0-SNAPSHOT.jar %COMMON_ARGS%"
start "TeamService" cmd /k "cd /d %~dp0 && java -jar football-team-service\target\football-team-service-1.0.0-SNAPSHOT.jar %COMMON_ARGS%"
start "PredictionService" cmd /k "cd /d %~dp0 && java -jar football-prediction-service\target\football-prediction-service-1.0.0-SNAPSHOT.jar %COMMON_ARGS%"
start "DataSyncService" cmd /k "cd /d %~dp0 && java -jar football-data-sync-service\target\football-data-sync-service-1.0.0-SNAPSHOT.jar %COMMON_ARGS%"

echo.
echo ================================================
echo   后端服务已启动！
echo ================================================
echo.
echo 启动前端:
echo   cd frontend ^&^& npm install ^&^& npm run dev
echo.
echo 启动 ML 服务(可选):
echo   cd football-ml-service ^&^& pip install -r requirements.txt ^&^& python train.py ^&^& python app.py
echo.
echo 服务地址：
echo   前端：http://localhost:3000
echo   网关： http://localhost:8080
echo   Nacos： http://localhost:8848/nacos
echo   Sentinel：http://localhost:8858
echo.
echo 默认账号：test / 123456
echo.
pause
