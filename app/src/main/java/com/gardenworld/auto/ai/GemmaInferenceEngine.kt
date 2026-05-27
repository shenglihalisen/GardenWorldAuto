package com.gardenworld.auto.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.activity.result.contract.ActivityResultContract
import com.google.ai.edge.InferenceSession
import com.google.ai.edge.ModelConfig
import com.google.ai.edge.litert.GpuDelegate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Gemma 端侧大模型推理引擎
 * 支持 Gemma 3 270M (125MB) 和 Gemma 4 E2B (2.5GB)
 * 支持从本地文件或assets加载模型
 */
class GemmaInferenceEngine(private val context: Context) {
    
    companion object {
        // Gemma 3 270M (轻量版，推荐离线使用)
        const val MODEL_NAME_270M = "gemma-3-270m-it-q8"
        const val MODEL_FILE_270M = "gemma-3-270m-it-q8.task"
        
        // Gemma 4 E2B (完整版，效果更好)
        const val MODEL_NAME_E2B = "gemma-4-e2b-it"
        const val MODEL_FILE_E2B = "gemma-4-e2b-it.task"
        
        // 默认使用270M轻量版
        var DEFAULT_MODEL = MODEL_FILE_270M
        
        const val MAX_TOKENS = 512
        const val TEMPERATURE = 0.7f
        
        // 红米K80 Ultra 适配参数
        const val REDMI_K80_GPU_DELEGATE = true
        const val REDMI_K80_THREAD_COUNT = 4
        
        // 模型最小大小验证 (270M约125MB)
        const val MIN_MODEL_SIZE_270M = 100_000_000L
        const val MIN_MODEL_SIZE_E2B = 2_000_000_000L
    }
    
    private var inferenceSession: InferenceSession? = null
    private var isModelLoaded = false
    private var currentModelFile: String? = null
    
    /**
     * 检查模型是否存在
     */
    fun isModelAvailable(): Boolean {
        // 检查内部存储
        val internalModel = File(context.filesDir, DEFAULT_MODEL)
        if (internalModel.exists() && internalModel.length() > MIN_MODEL_SIZE_270M) {
            return true
        }
        
        // 检查外部存储
        val externalDir = context.getExternalFilesDir(null)
        if (externalDir != null) {
            val externalModel = File(externalDir, DEFAULT_MODEL)
            if (externalModel.exists() && externalModel.length() > MIN_MODEL_SIZE_270M) {
                return true
            }
        }
        
        return false
    }
    
    /**
     * 获取模型文件路径
     */
    fun getModelPath(): String {
        val internal = File(context.filesDir, DEFAULT_MODEL)
        if (internal.exists()) return internal.absolutePath
        
        val external = context.getExternalFilesDir(null)?.let { 
            File(it, DEFAULT_MODEL) 
        }
        if (external?.exists() == true) return external.absolutePath
        
        return internal.absolutePath
    }
    
