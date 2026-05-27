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

class GardenAccessibilityService : AccessibilityService() {
    
    companion object {
        const val PACKAGE_NAME = "cn.lbwdhysj.gf"
        @Volatile var instance: GardenAccessibilityService? = null
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
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString()
            isGameForeground = packageName == PACKAGE_NAME
        }
    }
    
    override fun onInterrupt() { Timber.d("Accessibility Service interrupted") }
    
    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }
    
    fun click(x: Float, y: Float, callback: ((Boolean) -> Unit)? = null) {
        if (!isGameForeground) { callback?.invoke(false); return }
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 100)).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { callback?.invoke(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { callback?.invoke(false) }
        }, null)
    }
    
    fun longClick(x: Float, y: Float, duration: Long = 800, callback: ((Boolean) -> Unit)? = null) {
        if (!isGameForeground) { callback?.invoke(false); return }
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { callback?.invoke(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { callback?.invoke(false) }
        }, null)
    }
    
    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long = 300, callback: ((Boolean) -> Unit)? = null) {
        if (!isGameForeground) { callback?.invoke(false); return }
        val path = Path().apply { moveTo(startX, startY); lineTo(endX, endY) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { callback?.invoke(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { callback?.invoke(false) }
        }, null)
    }
    
    fun performActions(actions: List<GameAction>, onComplete: ((Boolean) -> Unit)? = null) {
        var index = 0
        fun executeNext() {
            if (index >= actions.size) { onComplete?.invoke(true); return }
            val action = actions[index++]
            executeAction(action) { success ->
                if (success) handler.postDelayed({ executeNext() }, action.delayAfter)
                else onComplete?.invoke(false)
            }
        }
        executeNext()
    }
    
    private fun executeAction(action: GameAction, callback: ((Boolean) -> Unit)? = null) {
        when (action.type) {
            ActionType.CLICK -> { val (x, y) = action.coordinates ?: return; click(x, y, callback) }
            ActionType.LONG_CLICK -> { val (x, y) = action.coordinates ?: return; longClick(x, y, action.duration, callback) }
            ActionType.SWIPE -> { val (sX, sY, eX, eY) = action.swipeCoordinates ?: return; swipe(sX, sY, eX, eY, action.duration, callback) }
            ActionType.WAIT -> handler.postDelayed({ callback?.invoke(true) }, action.duration)
            else -> callback?.invoke(false)
        }
    }
}

enum class ActionType { CLICK, LONG_CLICK, SWIPE, DOUBLE_CLICK, DRAG, CLICK_TEXT, CLICK_DESC, WAIT }

data class GameAction(
    val type: ActionType,
    val coordinates: Pair<Float, Float>? = null,
    val swipeCoordinates: com.gardenworld.auto.service.SwipeCoordinates? = null,
    val text: String? = null,
    val description: String? = null,
    val duration: Long = 300,
    val delayAfter: Long = 500
)

data class SwipeCoordinates(val startX: Float, val startY: Float, val endX: Float, val endY: Float)
