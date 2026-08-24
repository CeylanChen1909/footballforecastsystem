@echo off
chcp 65001 > nul
REM =============================================
REM 足球比赛结果预测系统 - 启动脚本 (Windows)
REM 模块: gateway:8082 / user:9001 / business:9000 / python:5001
REM 前置: MySQL(3306) + Redis(6379) + Nacos(8848) 已运行, 数据库已导入 sql/football_forecast.sql
REM =============================================
setlocal EnableExtensions EnableDelayedExpansion

echo =========================================
echo   足球比赛结果预测系统 启动脚本
echo =========================================

where java >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo [错误] 未找到 Java，请先安装 JDK 17+
    exit /b 1
)

cd /d "%~dp0.."

REM 读取项目根目录 .env；敏感值不再写入脚本
if exist ".env" for /f "usebackq tokens=* delims=" %%A in (".env") do (
    set "envline=%%A"
    if not "!envline!"=="" if not "!envline:~0,1!"=="#" for /f "tokens=1,* delims==" %%B in ("!envline!") do set "%%B=%%C"
)
if not defined MYSQL_HOST set MYSQL_HOST=127.0.0.1
if not defined MYSQL_PORT set MYSQL_PORT=3306
if not defined MYSQL_USER set MYSQL_USER=root
if not defined REDIS_HOST set REDIS_HOST=127.0.0.1
if not defined REDIS_PORT set REDIS_PORT=6379
if not defined NACOS_ADDR set NACOS_ADDR=127.0.0.1:8848
if not defined CRAWLER_BASE_URL set CRAWLER_BASE_URL=http://127.0.0.1:9000
if not defined MYSQL_PASSWORD (echo [错误] 请在 .env 或环境变量中设置 MYSQL_PASSWORD & exit /b 1)
if not defined JWT_SECRET (echo [错误] 请在 .env 或环境变量中设置 JWT_SECRET & exit /b 1)
set COMMON_ARGS=--spring.cloud.nacos.discovery.server-addr=%NACOS_ADDR% --spring.cloud.nacos.discovery.ip=127.0.0.1

echo.
echo [1/3] 编译项目...
call mvnw.cmd clean package -DskipTests
if errorlevel 1 (
    echo [错误] 编译失败
    exit /b 1
)

echo.
echo [2/3] 启动微服务...
start "Football Gateway" java -jar football-gateway\target\football-gateway-1.0.0-SNAPSHOT.jar %COMMON_ARGS%
timeout /t 3 /nobreak > nul
REM Gmail SMTP 在部分网络下 IPv4 地址轮换不稳定，用户服务优先使用 IPv6 并定期刷新 DNS 缓存
start "Football User" java -Djava.net.preferIPv6Addresses=true -Dsun.net.inetaddr.ttl=60 -jar football-user-service\target\football-user-service-1.0.0-SNAPSHOT.jar %COMMON_ARGS%
timeout /t 3 /nobreak > nul
start "Football Business" java -jar football-business-service\target\football-business-service-1.0.0-SNAPSHOT.jar %COMMON_ARGS%
timeout /t 3 /nobreak > nul

REM Python 推理服务（可选）
where python >nul 2>&1
if %ERRORLEVEL% equ 0 (
    start "Football ML" /D "%~dp0..\football-ml-service" python -u app.py
)

echo.
echo [3/3] 前端...
cd frontend
where npm >nul 2>&1
if %ERRORLEVEL% equ 0 (
    if not exist node_modules call npm install
    start "Football Frontend" npm run dev
) else (
    echo [警告] npm 未安装，跳过前端启动
)

echo.
echo =========================================
echo   启动完成！
echo   前端:  http://localhost:5173
echo   网关:  http://localhost:8082
echo   Nacos: http://localhost:8848/nacos
echo   默认账号请以数据库初始化脚本和实际配置为准，生产环境请立即修改
echo =========================================
pause
