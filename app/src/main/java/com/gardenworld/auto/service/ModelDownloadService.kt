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
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 模型下载管理器
 * 支持从HuggingFace下载Gemma模型，内置下载链接
 * 支持断点续传和进度回调
 */
class ModelDownloadService : Service() {

    /**
     * 可用模型列表
     */
    enum class ModelOption(
        val id: String,
        val name: String,
        val fileName: String,
        val downloadUrl: String,
        val fileSize: String,
        val minSizeBytes: Long
    ) {
        GEMMA_270M(
            id = "gemma_270m",
            name = "Gemma 3 270M Q8",
            fileName = "gemma-3-270m-it-q8.task",
            downloadUrl = "https://huggingface.co/litert-community/gemma-3-270m-it-q8/resolve/main/gemma-3-270m-it-q8.task",
            fileSize = "~125MB",
            minSizeBytes = 80_000_000L
        ),
        GEMMA_E2B(
            id = "gemma_e2b",
            name = "Gemma 4 E2B",
            fileName = "gemma-4-e2b-it.task",
            downloadUrl = "https://huggingface.co/google/gemma-4-e2b-it-litert/resolve/main/gemma-4-e2b-it-litert.task",
            fileSize = "~2.5GB",
            minSizeBytes = 2_000_000_000L
        ),
        GEMMA_E4B(
            id = "gemma_e4b",
            name = "Gemma 4 E4B",
            fileName = "gemma-4-e4b-it.task",
            downloadUrl = "https://huggingface.co/google/gemma-4-e4b-it-litert/resolve/main/gemma-4-e4b-it-litert.task",
            fileSize = "~5GB",
            minSizeBytes = 4_000_000_000L
        )
    }

    companion object {
        const val ACTION_DOWNLOAD = "com.gardenworld.auto.DOWNLOAD_MODEL"
        const val EXTRA_MODEL_ID = "model_id"
    }

    private val binder = LocalBinder()
    private var downloadThread: Thread? = null
    private var isDownloading = false
    private var shouldCancel = false

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState

    private val _selectedModel = MutableStateFlow(ModelOption.GEMMA_270M)
    val selectedModel: StateFlow<ModelOption> = _selectedModel

    private var currentModelOption: ModelOption? = null

    inner class LocalBinder : Binder() {
        fun getService(): ModelDownloadService = this@ModelDownloadService
    }

