package com.gardenworld.auto.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.gardenworld.auto.MainActivity
import com.gardenworld.auto.R
import timber.log.Timber

/**
 * 自动化前台服务
 * 保持APP在后台运行，确保自动化任务持续执行
 */
class AutoPlayService : Service() {
    
    companion object {
        const val CHANNEL_ID = "GardenWorldAutoChannel"
        const val CHANNEL_NAME = "花园世界自动化"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.gardenworld.auto.ACTION_START"
        const val ACTION_STOP = "com.gardenworld.auto.ACTION_STOP"
    }
    
    private val binder = LocalBinder()
    private var isRunning = false
    
    inner class LocalBinder : Binder() {
        fun getService(): AutoPlayService = this@AutoPlayService
    }
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Timber.d("AutoPlayService created")
    }
    
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startForegroundService()
            ACTION_STOP -> stopForegroundService()
        }
        return START_STICKY
    }
    
    /**
     * 启动前台服务
     */
    private fun startForegroundService() {
        if (isRunning) return
        
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        isRunning = true
        
        Timber.d("AutoPlayService started in foreground")
    }
    
    /**
     * 停止前台服务
     */
    private fun stopForegroundService() {
        if (!isRunning) return
        
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        isRunning = false
        
        Timber.d("AutoPlayService stopped")
    }
    
    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "花园世界自动化服务"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    /**
     * 创建通知
     */
    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, AutoPlayService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("花园世界自动化")
            .setContentText("自动化服务运行中...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "停止", stopIntent)
            .build()
    }
    
    /**
     * 更新通知内容
     */
    fun updateNotification(content: String) {
        if (!isRunning) return
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("花园世界自动化")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
        
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    /**
     * 检查服务是否运行
     */
    fun isServiceRunning(): Boolean = isRunning
    
    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        Timber.d("AutoPlayService destroyed")
    }
}
