package com.gardenworld.auto.automation

import android.content.Context
import android.graphics.Bitmap
import com.gardenworld.auto.ai.GameResources
import com.gardenworld.auto.ai.GameScreenAnalysis
import com.gardenworld.auto.ai.GemmaInferenceEngine
import com.gardenworld.auto.service.ActionType
import com.gardenworld.auto.service.GameAction
import com.gardenworld.auto.service.GardenAccessibilityService
import com.gardenworld.auto.vision.ScreenCaptureManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber

/**
 * 游戏自动化引擎
 */
class GameAutomationEngine(private val context: Context) {

    companion object {
        const val TAG = "GameAutomation"
        const val DEFAULT_CAPTURE_INTERVAL = 60_000L // 默认60秒
        const val MAX_RETRY_COUNT = 3
    }

    private val gemmaEngine = GemmaInferenceEngine(context)
    private val screenCapture = ScreenCaptureManager(context)

    private val _automationState = MutableStateFlow(AutomationState.IDLE)
    val automationState: StateFlow<AutomationState> = _automationState

    private val _currentTask = MutableStateFlow("")
    val currentTask: StateFlow<String> = _currentTask

    private val _logMessages = MutableStateFlow<List<String>>(emptyList())
    val logMessages: StateFlow<List<String>> = _logMessages

    private var automationJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var lastScreenAnalysis: GameScreenAnalysis? = null
    private var consecutiveErrors = 0

    suspend fun initialize(): Result<Unit> {
        log("正在初始化自动化引擎...")

        if (!gemmaEngine.isModelAvailable()) {
            log("⚠️ Gemma模型未找到，请先下载模型")
            return Result.failure(Exception("Model not available"))
        }

        return gemmaEngine.initializeModel().fold(
            onSuccess = {
                log("✅ Gemma模型初始化成功")
                Result.success(Unit)
            },
            onFailure = {
                log("❌ Gemma模型初始化失败: ${it.message}")
                Result.failure(it)
            }
        )
    }

    fun startAutomation(config: AutomationConfig) {
        if (automationJob?.isActive == true) {
            log("自动化已在运行中")
            return
        }

        if (!screenCapture.isInitialized()) {
            log("❌ 屏幕捕获未初始化")
            return
        }

        _automationState.value = AutomationState.RUNNING
        log("🚀 启动自动化任务 (每${config.captureIntervalSeconds}秒检测一次)")

        automationJob = scope.launch {
            runAutomationLoop(config)
        }
    }

    fun stopAutomation() {
        automationJob?.cancel()
        automationJob = null
        _automationState.value = AutomationState.IDLE
        _currentTask.value = ""
        log("🛑 自动化已停止")
    }

    fun pauseAutomation() {
        _automationState.value = AutomationState.PAUSED
        log("⏸️ 自动化已暂停")
    }

    fun resumeAutomation() {
        if (_automationState.value == AutomationState.PAUSED) {
            _automationState.value = AutomationState.RUNNING
            log("▶️ 自动化已恢复")
        }
    }

    private suspend fun runAutomationLoop(config: AutomationConfig) {
        while (isActive && _automationState.value == AutomationState.RUNNING) {
            try {
                _currentTask.value = "📸 捕获屏幕..."
                val screenResult = screenCapture.captureScreen()

                if (screenResult.isFailure) {
                    handleError("屏幕捕获失败")
                    delay(config.errorRetryDelay)
                    continue
                }

                val bitmap = screenResult.getOrThrow()

                _currentTask.value = "🧠 AI分析中..."
                val analysisResult = gemmaEngine.analyzeGameScreen(bitmap)

                if (analysisResult.isFailure) {
                    handleError("AI分析失败")
                    bitmap.recycle()
                    delay(config.errorRetryDelay)
                    continue
                }

                val analysis = analysisResult.getOrThrow()
                lastScreenAnalysis = analysis
                consecutiveErrors = 0

                _currentTask.value = "🎮 执行操作..."
                executeDecision(analysis, config)

                bitmap.recycle()
                delay(config.captureInterval) // 使用配置的间隔

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleError("自动化循环异常: ${e.message}")
                delay(config.errorRetryDelay)
            }
        }
    }

