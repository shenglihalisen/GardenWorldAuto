# 花园世界自动化助手

基于 Gemma E4B 端侧大模型的《我的花园世界》游戏自动化脚本APP。

## 功能特性

### 🤖 AI 智能识别
- **Gemma E4B 端侧大模型**：完全离线运行，无需联网，保护隐私
- **多模态理解**：支持游戏画面识别 + 文本理解
- **实时分析**：每3秒自动捕获屏幕并分析游戏状态

### 🎮 自动化功能
- ✅ 自动收获成熟花卉
- ✅ 自动浇水
- ✅ 自动施肥
- ✅ 自动种植
- ✅ 自动领取任务奖励
- ✅ 智能识别游戏界面状态

### 📱 设备适配
- **特别优化**：红米 K80 Ultra
- **GPU加速**：利用天玑芯片NPU加速AI推理
- **内存优化**：约需6GB RAM运行大模型

## 系统要求

| 项目 | 最低要求 | 推荐配置 |
|------|---------|---------|
| Android版本 | 9.0 (API 28) | 14+ (API 34) |
| RAM | 6GB | 8GB+ |
| 存储空间 | 4GB | 6GB+ |
| 处理器 | 骁龙865/天玑1200 | 骁龙8Gen2/天玑9200+ |

## 安装步骤

### 1. 下载模型文件

由于模型文件较大（约3.6GB），需要手动下载：

```bash
# 下载 Gemma-4-E4B 模型
wget https://storage.googleapis.com/ai-edge-models/gemma-4-e4b-it.task

# 将模型文件放入手机
adb push gemma-4-e4b-it.task /sdcard/Download/
```

### 2. 安装APP

```bash
# 构建Release版本
./gradlew assembleRelease

# 安装到手机
adb install app/build/outputs/apk/release/app-release.apk
```

### 3. 配置权限

首次启动需要授予以下权限：
1. **存储权限** - 读取模型文件
2. **屏幕录制权限** - 捕获游戏画面
3. **无障碍服务** - 模拟点击操作

### 4. 开启无障碍服务

设置 → 无障碍 → 已安装服务 → 花园世界自动化 → 开启

## 使用说明

### 启动自动化

1. 打开《我的花园世界》游戏
2. 打开本APP
3. 点击"初始化"按钮
4. 授予屏幕录制权限
5. 点击"开始"按钮

### 配置选项

- **截图间隔**：1-10秒可调
- **自动收获**：自动收获成熟花卉
- **自动浇水**：自动为植物浇水
- **自动施肥**：自动施肥加速生长
- **自动种植**：自动种植新花卉
- **自动领取任务**：自动完成日常任务

## 技术架构

```
┌─────────────────────────────────────────┐
│           UI Layer (Compose)            │
├─────────────────────────────────────────┤
│       GameAutomationEngine              │
│  ┌─────────────┐    ┌───────────────┐  │
│  │ Gemma AI    │    │ ScreenCapture │  │
│  │ Inference   │◄──►│ Manager       │  │
│  └─────────────┘    └───────────────┘  │
├─────────────────────────────────────────┤
│    GardenAccessibilityService           │
│         (无障碍服务)                     │
├─────────────────────────────────────────┤
│         Android System                  │
└─────────────────────────────────────────┘
```

## 项目结构

```
app/src/main/java/com/gardenworld/auto/
├── ai/
│   └── GemmaInferenceEngine.kt      # Gemma大模型推理
├── automation/
│   └── GameAutomationEngine.kt      # 自动化引擎
├── service/
│   ├── GardenAccessibilityService.kt # 无障碍服务
│   └── AutoPlayService.kt           # 前台服务
├── vision/
│   └── ScreenCaptureManager.kt      # 屏幕捕获
├── ui/
│   └── theme/                       # UI主题
├── MainActivity.kt                  # 主界面
└── GardenWorldAutoApp.kt           # Application
```

## 自定义配置

### 修改点击坐标

编辑 `GameAutomationEngine.kt` 中的坐标值：

```kotlin
// 植物位置（根据你的手机屏幕调整）
val plantPositions = listOf(
    300f to 800f,   // 第1个植物
    540f to 800f,   // 第2个植物
    780f to 800f,   // 第3个植物
    // ...
)
```

### 调整AI参数

```kotlin
// GemmaInferenceEngine.kt
const val MAX_TOKENS = 1024      // 最大生成token数
const val TEMPERATURE = 0.7f     // 创造性程度
const val REDMI_K80_THREAD_COUNT = 4  // 推理线程数
```

## 常见问题

### Q: 模型文件太大，手机存储不够？
可以使用 Gemma-E2B 模型（约2.5GB），修改 `GemmaInferenceEngine.kt`：
```kotlin
const val MODEL_FILE = "gemma-4-e2b-it.task"
```

### Q: 识别不准确？
- 确保游戏界面清晰可见
- 调整截图间隔，给AI更多处理时间
- 在光线充足的环境下使用

### Q: 点击位置偏移？
不同手机分辨率不同，需要根据实际屏幕调整坐标值。

### Q: 耗电量大？
- 降低截图频率
- 使用E2B模型代替E4B
- 关闭不必要的自动化功能

## 免责声明

本工具仅供学习交流使用，请遵守游戏用户协议。使用本工具可能导致账号被封禁，开发者不承担任何责任。

## 开源协议

Apache License 2.0

## 致谢

- Google DeepMind - Gemma 开源模型
- Google AI Edge - 端侧推理SDK
- MediaPipe - 视觉处理框架
