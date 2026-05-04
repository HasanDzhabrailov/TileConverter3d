@echo off
setlocal

set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"

set "BACKEND_PORT=8080"
set "PUBLIC_HOST=%TERRAIN_WEB_PUBLIC_HOST%"
set "POWERSHELL=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"

where gradle >nul 2>nul
if errorlevel 1 (
  echo Gradle not found in PATH.
  echo Install Gradle 8+ and JDK 21, then try again.
  exit /b 1
)

if "%PUBLIC_HOST%"=="" for /f "tokens=2 delims=: " %%h in ('ipconfig ^| findstr /R /C:"IPv4.*172\.20\."') do set "PUBLIC_HOST=%%h"
if "%PUBLIC_HOST%"=="" for /f "tokens=2 delims=: " %%h in ('ipconfig ^| findstr /R /C:"IPv4.*192\.168\."') do set "PUBLIC_HOST=%%h"
if "%PUBLIC_HOST%"=="" for /f "tokens=2 delims=: " %%h in ('ipconfig ^| findstr /R /C:"IPv4.*10\."') do set "PUBLIC_HOST=%%h"
if "%PUBLIC_HOST%"=="" for /f "tokens=2 delims=: " %%h in ('ipconfig ^| findstr /R /C:"IPv4.*172\.(1[6-9]\|2[0-9]\|3[0-1])\."') do set "PUBLIC_HOST=%%h"
if "%PUBLIC_HOST%"=="" for /f %%h in ('"%POWERSHELL%" -NoProfile -Command "$c = New-Object System.Net.Sockets.UdpClient; $c.Connect(''8.8.8.8'',80); ($c.Client.LocalEndPoint).Address.IPAddressToString; $c.Close()" 2^>nul') do set "PUBLIC_HOST=%%h"
if "%PUBLIC_HOST%"=="." set "PUBLIC_HOST="
if "%PUBLIC_HOST%"=="0.0.0.0" set "PUBLIC_HOST="
if "%PUBLIC_HOST%"=="" set "PUBLIC_HOST=172.20.10.2"
for /f "tokens=* delims= " %%h in ("%PUBLIC_HOST%") do set "PUBLIC_HOST=%%h"

echo Public host: %PUBLIC_HOST%

call :kill_port_process %BACKEND_PORT% "backend"

echo Building Kotlin web UI assets...
pushd "%ROOT%"
call gradle -p kotlin/terrain-web-ui syncFrontendDist
if errorlevel 1 (
  popd
  echo Kotlin web UI build failed.
  exit /b 1
)
popd

echo Building backend distribution...
pushd "%ROOT%"
call gradle :terrain-web:installDist
if errorlevel 1 (
  popd
  echo Backend build failed.
  exit /b 1
)
popd

echo Starting backend...
if not exist "%ROOT%\web\data" mkdir "%ROOT%\web\data"
set "TERRAIN_WEB_PUBLIC_HOST=%PUBLIC_HOST%"
set "TERRAIN_WEB_HOST=0.0.0.0"
set "TERRAIN_WEB_PORT=%BACKEND_PORT%"
set "TERRAIN_WEB_STORAGE_ROOT=%ROOT%\web\data"
set "TERRAIN_WEB_FRONTEND_DIST=%ROOT%\web\frontend\dist"

echo Opening application in browser after backend starts...
start "Open Terrain Web" "%POWERSHELL%" -NoProfile -Command "Start-Sleep -Seconds 5; Start-Process 'http://localhost:%BACKEND_PORT%/'"

echo.
echo Backend:  http://localhost:%BACKEND_PORT%
echo Backend LAN: http://%PUBLIC_HOST%:%BACKEND_PORT%
echo Web UI:   http://localhost:%BACKEND_PORT%
echo.
echo Keep this window open while using Terrain Web. Press Ctrl+C to stop.

call "%ROOT%\kotlin\terrain-web\build\install\terrain-web\bin\terrain-web.bat"
set "SERVER_EXIT=%ERRORLEVEL%"
endlocal & exit /b %SERVER_EXIT%

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
