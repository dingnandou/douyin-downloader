@echo off
chcp 65001 >nul
cd /d "%~dp0"
if not exist ".venv\Scripts\python.exe" (
  echo 未找到虚拟环境，正在创建...
  python -m venv .venv
  ".venv\Scripts\python.exe" -m pip install --upgrade pip
  ".venv\Scripts\python.exe" -m pip install -r requirements.txt
)
echo.
echo 启动抖音下载器：http://localhost:8000
echo 手机请用同一 WiFi 访问 http://本机局域网IP:8000
echo 按 Ctrl+C 停止
echo.
".venv\Scripts\python.exe" -m uvicorn app.main:app --host 0.0.0.0 --port 8000
