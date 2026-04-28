@echo off
setlocal

set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"

set "BACKEND_PORT=8080"
set "FRONTEND_PORT=5173"
set "PUBLIC_HOST=%TERRAIN_WEB_PUBLIC_HOST%"

where python >nul 2>nul
if errorlevel 1 (
  echo Python not found in PATH.
  exit /b 1
)

where node >nul 2>nul
if errorlevel 1 (
  echo Node.js not found in PATH.
  echo Install Node 18+ and try again.
  exit /b 1
)

where npm >nul 2>nul
if errorlevel 1 (
  echo npm not found in PATH.
  exit /b 1
)

for /f %%v in ('node -p "process.versions.node.split('.')[0]"') do set "NODE_MAJOR=%%v"
if "%NODE_MAJOR%"=="" (
  echo Failed to detect Node.js version.
  exit /b 1
)
if %NODE_MAJOR% LSS 18 (
  echo Node.js 18+ is required. Current major version: %NODE_MAJOR%
  exit /b 1
)

if "%PUBLIC_HOST%"=="" for /f "tokens=2 delims=: " %%h in ('ipconfig ^| findstr /R /C:"IPv4.*172\.20\."') do set "PUBLIC_HOST=%%h"
if "%PUBLIC_HOST%"=="" for /f "tokens=2 delims=: " %%h in ('ipconfig ^| findstr /R /C:"IPv4.*192\.168\."') do set "PUBLIC_HOST=%%h"
if "%PUBLIC_HOST%"=="" for /f "tokens=2 delims=: " %%h in ('ipconfig ^| findstr /R /C:"IPv4.*10\."') do set "PUBLIC_HOST=%%h"
if "%PUBLIC_HOST%"=="" for /f "tokens=2 delims=: " %%h in ('ipconfig ^| findstr /R /C:"IPv4.*172\.(1[6-9]\|2[0-9]\|3[0-1])\."') do set "PUBLIC_HOST=%%h"
if "%PUBLIC_HOST%"=="" for /f %%h in ('python -c "import socket; s=socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.connect((''8.8.8.8'', 80)); print(s.getsockname()[0]); s.close()" 2^>nul') do set "PUBLIC_HOST=%%h"
if "%PUBLIC_HOST%"=="." set "PUBLIC_HOST="
if "%PUBLIC_HOST%"=="0.0.0.0" set "PUBLIC_HOST="
if "%PUBLIC_HOST%"=="" set "PUBLIC_HOST=172.20.10.2"
for /f "tokens=* delims= " %%h in ("%PUBLIC_HOST%") do set "PUBLIC_HOST=%%h"

echo Public host: %PUBLIC_HOST%

call :kill_port_process %BACKEND_PORT% "backend"
call :kill_port_process %FRONTEND_PORT% "frontend"

echo Starting backend...
start "Terrain Backend" cmd /k "set TERRAIN_WEB_PUBLIC_HOST=%PUBLIC_HOST% && cd /d "%ROOT%" && python -m pip install -e ./converter && cd /d "%ROOT%\web\backend" && python -m pip install -e ".[test]" && uvicorn app.main:app --host 0.0.0.0 --port %BACKEND_PORT%"

echo Waiting for backend health check...
set "BACKEND_READY="
for /l %%i in (1,1,60) do (
  python -c "import sys, urllib.request; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:%BACKEND_PORT%/api/health', timeout=1).status == 200 else 1)" >nul 2>nul
  if not errorlevel 1 (
    set "BACKEND_READY=1"
    goto backend_ready
  )
  timeout /t 1 /nobreak >nul
)

:backend_ready
if not defined BACKEND_READY (
  echo Backend did not become ready within 60 seconds.
  echo Check the "Terrain Backend" window for install or startup errors.
  exit /b 1
)

echo Starting frontend...
start "Terrain Frontend" cmd /k "cd /d "%ROOT%\web\frontend" && npm install && npm run dev -- --host 0.0.0.0 --port %FRONTEND_PORT%"

echo Opening application in browser...
start "" "http://localhost:5173/"

echo.
echo Backend:  http://127.0.0.1:%BACKEND_PORT%
echo Backend LAN: http://%PUBLIC_HOST%:%BACKEND_PORT%
echo Frontend: http://127.0.0.1:%FRONTEND_PORT%
echo.
echo If frontend preview should use the bundled backend UI instead, open:
echo http://127.0.0.1:%BACKEND_PORT%

endlocal
exit /b 0

:kill_port_process
set "TARGET_PORT=%~1"
set "TARGET_NAME=%~2"
set "TARGET_PID="

for /f "tokens=5" %%p in ('netstat -ano ^| findstr /R /C:":%TARGET_PORT% .*LISTENING"') do (
  set "TARGET_PID=%%p"
  goto kill_pid
)

exit /b 0

:kill_pid
if defined TARGET_PID (
  echo Stopping existing %TARGET_NAME% process on port %TARGET_PORT% ^(PID %TARGET_PID%^)...
  taskkill /PID %TARGET_PID% /F >nul 2>nul
)
exit /b 0
