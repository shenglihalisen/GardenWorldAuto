package com.gardenworld.auto.automation

import android.content.Context
import android.graphics.Bitmap
import com.gardenworld.auto.ai.GameResources
import com.gardenworld.auto.ai.GameScreenAnalysis
import com.gardenworld.auto.ai.GemmaInferenceEngine
import com.gardenworld.auto.service.ActionType
import com.gardenworld.auto.service.GameAction
import com.gardenworld.auto.service.GardenAccessibilityService
import com.gardenworld.auto.service.SwipeCoordinates
import com.gardenworld.auto.vision.ScreenCaptureManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber

/**
 * 游戏自动化引擎
 * 整合AI识别和自动化操作
 */
class GameAutomationEngine(private val context: Context) {
    
    companion object {
        const val TAG = "GameAutomation"
        const val DEFAULT_CAPTURE_INTERVAL = 3000L // 3秒
        const val MAX_RETRY_COUNT = 3
    }
    
    // 依赖组件
    private val gemmaEngine = GemmaInferenceEngine(context)
    private val screenCapture = ScreenCaptureManager(context)
    
    // 状态管理
    private val _automationState = MutableStateFlow(AutomationState.IDLE)
    val automationState: StateFlow<AutomationState> = _automationState
    
    private val _currentTask = MutableStateFlow<String>("")
    val currentTask: StateFlow<String> = _currentTask
    
    private val _logMessages = MutableStateFlow<List<String>>(emptyList())
    val logMessages: StateFlow<List<String>> = _logMessages
    
    // 协程作用域
    private var automationJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // 游戏状态
    private var lastScreenAnalysis: GameScreenAnalysis? = null
    private var consecutiveErrors = 0
    
    /**
     * 初始化引擎
     */
    suspend fun initialize(): Result<Unit> {
        log("正在初始化自动化引擎...")
        
        return try {
            // 初始化Gemma模型
            if (!gemmaEngine.isModelAvailable()) {
                log("⚠️ Gemma模型未找到，请先下载模型")
                return Result.failure(Exception("Model not available"))
            }
            
            gemmaEngine.initializeModel().onSuccess {
                log("✅ Gemma模型初始化成功")
            }.onFailure {
                log("❌ Gemma模型初始化失败: ${it.message}")
                return Result.failure(it)
            }
            
            // 检查无障碍服务
            if (!GardenAccessibilityService.isRunning()) {
                log("⚠️ 无障碍服务未启动，请在设置中开启")
            } else {
                log("✅ 无障碍服务已启动")
            }
            
            log("✅ 自动化引擎初始化完成")
            Result.success(Unit)
            
        } catch (e: Exception) {
            log("❌ 初始化失败: ${e.message}")
            Result.failure(e)
        }
    }
    
    /**
     * 启动自动化
     */
    fun startAutomation(config: AutomationConfig = AutomationConfig()) {
        if (automationJob?.isActive == true) {
            log("自动化已在运行中")
            return
        }
        
        if (!screenCapture.isInitialized()) {
            log("❌ 屏幕捕获未初始化")
            return
        }
        
        _automationState.value = AutomationState.RUNNING
        log("🚀 启动自动化任务")
        
        automationJob = scope.launch {
            runAutomationLoop(config)
        }
    }
    
    /**
     * 停止自动化
     */
    fun stopAutomation() {
        automationJob?.cancel()
        automationJob = null
        _automationState.value = AutomationState.IDLE
        _currentTask.value = ""
        log("🛑 自动化已停止")
    }
    
    /**
     * 暂停自动化
     */
    fun pauseAutomation() {
        _automationState.value = AutomationState.PAUSED
        log("⏸️ 自动化已暂停")
    }
    
    /**
     * 恢复自动化
     */
    fun resumeAutomation() {
        if (_automationState.value == AutomationState.PAUSED) {
            _automationState.value = AutomationState.RUNNING
            log("▶️ 自动化已恢复")
        }
    }
    
    /**
     * 自动化主循环
     */
    private suspend fun runAutomationLoop(config: AutomationConfig) {
        while (isActive && _automationState.value == AutomationState.RUNNING) {
            try {
                // 捕获屏幕
                _currentTask.value = "📸 捕获屏幕..."
                val screenResult = screenCapture.captureScreen()
                
                if (screenResult.isFailure) {
                    handleError("屏幕捕获失败")
                    delay(config.errorRetryDelay)
                    continue
                }
                
                val bitmap = screenResult.getOrThrow()
                
                // AI分析
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
                
                // 执行决策
                _currentTask.value = "🎮 执行操作..."
                executeDecision(analysis, config)
                
                bitmap.recycle()
                
                // 等待下一次循环
                delay(config.captureInterval)
                
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleError("自动化循环异常: ${e.message}")
                delay(config.errorRetryDelay)
            }
        }
    }
    
