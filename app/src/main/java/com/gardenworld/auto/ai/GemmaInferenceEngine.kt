package com.gardenworld.auto.ai

import android.content.Context
import android.graphics.Bitmap
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
import java.io.FileOutputStream
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Gemma E4B 端侧大模型推理引擎
 * 支持多模态输入：文本 + 图像
 */
class GemmaInferenceEngine(private val context: Context) {
    
    companion object {
        const val MODEL_NAME = "gemma-4-e4b-it"
        const val MODEL_FILE = "gemma-4-e4b-it.task"
        const val MODEL_URL = "https://storage.googleapis.com/ai-edge-models/gemma-4-e4b-it.task"
        const val MAX_TOKENS = 1024
        const val TEMPERATURE = 0.7f
        
        // 红米K80 Ultra 适配参数
        const val REDMI_K80_GPU_DELEGATE = true
        const val REDMI_K80_THREAD_COUNT = 4
    }
    
    private var inferenceSession: InferenceSession? = null
    private var isModelLoaded = false
    
    /**
     * 检查模型是否存在
     */
    fun isModelAvailable(): Boolean {
        val modelFile = File(context.filesDir, MODEL_FILE)
        return modelFile.exists() && modelFile.length() > 3_000_000_000L // 约3GB
    }
    
    /**
     * 获取模型文件路径
     */
    fun getModelPath(): String {
        return File(context.filesDir, MODEL_FILE).absolutePath
    }
    
