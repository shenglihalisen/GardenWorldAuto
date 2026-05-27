# 模型下载与打包指南

## 🚀 一键下载脚本

### Windows (PowerShell)
```powershell
# 创建模型目录
mkdir -p app/src/main/assets/models

# 下载 Gemma 3 270M (约125MB，推荐)
Invoke-WebRequest -Uri "https://huggingface.co/litert-community/gemma-3-270m-it-q8/resolve/main/gemma-3-270m-it-q8.task" -OutFile "app/src/main/assets/models/gemma-3-270m-it-q8.task"

# 或下载 Gemma 4 E2B (约2.5GB，效果更好)
# Invoke-WebRequest -Uri "https://huggingface.co/google/gemma-4-e2b-it-litert/resolve/main/gemma-4-e2b-it-litert.task" -OutFile "app/src/main/assets/models/gemma-4-e2b-it.task"
```

### macOS / Linux
```bash
# 创建模型目录
mkdir -p app/src/main/assets/models

# 下载 Gemma 3 270M (约125MB)
wget -O app/src/main/assets/models/gemma-3-270m-it-q8.task \
  "https://huggingface.co/litert-community/gemma-3-270m-it-q8/resolve/main/gemma-3-270m-it-q8.task"

# 或下载 Gemma 4 E2B (约2.5GB)
# wget -O app/src/main/assets/models/gemma-4-e2b-it.task \
#   "https://huggingface.co/google/gemma-4-e2b-it-litert/resolve/main/gemma-4-e2b-it-litert.task"
```

### 使用IDM/迅雷（下载更快）
```
下载链接:
- Gemma 3 270M: https://huggingface.co/litert-community/gemma-3-270m-it-q8/resolve/main/gemma-3-270m-it-q8.task
- Gemma 4 E2B: https://huggingface.co/google/gemma-4-e2b-it-litert/resolve/main/gemma-4-e2b-it-litert.task

下载完成后，放到:
app/src/main/assets/models/
```

---

## 📱 模型版本对比

| 模型 | 大小 | 内存需求 | 效果 | 推荐场景 |
|------|------|---------|------|---------|
| **Gemma 3 270M Q8** | ~125MB | 256MB | ⭐⭐⭐ | 快速尝鲜/低配手机 |
| **Gemma 4 E2B** | ~2.5GB | 4GB | ⭐⭐⭐⭐⭐ | 红米K80推荐 |

---

## 🔧 完整打包流程

### 1. 下载模型
```bash
# 下载模型到 models 目录
mkdir -p app/src/main/assets/models
wget -O app/src/main/assets/models/gemma-3-270m-it-q8.task \
  "https://huggingface.co/litert-community/gemma-3-270m-it-q8/resolve/main/gemma-3-270m-it-q8.task"
```

### 2. 构建APK
```bash
# macOS/Linux
chmod +x gradlew build.sh
./build.sh

# Windows
build.bat
```

### 3. 验证模型打包
```bash
# 检查APK中是否包含模型
unzip -l app/build/outputs/apk/release/app-release.apk | grep ".task"
```

---

## 💡 离线使用说明

APP构建完成后，模型会打包在APK中。首次安装时，模型会自动提取到手机存储：

```
/data/user/0/com.gardenworld.auto/files/gemma-3-270m-it-q8.task
```

---

## ⚠️ 常见问题

### Q: 模型下载太慢？
- 使用IDM/迅雷多线程下载
- 国内可访问 HuggingFace 镜像: hf-mirror.com

### Q: 下载链接失效？
- 访问 https://huggingface.co/litert-community 查找最新模型
- 或访问 https://huggingface.co/google 查找官方模型

### Q: 模型太大无法打包？
- 使用 Gemma 3 270M (125MB)
- 或使用 OBB 扩展文件方式

---

## 📦 模型来源

- **Gemma 3 270M**: https://huggingface.co/litert-community/gemma-3-270m-it-q8
- **Gemma 4 E2B**: https://huggingface.co/google/gemma-4-e2b-it-litert
