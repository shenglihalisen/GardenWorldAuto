package com.gardenworld.auto.ai

import android.content.Context
import android.content.Intent
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

class GemmaInferenceEngine(private val context: Context) {
    
    companion object {
        // 使用 Gemma 4 E4B 模型（效果最好，约5GB）
        const val MODEL_FILE = "gemma-4-e4b-it.task"
        const val MAX_TOKENS = 1024  // E4B支持更大的token数
        const val TEMPERATURE = 0.7f
        const val MIN_MODEL_SIZE = 4_000_000_000L  // 约4GB最小大小
    }
    
    private var inferenceSession: InferenceSession? = null
    private var isModelLoaded = false
    
    fun isModelAvailable(): Boolean {
        val modelFile = File(context.filesDir, MODEL_FILE)
        return modelFile.exists() && modelFile.length() > MIN_MODEL_SIZE
    }
    
    fun getModelPath(): String = File(context.filesDir, MODEL_FILE).absolutePath
    
    suspend fun initializeModel(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val modelPath = getModelPath()
            val modelFile = File(modelPath)
            
            if (!modelFile.exists() || modelFile.length() < MIN_MODEL_SIZE) {
                Timber.w("模型不存在，请先下载模型")
                return@withContext Result.failure(Exception("模型文件不存在，请下载模型"))
            }
            
            Timber.d("正在加载模型: ${modelFile.length() / 1024 / 1024}MB")
            
            val config = ModelConfig.builder()
                .setModelPath(modelPath)
                .setMaxTokens(MAX_TOKENS)
                .setTemperature(TEMPERATURE)
                .setTopP(0.9f)
                .setTopK(40)
                .apply {
                    try { setGpuDelegate(GpuDelegate()) } catch (e: Exception) { Timber.w("GPU加速不可用") }
                    setNumThreads(4)
                }
                .build()
            
            inferenceSession = InferenceSession.create(context, config)
            isModelLoaded = true
            Timber.d("模型初始化成功!")
            Result.success(Unit)
            
        } catch (e: Exception) {
            Timber.e(e, "模型初始化失败")
            Result.failure(e)
        }
    }
    
    suspend fun generateText(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (!isModelLoaded) return@withContext Result.failure(Exception("模型未加载"))
            
            val response = inferenceSession?.generateResponse(buildPrompt(prompt))
                ?: return@withContext Result.failure(Exception("推理会话为空"))
            
            Result.success(response.trim())
        } catch (e: Exception) {
            Timber.e(e, "文本生成失败")
            Result.failure(e)
        }
    }
    
    suspend fun analyzeGameScreen(bitmap: Bitmap): Result<GameScreenAnalysis> = withContext(Dispatchers.IO) {
        try {
            if (!isModelLoaded) return@withContext Result.failure(Exception("模型未加载"))
            
            val prompt = """
分析这张《我的花园世界》游戏截图，返回JSON格式：
{
    "screenType": "主界面/种植/收获/任务/商店/其他",
    "resources": {"gold": 0, "diamond": 0, "energy": 0},
    "pendingTasks": ["浇水", "施肥", "收获"]
}
只返回JSON。
            """.trimIndent()
            
            val response = inferenceSession?.generateResponse(prompt)
                ?: return@withContext Result.failure(Exception("推理失败"))
            
            Result.success(parseGameAnalysis(response))
        } catch (e: Exception) {
            Timber.e(e, "画面分析失败")
            Result.failure(e)
        }
    }
    
    private fun buildPrompt(userPrompt: String): String = """
你是《我的花园世界》游戏的AI助手。
游戏功能：种植花卉、浇水施肥、收获、任务、商店。
请用中文回复，简洁明了。
User: $userPrompt
Assistant:
    """.trimIndent()
    
    private fun parseGameAnalysis(response: String): GameScreenAnalysis {
        return try {
            GameScreenAnalysis(
                screenType = extractJsonValue(response, "screenType") ?: "未知",
                interactiveElements = emptyList(),
                resources = GameResources(
                    gold = extractJsonValue(response, "gold")?.toIntOrNull() ?: 0,
                    diamond = extractJsonValue(response, "diamond")?.toIntOrNull() ?: 0,
                    energy = extractJsonValue(response, "energy")?.toIntOrNull() ?: 0
                ),
                pendingTasks = emptyList(),
                notifications = emptyList()
            )
        } catch (e: Exception) {
            GameScreenAnalysis("未知", emptyList(), GameResources(), emptyList(), emptyList())
        }
    }
    
    private fun extractJsonValue(json: String, key: String): String? {
        val regex = """"$key":\s*"?([^",}]+)""?".toRegex()
        return regex.find(json)?.groupValues?.get(1)?.trim()
    }
    
    fun release() {
        inferenceSession?.close()
        inferenceSession = null
        isModelLoaded = false
    }
}

data class GameScreenAnalysis(
    val screenType: String,
    val interactiveElements: List<InteractiveElement>,
    val resources: GameResources,
    val pendingTasks: List<String>,
    val notifications: List<String>
)

data class InteractiveElement(val name: String, val type: String, val bounds: List<Int>)
data class GameResources(val gold: Int = 0, val diamond: Int = 0, val energy: Int = 0)
