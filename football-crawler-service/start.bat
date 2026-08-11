@echo off
REM 足球预测系统爬虫服务启动脚本 (Windows)

echo ========================================
echo    足球预测系统 - 爬虫服务启动脚本
echo ========================================
echo.

REM 检查 Java
where java >nul 2>nul
if %ERRORLEVEL% neq 0 (
    echo [错误] 未找到 Java，请先安装 JDK 8+
    pause
    exit /b 1
)

REM 配置
set APP_JAR=target\football-crawler-service-1.0.0.jar
set PORT=9005

REM 编译项目（如果需要）
if not exist "%APP_JAR%" (
    echo [提示] 正在编译项目...
    call mvn clean package -DskipTests -q
)

REM 检查 JAR 文件
if not exist "%APP_JAR%" (
    echo [错误] 未找到 JAR 文件，请先执行 mvn package
    pause
    exit /b 1
)

REM 启动服务
echo [提示] 正在启动爬虫服务...
echo [提示] 端口: %PORT%
echo.

start "Football Crawler Service" java -jar "%APP_JAR%" ^
    --server.port=%PORT% ^
    --spring.data.redis.host=127.0.0.1 ^
    --spring.data.redis.port=6379 ^
    --spring.datasource.url="jdbc:mysql://127.0.0.1:3306/football_forecast?useUnicode=true^&characterEncoding=utf8^&useSSL=false^&serverTimezone=Asia/Shanghai" ^
    --spring.datasource.username=root ^
    --spring.datasource.password=root ^
    --crawler.request-interval-ms=2000

echo.
echo ========================================
echo [成功] 爬虫服务已启动
echo [成功] API 地址: http://localhost:%PORT%/api/crawler
echo ========================================
echo.
echo 常用接口:
echo   - 今日比赛: GET  http://localhost:%PORT%/api/crawler/matches/today
echo   - 指定日期: GET  http://localhost:%PORT%/api/crawler/matches/date/2024-01-15
echo   - 触发爬取: POST http://localhost:%PORT%/api/crawler/trigger?type=matches
echo.
pause