    override fun onCreate() {
        super.onCreate()
        // 检查已下载的模型
        checkExistingModels()
        Timber.d("ModelDownloadService created")
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            val modelId = it.getStringExtra(EXTRA_MODEL_ID)
            modelId?.let { id ->
                val model = ModelOption.values().find { m -> m.id == id }
                model?.let { startDownload(it) }
            }
        }
        return START_NOT_STICKY
    }

    /**
     * 检查已有模型
     */
    private fun checkExistingModels() {
        for (model in ModelOption.values()) {
            val file = File(filesDir, model.fileName)
            if (file.exists() && file.length() >= model.minSizeBytes) {
                _selectedModel.value = model
                _downloadState.value = DownloadState.Completed(model)
                Timber.d("Found existing model: ${model.name}")
                return
            }
        }
    }

    /**
     * 选择模型
     */
    fun selectModel(model: ModelOption) {
        _selectedModel.value = model
        // 检查是否已下载
        val file = File(filesDir, model.fileName)
        if (file.exists() && file.length() >= model.minSizeBytes) {
            _downloadState.value = DownloadState.Completed(model)
        } else {
            _downloadState.value = DownloadState.Idle
        }
    }

    /**
     * 开始下载模型（使用HttpURLConnection支持断点续传）
     */
    fun startDownload(model: ModelOption) {
        if (isDownloading) {
            Timber.w("Download already in progress")
            return
        }

        currentModelOption = model
        shouldCancel = false
        isDownloading = true

        _downloadState.value = DownloadState.Downloading(0, model)

        downloadThread = Thread {
            downloadModel(model)
        }.apply {
            name = "ModelDownload"
            start()
        }

        Timber.d("Start downloading: ${model.name} from ${model.downloadUrl}")
    }

    /**
     * 下载模型文件
     */
    private fun downloadModel(model: ModelOption) {
        var connection: HttpURLConnection? = null
        val targetFile = File(filesDir, model.fileName)
        val tempFile = File(filesDir, "${model.fileName}.tmp")

        try {
            // 检查已有部分文件大小（断点续传）
            var downloadedBytes = if (tempFile.exists()) tempFile.length() else 0L

            val url = URL(model.downloadUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 30000
                readTimeout = 60000
                // 设置User-Agent避免被拦截
                setRequestProperty("User-Agent", "GardenWorldAuto/1.0")
                // 断点续传
                if (downloadedBytes > 0) {
                    setRequestProperty("Range", "bytes=$downloadedBytes-")
                }
                // 跟随重定向
                instanceFollowRedirects = false
            }

            // 处理重定向（HuggingFace会重定向）
            var responseCode = connection.responseCode
            var redirectCount = 0
            while (responseCode in 300..399 && redirectCount < 5) {
                val newUrl = connection.getHeaderField("Location")
                connection.disconnect()
                connection = (URL(newUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30000
                    readTimeout = 60000
                    setRequestProperty("User-Agent", "GardenWorldAuto/1.0")
                    if (downloadedBytes > 0) {
                        setRequestProperty("Range", "bytes=$downloadedBytes-")
                    }
                }
                responseCode = connection.responseCode
                redirectCount++
            }

            // 获取总大小
            val totalSize: Long
            if (responseCode == 206) {
                // 断点续传
                val contentRange = connection.getHeaderField("Content-Range")
                totalSize = contentRange?.substringAfter("/")?.toLongOrNull()
                    ?: connection.contentLengthLong + downloadedBytes
            } else if (responseCode == 200) {
                // 全新下载
                downloadedBytes = 0
                totalSize = connection.contentLengthLong
            } else {
                throw Exception("HTTP错误: $responseCode")
            }

            Timber.d("下载开始: 已有${downloadedBytes / 1024 / 1024}MB, 总计${totalSize / 1024 / 1024}MB")

            // 写入文件
            val inputStream = connection.inputStream
            val outputStream = FileOutputStream(tempFile, downloadedBytes > 0) // append模式

            val buffer = ByteArray(8192)
            var bytesRead: Int

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                if (shouldCancel) {
                    outputStream.close()
                    inputStream.close()
                    _downloadState.value = DownloadState.Idle
                    Timber.d("下载已取消")
                    return
                }

                outputStream.write(buffer, 0, bytesRead)
                downloadedBytes += bytesRead

                // 更新进度
                if (totalSize > 0) {
                    val progress = ((downloadedBytes * 100) / totalSize).toInt()
                    _downloadState.value = DownloadState.Downloading(progress, model)
                }

                // 每1MB记录一次日志
                if (downloadedBytes % (1024 * 1024) < 8192) {
                    Timber.d("下载进度: ${downloadedBytes / 1024 / 1024}MB / ${totalSize / 1024 / 1024}MB")
                }
            }

            outputStream.close()
            inputStream.close()

            // 重命名为最终文件名
            if (tempFile.renameTo(targetFile)) {
                _downloadState.value = DownloadState.Completed(model)
                Timber.d("模型下载完成: ${targetFile.length() / 1024 / 1024}MB")
            } else {
                // 重命名失败，尝试复制
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
                _downloadState.value = DownloadState.Completed(model)
                Timber.d("模型下载完成(复制): ${targetFile.length() / 1024 / 1024}MB")
            }

        } catch (e: Exception) {
            Timber.e(e, "模型下载失败")
            _downloadState.value = DownloadState.Error(
                model = model,
                message = "下载失败: ${e.message}"
            )
        } finally {
            connection?.disconnect()
            isDownloading = false
        }
    }

    /**
     * 取消下载
     */
    fun cancelDownload() {
        shouldCancel = true
        downloadThread?.join(3000)
        isDownloading = false
        _downloadState.value = DownloadState.Idle
        Timber.d("下载已取消")
    }

    /**
     * 检查指定模型是否已下载
     */
    fun isModelDownloaded(model: ModelOption = selectedModel.value): Boolean {
        val file = File(filesDir, model.fileName)
        return file.exists() && file.length() >= model.minSizeBytes
    }

    /**
     * 获取已下载模型文件路径
     */
    fun getModelPath(model: ModelOption = selectedModel.value): String {
        return File(filesDir, model.fileName).absolutePath
    }

    /**
     * 获取已下载模型文件大小
     */
    fun getModelFileSize(model: ModelOption = selectedModel.value): Long {
        val file = File(filesDir, model.fileName)
        return if (file.exists()) file.length() else 0L
    }

    /**
     * 删除已下载的模型
     */
    fun deleteModel(model: ModelOption): Boolean {
        val file = File(filesDir, model.fileName)
        val tempFile = File(filesDir, "${model.fileName}.tmp")
        val deleted = file.delete() && tempFile.delete()
        if (deleted) {
            _downloadState.value = DownloadState.Idle
            Timber.d("模型已删除: ${model.name}")
        }
        return deleted
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelDownload()
        Timber.d("ModelDownloadService destroyed")
    }
}

/**
 * 下载状态
 */
sealed class DownloadState {
    /** 空闲 - 未开始下载 */
    object Idle : DownloadState()

    /** 下载中 - progress: 0~100 */
    data class Downloading(
        val progress: Int,
        val model: ModelDownloadService.ModelOption? = null
    ) : DownloadState()

    /** 下载完成 */
    data class Completed(
        val model: ModelDownloadService.ModelOption? = null
    ) : DownloadState()

    /** 下载失败 */
    data class Error(
        val model: ModelDownloadService.ModelOption? = null,
        val message: String = "未知错误"
    ) : DownloadState()
}
