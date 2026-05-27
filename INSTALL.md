# 快速安装指南

## 一键封装脚本

### 1. 下载模型文件

```bash
# 创建模型目录
mkdir -p /workspace/GardenWorldAuto/app/src/main/assets/models

# 下载 Gemma-4-E4B 模型 (约3.6GB)
cd /workspace/GardenWorldAuto/app/src/main/assets/models
wget https://storage.googleapis.com/ai-edge-models/gemma-4-e4b-it.task

# 或使用轻量版 E2B 模型 (约2.5GB)
# wget https://storage.googleapis.com/ai-edge-models/gemma-4-e2b-it.task
```

### 2. 配置Android SDK路径

编辑 `local.properties` 文件，设置你的Android SDK路径：

**macOS:**
```properties
sdk.dir=/Users/你的用户名/Library/Android/sdk
```

**Windows:**
```properties
sdk.dir=C\:\\Users\\你的用户名\\AppData\\Local\\Android\\Sdk
```

**Linux:**
```properties
sdk.dir=/home/你的用户名/Android/Sdk
```

### 3. 构建APK

```bash
cd /workspace/GardenWorldAuto

# 给gradlew添加执行权限 (macOS/Linux)
chmod +x gradlew

# 构建Release版本
./gradlew assembleRelease

# 或构建Debug版本
./gradlew assembleDebug
```

### 4. 安装到手机

```bash
# 连接手机并开启USB调试
adb devices

# 安装APK
adb install app/build/outputs/apk/release/app-release.apk

# 如果已安装，强制重新安装
adb install -r app/build/outputs/apk/release/app-release.apk
```

## 使用步骤

### 首次使用

1. **打开APP** - 授予存储权限
2. **开启无障碍服务** - 设置 → 无障碍 → 花园世界自动化 → 开启
3. **授予屏幕录制权限** - 点击"初始化"按钮，允许屏幕录制
4. **下载/加载模型** - 首次使用需要下载3.6GB模型文件

### 启动自动化

1. 打开《我的花园世界》游戏
2. 回到本APP，点击"开始"
3. 查看日志确认运行状态

## 项目结构

```
GardenWorldAuto/
├── app/
│   ├── build.gradle.kts          # 构建配置
│   ├── proguard-rules.pro        # 混淆规则
│   └── src/main/
│       ├── AndroidManifest.xml   # 应用配置
│       ├── assets/models/        # Gemma模型文件
│       ├── java/com/gardenworld/auto/
│       │   ├── ai/
│       │   │   └── GemmaInferenceEngine.kt      # AI推理引擎
│       │   ├── automation/
│       │   │   └── GameAutomationEngine.kt      # 自动化引擎
│       │   ├── service/
│       │   │   ├── GardenAccessibilityService.kt # 无障碍服务
│       │   │   ├── AutoPlayService.kt           # 前台服务
│       │   │   └── ModelDownloadService.kt      # 模型下载
│       │   ├── vision/
│       │   │   └── ScreenCaptureManager.kt      # 屏幕捕获
│       │   ├── ui/theme/
│       │   ├── MainActivity.kt    # 主界面
│       │   └── GardenWorldAutoApp.kt
│       └── res/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── local.properties              # SDK路径配置
└── README.md
```

## 常见问题

### Q: 构建失败，提示找不到Android SDK?
编辑 `local.properties` 文件，正确设置 `sdk.dir` 路径。

### Q: 模型文件太大，无法打包?
模型文件不需要打包进APK，APP会自动从网络下载或从SD卡加载。

### Q: 无障碍服务无法启动?
确保在系统设置中手动开启无障碍服务权限。

### Q: 点击位置不准确?
不同手机分辨率不同，需要修改 `GameAutomationEngine.kt` 中的坐标值。

## 技术参数

| 参数 | 值 |
|------|-----|
| 目标游戏 | 我的花园世界 (cn.lbwdhysj.gf) |
| AI模型 | Gemma-4-E4B (4B参数) |
| 模型大小 | 约3.6GB |
| 内存需求 | 6GB+ RAM |
| 最低Android版本 | API 28 (Android 9.0) |
| 推荐Android版本 | API 34+ (Android 14+) |

## 开发调试

```bash
# 查看日志
adb logcat -s GardenWorldAuto:D

# 清除数据重新安装
adb uninstall com.gardenworld.auto
adb install app/build/outputs/apk/debug/app-debug.apk

# 直接运行
./gradlew installDebug
```
