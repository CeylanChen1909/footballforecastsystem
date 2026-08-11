@echo off
chcp 65001 > nul
REM =============================================
REM 足球比赛结果预测系统 - 启动脚本 (Windows)
REM =============================================

setlocal

echo =========================================
echo   足球比赛结果预测系统 启动脚本
echo =========================================

REM 检查 Java
where java >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo 错误: 未找到 Java，请先安装 JDK 17+
    exit /b 1
)

echo Java 版本:
java -version

REM 检查 Maven
where mvn >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo 错误: 未找到 Maven，请先安装 Maven
    exit /b 1
)

echo.
echo Maven 版本:
mvn -version

REM 项目根目录
set PROJECT_DIR=%~dp0
cd /d "%PROJECT_DIR%"

REM 环境变量设置
set API_FOOTBALL_API_KEY=7294a6ff586cc5f3531171bc154ced85
set JWT_SECRET=football-forecast-secret-key-change-in-production
set MYSQL_HOST=127.0.0.1
set MYSQL_PORT=3307
set MYSQL_USER=root
set MYSQL_PASSWORD=root
set REDIS_HOST=127.0.0.1
set REDIS_PORT=6379
set NACOS_ADDR=127.0.0.1:8848
set COMMON_ARGS=--spring.cloud.nacos.discovery.server-addr=127.0.0.1:8848 --spring.cloud.nacos.discovery.ip=127.0.0.1

echo.
echo [1/4] 编译项目...
call mvn clean package -DskipTests

echo.
echo [2/4] 启动基础设施 (Docker)...
where docker >nul 2>&1
if %ERRORLEVEL% equ 0 (
    docker compose up -d
    echo 等待 MySQL 和 Redis 启动...
    timeout /t 10 /nobreak > nul
) else (
    echo 警告: Docker 未安装，请手动启动 MySQL 和 Redis
)

echo.
echo [3/4] 启动微服务...

REM 启动网关
echo 启动 Gateway (端口 8080)...
start "Football Gateway" java -jar football-gateway\target\football-gateway-1.0.0-SNAPSHOT.jar %COMMON_ARGS%
timeout /t 3 /nobreak > nul

REM 启动用户服务
echo 启动 User Service (端口 9001)...
start "Football User" java -jar football-user-service\target\football-user-service-1.0.0-SNAPSHOT.jar %COMMON_ARGS%
timeout /t 3 /nobreak > nul

REM 启动比赛服务
echo 启动 Match Service (端口 9002)...
start "Football Match" java -jar football-match-service\target\football-match-service-1.0.0-SNAPSHOT.jar %COMMON_ARGS%
timeout /t 3 /nobreak > nul

REM 启动资讯服务
echo 启动 News Service (端口 9003)...
start "Football News" java -jar football-news-service\target\football-news-service-1.0.0-SNAPSHOT.jar %COMMON_ARGS%
timeout /t 3 /nobreak > nul

REM 启动球队服务
echo 启动 Team Service (端口 9004)...
start "Football Team" java -jar football-team-service\target\football-team-service-1.0.0-SNAPSHOT.jar %COMMON_ARGS%
timeout /t 3 /nobreak > nul

REM 启动预测服务
echo 启动 Prediction Service (端口 9005)...
start "Football Prediction" java -jar football-prediction-service\target\football-prediction-service-1.0.0-SNAPSHOT.jar %COMMON_ARGS%
timeout /t 3 /nobreak > nul

echo.
echo [4/4] 启动前端...
cd /d "%PROJECT_DIR%frontend"
where npm >nul 2>&1
if %ERRORLEVEL% equ 0 (
    call npm install
    start "Football Frontend" npm run dev
) else (
    echo 警告: npm 未安装，跳过前端启动
)

echo.
echo =========================================
echo   所有服务启动完成！
echo =========================================
echo 前端: http://localhost:5173
echo 网关: http://localhost:8080
echo Nacos: http://localhost:8848/nacos
echo Sentinel: http://localhost:8858
echo.
echo 默认测试账号: test / 123456
echo.
pause
