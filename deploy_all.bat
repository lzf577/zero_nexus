@echo off
chcp 65001 > nul
echo.
echo ======================================================
echo   Zerotier Nexus Deployment Script
echo ======================================================
echo.

echo [Step 1/2] Building and installing APK...
call gradlew.bat installDebug

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [ERROR] Build or installation failed. Check device connection.
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo [Step 2/2] Restarting app on all devices...

for /f "tokens=1" %%d in ('adb devices ^| findstr "device$"') do (
    echo   - Refreshing device: %%d...
    adb -s %%d shell am force-stop com.zerotier.nexus
)

echo.
echo ======================================================
echo   Done!
echo ======================================================
pause
