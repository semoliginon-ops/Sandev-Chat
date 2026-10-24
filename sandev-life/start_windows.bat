@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo ==========================================
echo   SANDEV Life - Local SANDEV Game AI
echo ==========================================
set /p MODEL_PATH=Masukkan path lengkap file .gguf: 
if "%MODEL_PATH%"=="" (
  echo Path model kosong.
  pause
  exit /b 1
)
python --version >nul 2>&1
if errorlevel 1 (
  echo Python 3 tidak ditemukan. Instal Python 3.10+ lalu coba lagi.
  pause
  exit /b 1
)
python -m pip show llama-cpp-python >nul 2>&1
if errorlevel 1 (
  echo Memasang runtime lokal llama-cpp-python...
  python -m pip install llama-cpp-python
  if errorlevel 1 (
    echo Instalasi runtime gagal. Lihat README.md untuk bantuan.
    pause
    exit /b 1
  )
)
python sandev_life_local.py --model "%MODEL_PATH%" --host 127.0.0.1 --port 8080
pause