    /**
     * 执行决策
     */
    private suspend fun executeDecision(analysis: GameScreenAnalysis, config: AutomationConfig) {
        val accessibilityService = GardenAccessibilityService.instance
        if (accessibilityService == null) {
            log("❌ 无障碍服务不可用")
            return
        }
        
        // 根据游戏状态执行不同策略
        when (analysis.screenType) {
            "主界面" -> handleMainScreen(analysis, accessibilityService, config)
            "种植" -> handlePlanting(analysis, accessibilityService, config)
            "收获" -> handleHarvesting(analysis, accessibilityService, config)
            "任务" -> handleTasks(analysis, accessibilityService, config)
            "商店" -> handleShop(analysis, accessibilityService, config)
            else -> handleUnknownScreen(analysis, accessibilityService)
        }
    }
    
    /**
     * 处理主界面
     */
    private suspend fun handleMainScreen(
        analysis: GameScreenAnalysis,
        service: GardenAccessibilityService,
        config: AutomationConfig
    ) {
        log("🏠 当前在主界面")
        
        // 优先处理待办任务
        when {
            analysis.pendingTasks.contains("收获") && config.autoHarvest -> {
                log("🌻 执行收获任务")
                // 查找并点击可收获的植物
                harvestAllPlants(service)
            }
            analysis.pendingTasks.contains("浇水") && config.autoWater -> {
                log("💧 执行浇水任务")
                waterAllPlants(service)
            }
            analysis.pendingTasks.contains("施肥") && config.autoFertilize -> {
                log("🧪 执行施肥任务")
                fertilizeAllPlants(service)
            }
            config.autoCollectTasks -> {
                // 检查并领取任务奖励
                checkAndCollectTasks(service)
            }
        }
    }
    
    /**
     * 处理种植界面
     */
    private suspend fun handlePlanting(
        analysis: GameScreenAnalysis,
        service: GardenAccessibilityService,
        config: AutomationConfig
    ) {
        log("🌱 当前在种植界面")
        
        if (!config.autoPlant) return
        
        // 选择种子并种植
        val actions = listOf(
            GameAction(ActionType.CLICK, coordinates = 540f to 1200f, delayAfter = 800), // 选择种子
            GameAction(ActionType.CLICK, coordinates = 540f to 1600f, delayAfter = 500), // 确认种植
        )
        
        suspendCancellableCoroutine { continuation ->
            service.performActions(actions) { success ->
                if (success) {
                    log("✅ 种植完成")
                } else {
                    log("❌ 种植失败")
                }
                continuation.resume(Unit) {}
            }
        }
    }
    
    /**
     * 处理收获
     */
    private suspend fun handleHarvesting(
        analysis: GameScreenAnalysis,
        service: GardenAccessibilityService,
        config: AutomationConfig
    ) {
        log("🌾 当前在收获界面")
        harvestAllPlants(service)
    }
    
    /**
     * 处理任务
     */
    private suspend fun handleTasks(
        analysis: GameScreenAnalysis,
        service: GardenAccessibilityService,
        config: AutomationConfig
    ) {
        log("📋 当前在任务界面")
        
        if (config.autoCollectTasks) {
            // 点击领取所有可领取的奖励
            val actions = listOf(
                GameAction(ActionType.CLICK, coordinates = 800f to 600f, delayAfter = 500),
                GameAction(ActionType.CLICK, coordinates = 800f to 800f, delayAfter = 500),
                GameAction(ActionType.CLICK, coordinates = 800f to 1000f, delayAfter = 500),
            )
            
            suspendCancellableCoroutine { continuation ->
                service.performActions(actions) { success ->
                    log(if (success) "✅ 任务奖励领取完成" else "❌ 任务奖励领取失败")
                    continuation.resume(Unit) {}
                }
            }
        }
    }
    
    /**
     * 处理商店
     */
    private suspend fun handleShop(
        analysis: GameScreenAnalysis,
        service: GardenAccessibilityService,
        config: AutomationConfig
    ) {
        log("🏪 当前在商店界面")
        
        if (config.autoBuySeeds && analysis.resources.gold > 1000) {
            // 购买种子逻辑
            log("💰 购买种子")
        }
        
        // 返回主界面
        service.click(100f, 100f)
    }
    
