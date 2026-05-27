@echo off
chcp 65001 >nul
echo ==========================================
echo   花园世界自动化 APK 构建脚本
echo ==========================================

:: 检查Java
java -version >nul 2>&1
if errorlevel 1 (
    echo ❌ Java未安装，请先安装JDK 17+
    echo    下载地址: https://adoptium.net/
    pause
    exit /b 1
)

:: 检查Gradle
where gradle >nul 2>&1
if errorlevel 1 (
    echo ⚠️ Gradle未安装，将使用wrapper
)

:: 设置SDK路径
if "%ANDROID_HOME%"=="" (
    if exist "%USERPROFILE%\AppData\Local\Android\Sdk" (
        set "ANDROID_HOME=%USERPROFILE%\AppData\Local\Android\Sdk"
    ) else if exist "%LOCALAPPDATA%\Android\Sdk" (
        set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
    ) else if exist "C:\Android\Sdk" (
        set "ANDROID_HOME=C:\Android\Sdk"
    )
)

echo 📁 ANDROID_HOME: %ANDROID_HOME%

if "%ANDROID_HOME%"=="" (
    echo ❌ Android SDK未找到
    echo    请安装Android Studio或设置ANDROID_HOME环境变量
    pause
    exit /b 1
)

:: 创建本地配置
echo sdk.dir=%ANDROID_HOME% > local.properties

:: 清理
echo 🧹 清理旧构建...
call gradlew.bat clean

:: 构建Debug版本
echo 🔨 开始构建Debug APK...
call gradlew.bat assembleDebug

:: 检查结果
if exist "app\build\outputs\apk\debug\app-debug.apk" (
    echo.
    echo ==========================================
    echo   ✅ 构建成功！
    echo ==========================================
    echo.
    echo 📱 APK位置:
    echo    %cd%\app\build\outputs\apk\debug\app-debug.apk
    echo.
    echo 📦 安装到手机:
    echo    adb install app\build\outputs\apk\debug\app-debug.apk
    echo.
    pause
) else (
    echo ❌ 构建失败，请检查错误信息
    pause
    exit /b 1
)
