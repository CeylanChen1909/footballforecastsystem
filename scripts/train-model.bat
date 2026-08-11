@echo off
chcp 65001 >nul
echo =========================================
echo   XGBoost 模型训练脚本
echo =========================================
echo.

cd /d "%~dp0..\python"

echo [1/4] 检查Python环境...
python --version >nul 2>&1
if errorlevel 1 (
    echo [错误] Python 未安装，请先安装 Python 3.8+
    pause
    exit /b 1
)

echo [2/4] 安装依赖...
pip install -r requirements.txt -q
echo.

echo [3/4] 生成模拟训练数据...
python scripts\init_data.py
echo.

echo [4/4] 训练模型...
python train.py data\fixtures.json models\xgboost_model.json models\team_encoder.pkl
echo.

if exist "models\xgboost_model.json" (
    echo =========================================
    echo   模型训练成功！
    echo =========================================
    echo.
    echo 启动推理服务...
    start "XGBoost-Inference" python inference_server.py
    echo.
    echo 推理服务已启动，访问 http://localhost:5001
) else (
    echo [警告] 模型文件未生成，请检查训练输出
)
echo.
pause