    /**
     * 初始化模型
     */
    suspend fun initializeModel(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val modelPath = getModelPath()
            
            if (!File(modelPath).exists()) {
                return@withContext Result.failure(Exception("Model file not found. Please download first."))
            }
            
            // 配置模型
            val config = ModelConfig.builder()
                .setModelPath(modelPath)
                .setMaxTokens(MAX_TOKENS)
                .setTemperature(TEMPERATURE)
                .setTopP(0.9f)
                .setTopK(40)
                .apply {
                    // 红米K80 Ultra GPU加速
                    if (REDMI_K80_GPU_DELEGATE) {
                        setGpuDelegate(GpuDelegate())
                    }
                    setNumThreads(REDMI_K80_THREAD_COUNT)
                }
                .build()
            
            // 创建推理会话
            inferenceSession = InferenceSession.create(context, config)
            isModelLoaded = true
            
            Timber.d("Gemma E4B model initialized successfully")
            Result.success(Unit)
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize Gemma model")
            Result.failure(e)
        }
    }
    
    /**
     * 纯文本推理
     */
    suspend fun generateText(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (!isModelLoaded) {
                return@withContext Result.failure(Exception("Model not loaded"))
            }
            
            val systemPrompt = buildSystemPrompt()
            val fullPrompt = "$systemPrompt\n\nUser: $prompt\n\nAssistant:"
            
            val response = inferenceSession?.generateResponse(fullPrompt)
                ?: return@withContext Result.failure(Exception("Inference session is null"))
            
            Result.success(response.trim())
            
        } catch (e: Exception) {
            Timber.e(e, "Text generation failed")
            Result.failure(e)
        }
    }
    
    /**
     * 流式文本生成
     */
    fun generateTextStream(prompt: String): Flow<String> = flow {
        try {
            if (!isModelLoaded) {
                throw Exception("Model not loaded")
            }
            
            val systemPrompt = buildSystemPrompt()
            val fullPrompt = "$systemPrompt\n\nUser: $prompt\n\nAssistant:"
            
            // 使用流式生成
            inferenceSession?.generateResponseStream(fullPrompt)?.collect { token ->
                emit(token)
            }
            
        } catch (e: Exception) {
            Timber.e(e, "Stream generation failed")
            throw e
        }
    }.flowOn(Dispatchers.IO)
    
    /**
     * 图像理解 + 文本推理（多模态）
     */
    suspend fun analyzeImage(bitmap: Bitmap, question: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (!isModelLoaded) {
                return@withContext Result.failure(Exception("Model not loaded"))
            }
            
            // 预处理图像
            val processedBitmap = preprocessImage(bitmap)
            
            // 构建多模态提示词
            val prompt = buildMultimodalPrompt(processedBitmap, question)
            
            val response = inferenceSession?.generateResponse(prompt)
                ?: return@withContext Result.failure(Exception("Inference session is null"))
            
            Result.success(response.trim())
            
        } catch (e: Exception) {
            Timber.e(e, "Image analysis failed")
            Result.failure(e)
        }
    }
    
    /**
     * 游戏画面分析 - 专门用于识别游戏UI元素
     */
    suspend fun analyzeGameScreen(bitmap: Bitmap): Result<GameScreenAnalysis> = withContext(Dispatchers.IO) {
        try {
            if (!isModelLoaded) {
                return@withContext Result.failure(Exception("Model not loaded"))
            }
            
            val prompt = """
                分析这张《我的花园世界》游戏截图，识别以下元素：
                1. 当前游戏状态（主界面/种植/收获/任务/商店等）
                2. 可交互元素的位置（按钮、图标、NPC等）
                3. 资源数量（金币、钻石、体力等）
                4. 待处理任务（浇水、施肥、收获等）
                5. 任何弹窗或提示信息
                
                请以JSON格式返回：
                {
                    "screenType": "主界面/种植/收获/任务/商店/其他",
                    "interactiveElements": [
                        {"name": "元素名称", "type": "button/icon/npc", "bounds": [x1, y1, x2, y2]}
                    ],
                    "resources": {
                        "gold": 金币数量,
                        "diamond": 钻石数量,
                        "energy": 体力值
                    },
                    "pendingTasks": ["浇水", "施肥", "收获"],
                    "notifications": ["任何弹窗或提示"]
                }
            """.trimIndent()
            
            val response = inferenceSession?.generateResponse(prompt)
                ?: return@withContext Result.failure(Exception("Inference session is null"))
            
            // 解析JSON响应
            val analysis = parseGameAnalysis(response)
            Result.success(analysis)
            
        } catch (e: Exception) {
            Timber.e(e, "Game screen analysis failed")
            Result.failure(e)
        }
    }
    
    /**
     * 构建系统提示词
     */
    private fun buildSystemPrompt(): String {
        return """
            你是一个专业的《我的花园世界》游戏助手，帮助玩家自动化操作游戏。
            
            游戏规则：
            - 种植花卉：点击空地选择种子，不同花卉成熟时间不同
            - 浇水施肥：定期维护可加速生长
            - 收获：成熟后点击收获获得金币和经验
            - 任务系统：完成日常任务获得奖励
            - 花艺制作：将花朵加工成花艺作品出售
            - 好友互动：访问好友花园、互赠花种
            
            你的职责：
            1. 分析游戏画面，识别当前状态和可操作元素
            2. 提供最优操作策略
            3. 识别任务优先级
            4. 检测异常状态（如网络错误、体力不足等）
            
            请用中文回复，简洁明了。
        """.trimIndent()
    }
    
    /**
     * 构建多模态提示词
     */
    private fun buildMultimodalPrompt(bitmap: Bitmap, question: String): String {
        return """
            [IMAGE]
            $question
        """.trimIndent()
    }
    
    /**
     * 预处理图像
     */
    private fun preprocessImage(bitmap: Bitmap): Bitmap {
        // 调整图像大小以优化推理速度
        val maxSize = 1024
        val width = bitmap.width
        val height = bitmap.height
        
        return if (width > maxSize || height > maxSize) {
            val ratio = maxSize.toFloat() / maxOf(width, height)
            val newWidth = (width * ratio).toInt()
            val newHeight = (height * ratio).toInt()
            Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        } else {
            bitmap
        }
    }
    
    /**
     * 解析游戏分析结果
     */
    private fun parseGameAnalysis(response: String): GameScreenAnalysis {
        // 简化的解析逻辑，实际应用中应使用JSON解析器
        return GameScreenAnalysis(
            screenType = extractValue(response, "screenType") ?: "未知",
            interactiveElements = emptyList(),
            resources = GameResources(),
            pendingTasks = emptyList(),
            notifications = emptyList()
        )
    }
    
    private fun extractValue(response: String, key: String): String? {
        val regex = """"$key":\s*"([^"]+)"""".toRegex()
        return regex.find(response)?.groupValues?.get(1)
    }
    
    /**
     * 释放资源
     */
    fun release() {
        inferenceSession?.close()
        inferenceSession = null
        isModelLoaded = false
        Timber.d("Gemma inference engine released")
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
