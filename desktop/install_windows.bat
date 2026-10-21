@echo off
setlocal
cd /d "%~dp0"
echo Installing Python packages for NK Voice AI Desktop...
py -3 -m pip install --upgrade pip
py -3 -m pip install -r requirements.txt
if errorlevel 1 (
  echo.
  echo Installation failed. llama-cpp-python may require a compatible wheel or C++ build tools.
  echo See README.md for troubleshooting.
  pause
  exit /b 1
)
echo.
echo Installation complete.
pause
