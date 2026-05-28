package com.gardenworld.auto.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 模型下载服务
 * 
 * 安全加固：
 * 1. 限制重定向次数，防止重定向循环攻击
 * 2. 验证重定向目标域名白名单，防止SSRF/恶意文件注入
 */
class ModelDownloadService : Service() {

    enum class ModelOption(
        val id: String, val name: String, val fileName: String,
        val downloadUrl: String, val fileSize: String, val minSizeBytes: Long
    ) {
        GEMMA_270M("gemma_270m", "Gemma 3 270M Q8", "gemma-3-270m-it-q8.task",
            "https://huggingface.co/litert-community/gemma-3-270m-it-q8/resolve/main/gemma-3-270m-it-q8.task",
            "~125MB", 80_000_000L),
        GEMMA_E2B("gemma_e2b", "Gemma 4 E2B", "gemma-4-e2b-it.task",
            "https://huggingface.co/google/gemma-4-e2b-it-litert/resolve/main/gemma-4-e2b-it-litert.task",
            "~2.5GB", 2_000_000_000L),
        GEMMA_E4B("gemma_e4b", "Gemma 4 E4B", "gemma-4-e4b-it.task",
            "https://huggingface.co/google/gemma-4-e4b-it-litert/resolve/main/gemma-4-e4b-it-litert.task",
            "~5GB", 4_000_000_000L)
    }

    companion object {
        // 安全修复：允许的重定向目标域名白名单
        private val ALLOWED_HOSTS = setOf(
            "huggingface.co",
            "www.huggingface.co",
            "cdn-lfs.huggingface.co",
            "hf.co",
            "cdn.hf.co",
            "storage.googleapis.com"  // Google Cloud Storage
        )
        
        // 安全修复：最大重定向次数
        private const val MAX_REDIRECTS = 5
        
        // 安全修复：连接超时（毫秒）
        private const val CONNECT_TIMEOUT = 30000
        private const val READ_TIMEOUT = 60000
    }

    private val binder = LocalBinder()
    private var downloadThread: Thread? = null
    private var isDownloading = false
    private var shouldCancel = false

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState

    private val _selectedModel = MutableStateFlow(ModelOption.GEMMA_270M)
    val selectedModel: StateFlow<ModelOption> = _selectedModel

    inner class LocalBinder : Binder() { 
        fun getService(): ModelDownloadService = this@ModelDownloadService 
    }

