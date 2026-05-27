#!/bin/bash

# 花园世界自动化 - 一键构建脚本
# 使用方法: ./build.sh

echo "=========================================="
echo "  花园世界自动化 APK 构建脚本"
echo "=========================================="

# 检查Java
if ! command -v java &> /dev/null; then
    echo "❌ Java未安装，请先安装JDK 17+"
    echo "   macOS: brew install openjdk@17"
    echo "   Ubuntu: sudo apt install openjdk-17-jdk"
    exit 1
fi

# 检查Gradle
if ! command -v gradle &> /dev/null; then
    echo "⚠️ Gradle未安装，将使用wrapper"
fi

# 设置SDK路径
if [ -z "$ANDROID_HOME" ]; then
    if [ -d "$HOME/Library/Android/sdk" ]; then
        export ANDROID_HOME="$HOME/Library/Android/sdk"
    elif [ -d "$HOME/Android/Sdk" ]; then
        export ANDROID_HOME="$HOME/Android/Sdk"
    elif [ -d "/usr/lib/android-sdk" ]; then
        export ANDROID_HOME="/usr/lib/android-sdk"
    fi
fi

echo "📁 ANDROID_HOME: $ANDROID_HOME"

if [ -z "$ANDROID_HOME" ]; then
    echo "❌ Android SDK未找到"
    echo "   请设置ANDROID_HOME环境变量或安装Android Studio"
    exit 1
fi

# 创建本地配置
echo "sdk.dir=$ANDROID_HOME" > local.properties

# 清理
echo "🧹 清理旧构建..."
./gradlew clean 2>/dev/null

# 构建Debug版本
echo "🔨 开始构建Debug APK..."
./gradlew assembleDebug

# 检查结果
if [ -f "app/build/outputs/apk/debug/app-debug.apk" ]; then
    echo ""
    echo "=========================================="
    echo "  ✅ 构建成功！"
    echo "=========================================="
    echo ""
    echo "📱 APK位置:"
    echo "   $(pwd)/app/build/outputs/apk/debug/app-debug.apk"
    echo ""
    echo "📦 安装到手机:"
    echo "   adb install app/build/outputs/apk/debug/app-debug.apk"
    echo ""
else
    echo "❌ 构建失败，请检查错误信息"
    exit 1
fi
