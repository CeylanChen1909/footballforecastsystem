@echo off
chcp 65001 >nul
setlocal EnableExtensions EnableDelayedExpansion
echo =========================================
echo   足球比赛结果预测系统 - 一键启动（含基础设施）
echo =========================================

REM 检查 Docker
docker --version >nul 2>&1
if errorlevel 1 (
    echo [错误] Docker 未安装，请先安装 Docker Desktop 或手动启动 MySQL/Redis/Nacos
    pause
    exit /b 1
)

echo [1/4] 启动基础设施（MySQL, Redis, Nacos）...
docker compose up -d
timeout /t 30 /nobreak >nul

echo [2/4] 导入数据库（如已导入可跳过）...
if exist ".env" for /f "usebackq tokens=* delims=" %%A in (".env") do (
    set "envline=%%A"
    if not "!envline!"=="" if not "!envline:~0,1!"=="#" for /f "tokens=1,* delims==" %%B in ("!envline!") do set "%%B=%%C"
)
if not defined MYSQL_PASSWORD (echo [错误] 请在 .env 或环境变量中设置 MYSQL_PASSWORD & pause & exit /b 1)
if not defined JWT_SECRET (echo [错误] 请在 .env 或环境变量中设置 JWT_SECRET & pause & exit /b 1)
docker exec -i football-mysql mysql -uroot -p%MYSQL_PASSWORD% football_forecast < sql\football_forecast.sql

echo [3/4] 编译并启动微服务...
if not defined MYSQL_HOST set MYSQL_HOST=127.0.0.1
if not defined MYSQL_PORT set MYSQL_PORT=3306
if not defined MYSQL_USER set MYSQL_USER=root
if not defined NACOS_ADDR set NACOS_ADDR=127.0.0.1:8848
if not defined CRAWLER_BASE_URL set CRAWLER_BASE_URL=http://127.0.0.1:9000

call mvnw.cmd clean package -DskipTests
if errorlevel 1 (
    echo [错误] 编译失败
    pause
    exit /b 1
)

start "Gateway" java -jar football-gateway\target\football-gateway-1.0.0-SNAPSHOT.jar
start "UserService" java -Djava.net.preferIPv6Addresses=true -Dsun.net.inetaddr.ttl=60 -jar football-user-service\target\football-user-service-1.0.0-SNAPSHOT.jar
start "BusinessService" java -jar football-business-service\target\football-business-service-1.0.0-SNAPSHOT.jar

echo [4/4] 启动 Python 推理与前端...
where python >nul 2>&1
if %ERRORLEVEL% equ 0 start "Football ML" /D "%~dp0..\football-ml-service" python -u app.py
cd frontend
if not exist node_modules call npm install
start "Frontend" npm run dev

echo.
echo =========================================
echo   启动完成！
echo   前端:  http://localhost:5173
echo   网关:  http://localhost:8082
echo   Nacos: http://localhost:8848/nacos (nacos/nacos)
echo   默认账号请以数据库初始化脚本和实际配置为准，生产环境请立即修改
echo =========================================
pause >nul