    override fun onCreate() {
        super.onCreate()
        checkExistingModels()
        Timber.d("ModelDownloadService created")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun checkExistingModels() {
        for (model in ModelOption.values()) {
            val file = File(filesDir, model.fileName)
            if (file.exists() && file.length() >= model.minSizeBytes) {
                _selectedModel.value = model
                _downloadState.value = DownloadState.Completed(model)
                return
            }
        }
    }

    fun selectModel(model: ModelOption) {
        _selectedModel.value = model
        val file = File(filesDir, model.fileName)
        if (file.exists() && file.length() >= model.minSizeBytes) {
            _downloadState.value = DownloadState.Completed(model)
        } else {
            _downloadState.value = DownloadState.Idle
        }
    }

    fun startDownload(model: ModelOption) {
        if (isDownloading) return
        shouldCancel = false
        isDownloading = true
        _downloadState.value = DownloadState.Downloading(0, model)

        downloadThread = Thread {
            downloadModel(model)
        }.apply { name = "ModelDownload"; start() }

        Timber.d("Start downloading: ${model.name}")
    }

    /**
     * 安全修复：验证URL是否在白名单中
     */
    private fun isUrlAllowed(urlString: String): Boolean {
        return try {
            val url = URL(urlString)
            val host = url.host.lowercase()
            ALLOWED_HOSTS.any { allowed -> host == allowed || host.endsWith(".$allowed") }
        } catch (e: Exception) {
            Timber.e("URL解析失败: $urlString")
            false
        }
    }

    private fun downloadModel(model: ModelOption) {
        var connection: HttpURLConnection? = null
        val targetFile = File(filesDir, model.fileName)
        val tempFile = File(filesDir, "${model.fileName}.tmp")

        try {
            // 安全修复：验证初始URL
            if (!isUrlAllowed(model.downloadUrl)) {
                throw SecurityException("初始URL不在白名单中: ${model.downloadUrl}")
            }
            
            var downloadedBytes = if (tempFile.exists()) tempFile.length() else 0L
            var currentUrl = model.downloadUrl
            var redirectCount = 0
            
            // 安全修复：限制重定向次数
            while (redirectCount < MAX_REDIRECTS) {
                val url = URL(currentUrl)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT
                    readTimeout = READ_TIMEOUT
                    setRequestProperty("User-Agent", "GardenWorldAuto/1.0")
                    instanceFollowRedirects = false  // 手动处理重定向
                    if (downloadedBytes > 0) {
                        setRequestProperty("Range", "bytes=$downloadedBytes-")
                    }
                }

                val responseCode = connection.responseCode
                
                // 处理重定向
                if (responseCode in 300..399) {
                    val newUrl = connection.getHeaderField("Location") 
                        ?: throw Exception("重定向缺少Location头")
                    
                    // 安全修复：验证重定向目标
                    if (!isUrlAllowed(newUrl)) {
                        connection.disconnect()
                        throw SecurityException("重定向到不受信任的域名: $newUrl")
                    }
                    
                    currentUrl = newUrl
                    redirectCount++
                    connection.disconnect()
                    Timber.d("重定向 $redirectCount: $currentUrl")
                    continue
                }
                
                // 非重定向状态码，跳出循环
                break
            }
            
            // 安全修复：检查是否超过最大重定向次数
            if (redirectCount >= MAX_REDIRECTS) {
                throw SecurityException("重定向次数超过最大值: $MAX_REDIRECTS")
            }

            // 获取最终响应
            val finalResponseCode = connection?.responseCode ?: -1
            val totalSize: Long = when (finalResponseCode) {
                200 -> {
                    downloadedBytes = 0
                    connection.contentLengthLong
                }
                206 -> {
                    val contentRange = connection.getHeaderField("Content-Range")
                    contentRange?.substringAfter("/")?.toLongOrNull() 
                        ?: (connection.contentLengthLong + downloadedBytes)
                }
                else -> throw Exception("HTTP错误: $finalResponseCode")
            }

            Timber.d("下载: 已有${downloadedBytes/1024/1024}MB, 总计${totalSize/1024/1024}MB")

            val inputStream = connection.inputStream
            val outputStream = FileOutputStream(tempFile, downloadedBytes > 0)
            val buffer = ByteArray(8192)
            var bytesRead: Int

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                if (shouldCancel) {
                    outputStream.close()
                    inputStream.close()
                    _downloadState.value = DownloadState.Idle
                    return
                }
                outputStream.write(buffer, 0, bytesRead)
                downloadedBytes += bytesRead
                if (totalSize > 0) {
                    val progress = ((downloadedBytes * 100) / totalSize).toInt()
                    _downloadState.value = DownloadState.Downloading(progress, model)
                }
            }

            outputStream.close()
            inputStream.close()
            connection.disconnect()
            
            // 验证下载完整性
            if (tempFile.length() < model.minSizeBytes) {
                throw Exception("下载文件不完整: ${tempFile.length()} < ${model.minSizeBytes}")
            }
            
            // 移动到最终位置
            if (tempFile.renameTo(targetFile)) {
                _downloadState.value = DownloadState.Completed(model)
                Timber.d("模型下载完成: ${targetFile.length()/1024/1024}MB")
            } else {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
                _downloadState.value = DownloadState.Completed(model)
            }

        } catch (e: SecurityException) {
            // 安全异常单独处理
            Timber.e(e, "安全验证失败")
            _downloadState.value = DownloadState.Error(model, "安全验证失败: ${e.message}")
        } catch (e: Exception) {
            Timber.e(e, "模型下载失败")
            _downloadState.value = DownloadState.Error(model, "下载失败: ${e.message}")
        } finally {
            connection?.disconnect()
            isDownloading = false
        }
    }

    fun cancelDownload() {
        shouldCancel = true
        downloadThread?.join(3000)
        isDownloading = false
        _downloadState.value = DownloadState.Idle
    }

    fun isModelDownloaded(model: ModelOption = selectedModel.value): Boolean {
        val file = File(filesDir, model.fileName)
        return file.exists() && file.length() >= model.minSizeBytes
    }

    fun getModelPath(model: ModelOption = selectedModel.value): String = 
        File(filesDir, model.fileName).absolutePath

    override fun onDestroy() {
        super.onDestroy()
        cancelDownload()
    }
}

sealed class DownloadState {
    object Idle : DownloadState()
    data class Downloading(val progress: Int, val model: ModelDownloadService.ModelOption? = null) : DownloadState()
    data class Completed(val model: ModelDownloadService.ModelOption? = null) : DownloadState()
    data class Error(val model: ModelDownloadService.ModelOption? = null, val message: String = "未知错误") : DownloadState()
}
