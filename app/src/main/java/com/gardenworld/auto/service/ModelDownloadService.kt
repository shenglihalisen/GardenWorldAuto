package com.gardenworld.auto.service

import android.app.DownloadManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.io.File

/**
 * 模型下载服务
 * 用于后台下载Gemma大模型文件
 */
class ModelDownloadService : Service() {
    
    companion object {
        const val MODEL_URL = "https://storage.googleapis.com/ai-edge-models/gemma-4-e4b-it.task"
        const val MODEL_FILENAME = "gemma-4-e4b-it.task"
    }
    
    private val binder = LocalBinder()
    private var downloadId: Long = -1
    private lateinit var downloadManager: DownloadManager
    
    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState
    
    private val _downloadProgress = MutableStateFlow(0)
    val downloadProgress: StateFlow<Int> = _downloadProgress
    
    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) ?: return
            if (id == downloadId) {
                handleDownloadComplete()
            }
        }
    }
    
    inner class LocalBinder : Binder() {
        fun getService(): ModelDownloadService = this@ModelDownloadService
    }
    
    override fun onCreate() {
        super.onCreate()
        downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        registerReceiver(downloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        Timber.d("ModelDownloadService created")
    }
    
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
    
    /**
     * 开始下载模型
     */
    fun startDownload(): Boolean {
        // 检查是否已下载
        val modelFile = File(filesDir, MODEL_FILENAME)
        if (modelFile.exists() && modelFile.length() > 3_000_000_000L) {
            _downloadState.value = DownloadState.Completed
            return true
        }
        
        // 创建下载请求
        val request = DownloadManager.Request(Uri.parse(MODEL_URL))
            .setTitle("下载Gemma模型")
            .setDescription("正在下载AI模型文件，请保持网络连接...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(this, null, MODEL_FILENAME)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
        
        downloadId = downloadManager.enqueue(request)
        _downloadState.value = DownloadState.Downloading(0)
        
        // 启动进度监听
        startProgressMonitoring()
        
        Timber.d("Model download started, id: $downloadId")
        return true
    }
    
    /**
     * 监听下载进度
     */
    private fun startProgressMonitoring() {
        Thread {
            while (_downloadState.value is DownloadState.Downloading) {
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = downloadManager.query(query)
                
                if (cursor.moveToFirst()) {
                    val bytesDownloaded = cursor.getLong(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    )
                    val bytesTotal = cursor.getLong(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    )
                    
                    if (bytesTotal > 0) {
                        val progress = ((bytesDownloaded * 100) / bytesTotal).toInt()
                        _downloadProgress.value = progress
                        _downloadState.value = DownloadState.Downloading(progress)
                    }
                }
                cursor.close()
                
                Thread.sleep(1000)
            }
        }.start()
    }
    
    /**
     * 处理下载完成
     */
    private fun handleDownloadComplete() {
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor = downloadManager.query(query)
        
        if (cursor.moveToFirst()) {
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    // 移动文件到内部存储
                    val downloadedFile = File(getExternalFilesDir(null), MODEL_FILENAME)
                    val targetFile = File(filesDir, MODEL_FILENAME)
                    
                    if (downloadedFile.renameTo(targetFile)) {
                        _downloadState.value = DownloadState.Completed
                        Timber.d("Model download completed")
                    } else {
                        _downloadState.value = DownloadState.Error("文件移动失败")
                    }
                }
                DownloadManager.STATUS_FAILED -> {
                    val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    _downloadState.value = DownloadState.Error("下载失败: $reason")
                    Timber.e("Model download failed: $reason")
                }
            }
        }
        cursor.close()
    }
    
    /**
     * 取消下载
     */
    fun cancelDownload() {
        if (downloadId != -1L) {
            downloadManager.remove(downloadId)
            downloadId = -1
            _downloadState.value = DownloadState.Idle
            Timber.d("Model download cancelled")
        }
    }
    
    /**
     * 检查模型是否已下载
     */
    fun isModelDownloaded(): Boolean {
        val modelFile = File(filesDir, MODEL_FILENAME)
        return modelFile.exists() && modelFile.length() > 3_000_000_000L
    }
    
    /**
     * 获取模型文件路径
     */
    fun getModelPath(): String {
        return File(filesDir, MODEL_FILENAME).absolutePath
    }
    
    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(downloadReceiver)
        Timber.d("ModelDownloadService destroyed")
    }
}

/**
 * 下载状态
 */
sealed class DownloadState {
    object Idle : DownloadState()
    data class Downloading(val progress: Int) : DownloadState()
    object Completed : DownloadState()
    data class Error(val message: String) : DownloadState()
}