    /**
     * 从URI导入模型（用户选择文件）
     */
    suspend fun importModelFromUri(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Timber.d("从URI导入模型: $uri")
            
            val targetFile = File(context.filesDir, DEFAULT_MODEL)
            
            context.contentResolver.openInputStream(uri)?.use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            
            Timber.d("模型已导入: ${targetFile.length() / 1024 / 1024}MB")
            Result.success(Unit)
            
        } catch (e: Exception) {
            Timber.e(e, "模型导入失败")
            Result.failure(e)
        }
    }
    
    /**
     * 从assets提取模型（首次安装）
     */
    suspend fun extractModelFromAssets(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val targetFile = File(context.filesDir, DEFAULT_MODEL)
            
            // 检查是否已提取
            if (targetFile.exists() && targetFile.length() > MIN_MODEL_SIZE_270M) {
                Timber.d("模型已存在")
                return@withContext Result.success(Unit)
            }
            
            // 尝试从assets提取
            val modelPath = "models/$DEFAULT_MODEL"
            try {
                context.assets.open(modelPath).use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Timber.d("从assets提取模型成功: ${targetFile.length() / 1024 / 1024}MB")
                return@withContext Result.success(Unit)
            } catch (e: Exception) {
                Timber.d("assets中无模型文件，需要手动下载")
                return@withContext Result.failure(Exception("assets中无模型文件"))
            }
            
        } catch (e: Exception) {
            Timber.e(e, "提取模型失败")
            Result.failure(e)
        }
    }
    
    /**
     * 初始化模型
     */
    suspend fun initializeModel(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val modelPath = getModelPath()
            val modelFile = File(modelPath)
            
            // 检查模型是否存在
            if (!modelFile.exists() || modelFile.length() < MIN_MODEL_SIZE_270M) {
                // 尝试从assets提取
                extractModelFromAssets().onFailure {
                    Timber.w("模型不存在，请先下载模型")
                    return@withContext Result.failure(Exception("模型文件不存在，请下载模型"))
                }
            }
            
            Timber.d("正在加载模型: ${modelFile.length() / 1024 / 1024}MB")
            
            // 配置模型
            val config = ModelConfig.builder()
                .setModelPath(modelPath)
                .setMaxTokens(MAX_TOKENS)
                .setTemperature(TEMPERATURE)
                .setTopP(0.9f)
                .setTopK(40)
                .apply {
                    if (REDMI_K80_GPU_DELEGATE) {
                        try {
                            setGpuDelegate(GpuDelegate())
                        } catch (e: Exception) {
                            Timber.w("GPU加速不可用: ${e.message}")
                        }
                    }
                    setNumThreads(REDMI_K80_THREAD_COUNT)
                }
                .build()
            
            // 创建推理会话
            inferenceSession = InferenceSession.create(context, config)
            isModelLoaded = true
            currentModelFile = modelPath
            
            Timber.d("模型初始化成功!")
            Result.success(Unit)
            
        } catch (e: Exception) {
            Timber.e(e, "模型初始化失败")
            Result.failure(e)
        }
    }
    
    /**
     * 纯文本推理
     */
    suspend fun generateText(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (!isModelLoaded) {
                return@withContext Result.failure(Exception("模型未加载"))
            }
            
            val systemPrompt = buildSystemPrompt()
            val fullPrompt = "$systemPrompt\n\nUser: $prompt\n\nAssistant:"
            
            val response = inferenceSession?.generateResponse(fullPrompt)
                ?: return@withContext Result.failure(Exception("推理会话为空"))
            
            Result.success(response.trim())
            
        } catch (e: Exception) {
            Timber.e(e, "文本生成失败")
            Result.failure(e)
        }
    }
    
    /**
     * 流式文本生成
     */
    fun generateTextStream(prompt: String): Flow<String> = flow {
        try {
            if (!isModelLoaded) throw Exception("模型未加载")
            
            val systemPrompt = buildSystemPrompt()
            val fullPrompt = "$systemPrompt\n\nUser: $prompt\n\nAssistant:"
            
            inferenceSession?.generateResponseStream(fullPrompt)?.collect { token ->
                emit(token)
            }
            
        } catch (e: Exception) {
            Timber.e(e, "流式生成失败")
            throw e
        }
    }.flowOn(Dispatchers.IO)
    
    /**
     * 游戏画面分析
     */
    suspend fun analyzeGameScreen(bitmap: android.graphics.Bitmap): Result<GameScreenAnalysis> = withContext(Dispatchers.IO) {
        try {
            if (!isModelLoaded) {
                return@withContext Result.failure(Exception("模型未加载"))
            }
            
            val prompt = buildGameAnalysisPrompt()
            Timber.d("分析游戏画面...")
            
            val response = inferenceSession?.generateResponse(prompt)
                ?: return@withContext Result.failure(Exception("推理失败"))
            
            val analysis = parseGameAnalysis(response)
            Timber.d("分析完成: ${analysis.screenType}")
            
            Result.success(analysis)
            
        } catch (e: Exception) {
            Timber.e(e, "画面分析失败")
            Result.failure(e)
        }
    }
    
    /**
     * 构建系统提示词
     */
    private fun buildSystemPrompt(): String {
        return """
你是《我的花园世界》游戏的AI助手，帮助玩家自动化操作。

游戏功能：
- 种植花卉：点击空地种种子，等待成熟
- 浇水施肥：加速植物生长
- 收获：点击成熟植物获得金币
- 任务：完成日常任务获得奖励
- 商店：购买种子和道具

请用中文回复，简洁明了。
        """.trimIndent()
    }
    
    /**
     * 构建游戏分析提示词
     */
    private fun buildGameAnalysisPrompt(): String {
        return """
分析这张《我的花园世界》游戏截图，返回JSON格式：
{
    "screenType": "主界面/种植/收获/任务/商店/其他",
    "interactiveElements": [{"name": "名称", "type": "类型", "bounds": [x1,y1,x2,y2]}],
    "resources": {"gold": 0, "diamond": 0, "energy": 0},
    "pendingTasks": ["浇水", "施肥", "收获"],
    "notifications": ["提示"]
}

只返回JSON，不要其他内容。
        """.trimIndent()
    }
    
    /**
     * 解析分析结果
     */
    private fun parseGameAnalysis(response: String): GameScreenAnalysis {
        return try {
            // 简单解析JSON
            val screenType = extractJsonValue(response, "screenType") ?: "未知"
            val gold = extractJsonValue(response, "gold")?.toIntOrNull() ?: 0
            val diamond = extractJsonValue(response, "diamond")?.toIntOrNull() ?: 0
            val energy = extractJsonValue(response, "energy")?.toIntOrNull() ?: 0
            
            GameScreenAnalysis(
                screenType = screenType,
                interactiveElements = emptyList(),
                resources = GameResources(gold, diamond, energy),
                pendingTasks = emptyList(),
                notifications = emptyList()
            )
        } catch (e: Exception) {
            Timber.e(e, "解析失败，使用默认值")
            GameScreenAnalysis("未知", emptyList(), GameResources(), emptyList(), emptyList())
        }
    }
    
    private fun extractJsonValue(json: String, key: String): String? {
        val regex = """"$key":\s*"?([^",}]+)""?".toRegex()
        return regex.find(json)?.groupValues?.get(1)?.trim()
    }
    
    /**
     * 获取模型下载链接
     */
    fun getModelDownloadUrl(): String {
        return when (DEFAULT_MODEL) {
            MODEL_FILE_270M -> "https://huggingface.co/litert-community/gemma-3-270m-it-q8/resolve/main/gemma-3-270m-it-q8.task"
            MODEL_FILE_E2B -> "https://huggingface.co/google/gemma-4-e2b-it-litert/resolve/main/gemma-4-e2b-it-litert.task"
            else -> ""
        }
    }
    
    /**
     * 释放资源
     */
    fun release() {
        inferenceSession?.close()
        inferenceSession = null
        isModelLoaded = false
        Timber.d("推理引擎已释放")
    }
}

/**
 * 游戏画面分析结果
 */
data class GameScreenAnalysis(
    val screenType: String,
    val interactiveElements: List<InteractiveElement>,
    val resources: GameResources,
    val pendingTasks: List<String>,
    val notifications: List<String>
)

data class InteractiveElement(
    val name: String,
    val type: String,
    val bounds: List<Int>
)

data class GameResources(
    val gold: Int = 0,
    val diamond: Int = 0,
    val energy: Int = 0
)
