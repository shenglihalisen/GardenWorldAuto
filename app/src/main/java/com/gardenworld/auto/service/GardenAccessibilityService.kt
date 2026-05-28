package com.gardenworld.auto.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import timber.log.Timber

/**
 * 无障碍服务 - 用于自动化操作游戏
 * 
 * 安全加固：
 * 1. instance 改为 internal，仅同模块可访问
 * 2. 所有操作添加调用者包名校验，防止外部APP滥用
 */
class GardenAccessibilityService : AccessibilityService() {
    
    companion object {
        const val PACKAGE_NAME = "cn.lbwdhysj.gf"
        
        // 安全修复：改为 internal，仅同模块可访问
        @Volatile
        internal var instance: GardenAccessibilityService? = null
        
        fun isRunning(): Boolean = instance != null
        
        // 安全修复：添加获取方法，内部进行校验
        internal fun getInstanceSafe(callerPackage: String?): GardenAccessibilityService? {
            if (callerPackage != BuildConfig.APPLICATION_ID) {
                Timber.w("拒绝外部APP访问无障碍服务: $callerPackage")
                return null
            }
            return instance
        }
    }
    
    private val handler = Handler(Looper.getMainLooper())
    private var isGameForeground = false
    
    // 本地Binder，仅允许同进程绑定
    inner class LocalBinder : Binder() {
        fun getService(): GardenAccessibilityService = this@GardenAccessibilityService
    }
    
    override fun onBind(intent: Intent?): IBinder {
        // 安全修复：验证绑定调用者
        val callerPackage = callingPackage
        if (callerPackage != packageName) {
            Timber.e("拒绝外部APP绑定: $callerPackage")
            throw SecurityException("无权绑定此服务")
        }
        return LocalBinder()
    }
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Timber.d("Accessibility Service connected")
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString()
            isGameForeground = packageName == PACKAGE_NAME
            Timber.d("Window changed: $packageName, isGame: $isGameForeground")
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
     * 安全修复：验证调用者身份
     */
    private fun verifyCaller(): Boolean {
        // 获取调用者UID，验证是否为同应用
        val callerUid = Binder.getCallingUid()
        val myUid = android.os.Process.myUid()
        if (callerUid != myUid) {
            Timber.w("拒绝跨UID调用: caller=$callerUid, mine=$myUid")
            return false
        }
        return true
    }
    
    /**
     * 点击指定坐标
     * 安全修复：添加调用者验证和游戏前台检查
     */
    fun click(x: Float, y: Float, callback: ((Boolean) -> Unit)? = null) {
        // 验证调用者
        if (!verifyCaller()) {
            callback?.invoke(false)
            return
        }
        
        // 验证游戏是否在前台
        if (!isGameForeground) {
            Timber.w("游戏不在前台，拒绝操作")
            callback?.invoke(false)
            return
        }
        
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        
        val result = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Timber.d("点击完成: ($x, $y)")
                callback?.invoke(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Timber.w("点击取消: ($x, $y)")
                callback?.invoke(false)
            }
        }, null)
        
        if (!result) {
            Timber.e("手势分发失败")
            callback?.invoke(false)
        }
    }
    
    /**
     * 长按指定坐标
     */
    fun longClick(x: Float, y: Float, duration: Long = 800, callback: ((Boolean) -> Unit)? = null) {
        if (!verifyCaller()) { callback?.invoke(false); return }
        if (!isGameForeground) { callback?.invoke(false); return }
        
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { callback?.invoke(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { callback?.invoke(false) }
        }, null)
    }
    
    /**
     * 滑动操作
     */
    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long = 300, callback: ((Boolean) -> Unit)? = null) {
        if (!verifyCaller()) { callback?.invoke(false); return }
        if (!isGameForeground) { callback?.invoke(false); return }
        
        val path = Path().apply { 
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { callback?.invoke(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { callback?.invoke(false) }
        }, null)
    }
    
    /**
     * 执行一系列操作
     */
    fun performActions(actions: List<GameAction>, onComplete: ((Boolean) -> Unit)? = null) {
        if (!verifyCaller()) { onComplete?.invoke(false); return }
        if (!isGameForeground) { onComplete?.invoke(false); return }
        
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
                    Timber.e("操作失败: $action")
                    onComplete?.invoke(false)
                }
            }
        }
        
        executeNext()
    }
    
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
                val (sX, sY, eX, eY) = action.swipeCoordinates ?: return
                swipe(sX, sY, eX, eY, action.duration, callback)
            }
            ActionType.WAIT -> {
                handler.postDelayed({ callback?.invoke(true) }, action.duration)
            }
            else -> callback?.invoke(false)
        }
    }
}

enum class ActionType { 
    CLICK, LONG_CLICK, SWIPE, DOUBLE_CLICK, DRAG, CLICK_TEXT, CLICK_DESC, WAIT 
}

data class GameAction(
    val type: ActionType,
    val coordinates: Pair<Float, Float>? = null,
    val swipeCoordinates: SwipeCoordinates? = null,
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
