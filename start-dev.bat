@echo off
chcp 65001 >nul
REM 本地开发一键启动入口。服务拓扑已收敛到 gateway/user/business + ML。
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"
call scripts\start.bat
