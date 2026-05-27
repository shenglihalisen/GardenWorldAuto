package com.gardenworld.auto.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import timber.log.Timber

/**
 * 无障碍服务 - 用于自动化操作游戏
 * 通过AccessibilityService模拟点击、滑动等操作
 */
class GardenAccessibilityService : AccessibilityService() {
    
    companion object {
        const val TAG = "GardenAccessibility"
        const val PACKAGE_NAME = "cn.lbwdhysj.gf"
        
        @Volatile
        var instance: GardenAccessibilityService? = null
        
        fun isRunning(): Boolean = instance != null
    }
    
    private val handler = Handler(Looper.getMainLooper())
    private var isGameForeground = false
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Timber.d("Accessibility Service connected")
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val packageName = event.packageName?.toString()
                isGameForeground = packageName == PACKAGE_NAME
                Timber.d("Window state changed: $packageName, isGame: $isGameForeground")
            }
            
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (isGameForeground) {
                    // 游戏内容变化，可以在这里分析UI
                    analyzeGameUI()
                }
            }
        }
    }
    
    override fun onInterrupt() {
        Timber.d("Accessibility Service interrupted")
    }
    
    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        Timber.d("Accessibility Service unbound")
        return super.onUnbind(intent)
    }
    
    /**
     * 分析游戏UI
     */
    private fun analyzeGameUI() {
        try {
            val rootNode = rootInActiveWindow ?: return
            
            // 查找特定元素
            // 这里可以根据游戏UI结构查找按钮、文本等
            traverseNode(rootNode, 0)
            
            rootNode.recycle()
        } catch (e: Exception) {
            Timber.e(e, "Error analyzing game UI")
        }
    }
    
    /**
     * 遍历节点树
     */
    private fun traverseNode(node: AccessibilityNodeInfo, depth: Int) {
        try {
            // 获取节点信息
            val text = node.text?.toString()
            val contentDesc = node.contentDescription?.toString()
            val className = node.className?.toString()
            val bounds = Rect().apply { node.getBoundsInScreen(this) }
            
            // 记录感兴趣的节点
            if (!text.isNullOrBlank() || !contentDesc.isNullOrBlank()) {
                Timber.v("Node[$depth]: text=$text, desc=$contentDesc, class=$className, bounds=$bounds")
            }
            
            // 递归遍历子节点
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    traverseNode(child, depth + 1)
                    child.recycle()
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error traversing node")
        }
    }
    
    /**
     * 点击指定坐标
     */
    fun click(x: Float, y: Float, callback: ((Boolean) -> Unit)? = null) {
        if (!isGameForeground) {
            Timber.w("Game is not in foreground")
            callback?.invoke(false)
            return
        }
        
        val path = Path().apply {
            moveTo(x, y)
        }
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        
        val result = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Timber.d("Click completed at ($x, $y)")
                callback?.invoke(true)
            }
            
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Timber.w("Click cancelled at ($x, $y)")
                callback?.invoke(false)
            }
        }, null)
        
        if (!result) {
            Timber.e("Failed to dispatch click gesture")
            callback?.invoke(false)
        }
    }
    
    /**
     * 长按指定坐标
     */
    fun longClick(x: Float, y: Float, duration: Long = 800, callback: ((Boolean) -> Unit)? = null) {
        if (!isGameForeground) {
            callback?.invoke(false)
            return
        }
        
        val path = Path().apply {
            moveTo(x, y)
        }
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Timber.d("Long click completed at ($x, $y)")
                callback?.invoke(true)
            }
            
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Timber.w("Long click cancelled at ($x, $y)")
                callback?.invoke(false)
            }
        }, null)
    }
    
    /**
     * 滑动操作
     */
    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long = 300, callback: ((Boolean) -> Unit)? = null) {
        if (!isGameForeground) {
            callback?.invoke(false)
            return
        }
        
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Timber.d("Swipe completed from ($startX, $startY) to ($endX, $endY)")
                callback?.invoke(true)
            }
            
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Timber.w("Swipe cancelled")
                callback?.invoke(false)
            }
        }, null)
    }
    
    /**
     * 双击操作
     */
    fun doubleClick(x: Float, y: Float, callback: ((Boolean) -> Unit)? = null) {
        click(x, y) { success1 ->
            if (success1) {
                handler.postDelayed({
                    click(x, y, callback)
                }, 150)
            } else {
                callback?.invoke(false)
            }
        }
    }
    
    /**
     * 拖拽操作（用于移动物品）
     */
    fun drag(startX: Float, startY: Float, endX: Float, endY: Float, callback: ((Boolean) -> Unit)? = null) {
        // 拖拽等同于长按后移动
        longClick(startX, startY, 500) { success ->
            if (success) {
                swipe(startX, startY, endX, endY, 500, callback)
            } else {
                callback?.invoke(false)
            }
        }
    }
    
    /**
     * 查找并点击文本
     */
    fun findAndClickText(text: String, callback: ((Boolean) -> Unit)? = null) {
        try {
            val rootNode = rootInActiveWindow
            if (rootNode == null) {
                callback?.invoke(false)
                return
            }
            
            val nodes = rootNode.findAccessibilityNodeInfosByText(text)
            if (nodes.isNotEmpty()) {
                val node = nodes[0]
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                
                val centerX = (bounds.left + bounds.right) / 2f
                val centerY = (bounds.top + bounds.bottom) / 2f
                
                click(centerX, centerY, callback)
                
                for (n in nodes) {
                    n.recycle()
                }
            } else {
                Timber.w("Text not found: $text")
                callback?.invoke(false)
            }
            
            rootNode.recycle()
        } catch (e: Exception) {
            Timber.e(e, "Error finding and clicking text")
            callback?.invoke(false)
        }
    }
    
    /**
     * 查找并点击内容描述
     */
    fun findAndClickByDescription(description: String, callback: ((Boolean) -> Unit)? = null) {
        try {
            val rootNode = rootInActiveWindow
            if (rootNode == null) {
                callback?.invoke(false)
                return
            }
            
            val found = findNodeByDescription(rootNode, description)
            if (found != null) {
                val bounds = Rect()
                found.getBoundsInScreen(bounds)
                
                val centerX = (bounds.left + bounds.right) / 2f
                val centerY = (bounds.top + bounds.bottom) / 2f
                
                click(centerX, centerY, callback)
                found.recycle()
            } else {
                Timber.w("Description not found: $description")
                callback?.invoke(false)
            }
            
            rootNode.recycle()
        } catch (e: Exception) {
            Timber.e(e, "Error finding and clicking by description")
            callback?.invoke(false)
        }
    }
    
    /**
     * 递归查找节点
     */
    private fun findNodeByDescription(node: AccessibilityNodeInfo, description: String): AccessibilityNodeInfo? {
        val nodeDesc = node.contentDescription?.toString()
        if (nodeDesc == description) {
            return node
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByDescription(child, description)
            if (found != null) {
                return found
            }
            child.recycle()
        }
        
        return null
    }
    
    /**
     * 执行一系列操作（链式调用）
     */
    fun performActions(actions: List<GameAction>, onComplete: ((Boolean) -> Unit)? = null) {
        var index = 0
        
        fun executeNext() {
            if (index >= actions.size) {
                onComplete?.invoke(true)
                return
            }
            
            val action = actions[index++]
            executeAction(action) { success ->
                if (success) {
                    handler.postDelayed({ executeNext() }, action.delayAfter)
                } else {
                    Timber.e("Action failed: $action")
                    onComplete?.invoke(false)
                }
            }
        }
        
        executeNext()
    }
    
    /**
     * 执行单个操作
     */
    private fun executeAction(action: GameAction, callback: ((Boolean) -> Unit)? = null) {
        when (action.type) {
            ActionType.CLICK -> {
                val (x, y) = action.coordinates ?: return
                click(x, y, callback)
            }
            ActionType.LONG_CLICK -> {
                val (x, y) = action.coordinates ?: return
                longClick(x, y, action.duration, callback)
            }
            ActionType.SWIPE -> {
                val (startX, startY, endX, endY) = action.swipeCoordinates ?: return
                swipe(startX, startY, endX, endY, action.duration, callback)
            }
            ActionType.DOUBLE_CLICK -> {
                val (x, y) = action.coordinates ?: return
                doubleClick(x, y, callback)
            }
            ActionType.DRAG -> {
                val (startX, startY, endX, endY) = action.swipeCoordinates ?: return
                drag(startX, startY, endX, endY, callback)
            }
            ActionType.CLICK_TEXT -> {
                findAndClickText(action.text ?: "", callback)
            }
            ActionType.CLICK_DESC -> {
                findAndClickByDescription(action.description ?: "", callback)
            }
            ActionType.WAIT -> {
                handler.postDelayed({ callback?.invoke(true) }, action.duration)
            }
        }
    }
}

/**
 * 游戏操作类型
 */
enum class ActionType {
    CLICK,          // 点击
    LONG_CLICK,     // 长按
    SWIPE,          // 滑动
    DOUBLE_CLICK,   // 双击
    DRAG,           // 拖拽
    CLICK_TEXT,     // 点击文本
    CLICK_DESC,     // 点击描述
    WAIT            // 等待
}

/**
 * 游戏操作
 */
data class GameAction(
    val type: ActionType,
    val coordinates: Pair<Float, Float>? = null,  // x, y
    val swipeCoordinates: SwipeCoordinates? = null,  // startX, startY, endX, endY
    val text: String? = null,
    val description: String? = null,
    val duration: Long = 300,
    val delayAfter: Long = 500
)

data class SwipeCoordinates(
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float
)