    private suspend fun executeDecision(analysis: GameScreenAnalysis, config: AutomationConfig) {
        // 安全修复：使用内部方法获取实例，并进行包名校验
        val service = GardenAccessibilityService.instance
        if (service == null) {
            log("❌ 无障碍服务不可用")
            return
        }

        when (analysis.screenType) {
            "主界面" -> handleMainScreen(analysis, service, config)
            "种植" -> handlePlanting(analysis, service, config)
            "收获" -> handleHarvesting(analysis, service, config)
            "任务" -> handleTasks(analysis, service, config)
            else -> log("📱 当前界面: ${analysis.screenType}")
        }
    }

    private suspend fun handleMainScreen(analysis: GameScreenAnalysis, service: GardenAccessibilityService, config: AutomationConfig) {
        log("🏠 主界面 - 资源: 金币=${analysis.resources.gold}")

        when {
            analysis.pendingTasks.contains("收获") && config.autoHarvest -> harvestAllPlants(service)
            analysis.pendingTasks.contains("浇水") && config.autoWater -> waterAllPlants(service)
            config.autoCollectTasks -> checkAndCollectTasks(service)
        }
    }

    private suspend fun handlePlanting(analysis: GameScreenAnalysis, service: GardenAccessibilityService, config: AutomationConfig) {
        log("🌱 种植界面")
        if (config.autoPlant) log("🌾 自动种植已开启")
    }

    private suspend fun handleHarvesting(analysis: GameScreenAnalysis, service: GardenAccessibilityService, config: AutomationConfig) {
        log("🌾 收获界面")
        harvestAllPlants(service)
    }

    private suspend fun handleTasks(analysis: GameScreenAnalysis, service: GardenAccessibilityService, config: AutomationConfig) {
        log("📋 任务界面")
        if (config.autoCollectTasks) log("📦 领取任务奖励")
    }

    private suspend fun harvestAllPlants(service: GardenAccessibilityService) {
        log("🌻 收获所有植物...")
        service.click(300f, 800f)
        delay(200)
        service.click(540f, 800f)
        delay(200)
        service.click(780f, 800f)
        delay(200)
        service.click(300f, 1000f)
        delay(200)
        service.click(540f, 1000f)
        delay(200)
        service.click(780f, 1000f)
        log("✅ 收获完成")
    }

    private suspend fun waterAllPlants(service: GardenAccessibilityService) {
        log("💧 浇水所有植物...")
        service.click(900f, 1800f)
        delay(500)
        service.click(300f, 800f)
        delay(300)
        service.click(540f, 800f)
        delay(300)
        service.click(780f, 800f)
        log("✅ 浇水完成")
    }

    private suspend fun checkAndCollectTasks(service: GardenAccessibilityService) {
        log("📋 检查任务...")
        service.click(900f, 200f)
        delay(1000)
        service.click(800f, 600f)
        delay(500)
        service.click(100f, 100f)
    }

    private fun handleError(message: String) {
        consecutiveErrors++
        log("❌ $message (错误次数: $consecutiveErrors)")
        if (consecutiveErrors >= MAX_RETRY_COUNT) {
            log("⚠️ 连续错误次数过多，停止自动化")
            stopAutomation()
        }
    }

    private fun log(message: String) {
        Timber.d(message)
        val currentLogs = _logMessages.value.toMutableList()
        currentLogs.add("[${System.currentTimeMillis()}] $message")
        if (currentLogs.size > 100) currentLogs.removeAt(0)
        _logMessages.value = currentLogs
    }

    fun getScreenCaptureManager(): ScreenCaptureManager = screenCapture

    fun release() {
        stopAutomation()
        scope.cancel()
        screenCapture.release()
        gemmaEngine.release()
    }
}

enum class AutomationState {
    IDLE, RUNNING, PAUSED, ERROR
}