    /**
     * 处理未知界面
     */
    private suspend fun handleUnknownScreen(
        analysis: GameScreenAnalysis,
        service: GardenAccessibilityService
    ) {
        log("❓ 未知界面类型: ${analysis.screenType}")
        
        // 尝试点击返回按钮或关闭弹窗
        service.click(100f, 100f)
        delay(500)
    }
    
    /**
     * 收获所有植物
     */
    private suspend fun harvestAllPlants(service: GardenAccessibilityService) {
        // 模拟点击多个植物位置
        val plantPositions = listOf(
            300f to 800f, 540f to 800f, 780f to 800f,
            300f to 1000f, 540f to 1000f, 780f to 1000f,
            300f to 1200f, 540f to 1200f, 780f to 1200f,
        )
        
        for ((x, y) in plantPositions) {
            service.click(x, y)
            delay(200)
        }
        
        log("🌻 收获操作完成")
    }
    
    /**
     * 浇水所有植物
     */
    private suspend fun waterAllPlants(service: GardenAccessibilityService) {
        // 先点击水壶工具
        service.click(900f, 1800f) // 假设水壶在右下角
        delay(500)
        
        // 然后点击需要浇水的植物
        val plantPositions = listOf(
            300f to 800f, 540f to 800f, 780f to 800f,
            300f to 1000f, 540f to 1000f, 780f to 1000f,
        )
        
        for ((x, y) in plantPositions) {
            service.click(x, y)
            delay(300)
        }
        
        log("💧 浇水操作完成")
    }
    
    /**
     * 施肥所有植物
     */
    private suspend fun fertilizeAllPlants(service: GardenAccessibilityService) {
        // 先点击肥料工具
        service.click(900f to 1700f) // 假设肥料在右下角
        delay(500)
        
        // 然后点击需要施肥的植物
        val plantPositions = listOf(
            300f to 800f, 540f to 800f, 780f to 800f,
        )
        
        for ((x, y) in plantPositions) {
            service.click(x, y)
            delay(300)
        }
        
        log("🧪 施肥操作完成")
    }
    
    /**
     * 检查并领取任务
     */
    private suspend fun checkAndCollectTasks(service: GardenAccessibilityService) {
        // 点击任务按钮
        service.click(900f to 200f) // 假设任务按钮在右上角
        delay(1000)
        
        // 领取奖励
        service.click(800f to 600f)
        delay(500)
        
        // 返回
        service.click(100f to 100f)
        
        log("📋 任务检查完成")
    }
    
    /**
     * 处理错误
     */
    private fun handleError(message: String) {
        consecutiveErrors++
        log("❌ $message (错误次数: $consecutiveErrors)")
        
        if (consecutiveErrors >= MAX_RETRY_COUNT) {
            log("⚠️ 连续错误次数过多，停止自动化")
            stopAutomation()
        }
    }
    
    /**
     * 添加日志
     */
    private fun log(message: String) {
        Timber.d(message)
        val currentLogs = _logMessages.value.toMutableList()
        currentLogs.add("[${System.currentTimeMillis()}] $message")
        
        // 限制日志数量
        if (currentLogs.size > 100) {
            currentLogs.removeAt(0)
        }
        
        _logMessages.value = currentLogs
    }
    
    /**
     * 获取屏幕捕获管理器
     */
    fun getScreenCaptureManager(): ScreenCaptureManager = screenCapture
    
    /**
     * 释放资源
     */
    fun release() {
        stopAutomation()
        scope.cancel()
        screenCapture.release()
        gemmaEngine.release()
    }
}

/**
 * 自动化配置
 */
data class AutomationConfig(
    val captureInterval: Long = 3000L,
    val errorRetryDelay: Long = 5000L,
    val autoHarvest: Boolean = true,
    val autoWater: Boolean = true,
    val autoFertilize: Boolean = true,
    val autoPlant: Boolean = true,
    val autoCollectTasks: Boolean = true,
    val autoBuySeeds: Boolean = false,
    val minGoldForShopping: Int = 5000
)

/**
 * 自动化状态
 */
enum class AutomationState {
    IDLE,       // 空闲
    RUNNING,    // 运行中
    PAUSED,     // 暂停
    ERROR       // 错误
}
