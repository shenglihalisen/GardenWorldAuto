package com.gardenworld.auto.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

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

    private val binder = LocalBinder()
    private var downloadThread: Thread? = null
    private var isDownloading = false
    private var shouldCancel = false

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState

    private val _selectedModel = MutableStateFlow(ModelOption.GEMMA_270M)
    val selectedModel: StateFlow<ModelOption> = _selectedModel

    inner class LocalBinder : Binder() { fun getService(): ModelDownloadService = this@ModelDownloadService }

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

    private fun downloadModel(model: ModelOption) {
        var connection: HttpURLConnection? = null
        val targetFile = File(filesDir, model.fileName)
        val tempFile = File(filesDir, "${model.fileName}.tmp")

        try {
            var downloadedBytes = if (tempFile.exists()) tempFile.length() else 0L
            val url = URL(model.downloadUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 30000; readTimeout = 60000
                setRequestProperty("User-Agent", "GardenWorldAuto/1.0")
                if (downloadedBytes > 0) setRequestProperty("Range", "bytes=$downloadedBytes-")
                instanceFollowRedirects = false
            }

            var responseCode = connection.responseCode
            while (responseCode in 300..399 && responseCode < 400) {
                val newUrl = connection.getHeaderField("Location")
                connection.disconnect()
                connection = (URL(newUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30000; readTimeout = 60000
                    setRequestProperty("User-Agent", "GardenWorldAuto/1.0")
                    if (downloadedBytes > 0) setRequestProperty("Range", "bytes=$downloadedBytes-")
                }
                responseCode = connection.responseCode
            }

            val totalSize: Long
            if (responseCode == 206) {
                val contentRange = connection.getHeaderField("Content-Range")
                totalSize = contentRange?.substringAfter("/")?.toLongOrNull() ?: connection.contentLengthLong + downloadedBytes
            } else if (responseCode == 200) {
                downloadedBytes = 0; totalSize = connection.contentLengthLong
            } else {
                throw Exception("HTTP错误: $responseCode")
            }

            Timber.d("下载: 已有${downloadedBytes/1024/1024}MB, 总计${totalSize/1024/1024}MB")

            val inputStream = connection.inputStream
            val outputStream = FileOutputStream(tempFile, downloadedBytes > 0)
            val buffer = ByteArray(8192); var bytesRead: Int

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                if (shouldCancel) {
                    outputStream.close(); inputStream.close()
                    _downloadState.value = DownloadState.Idle; return
                }
                outputStream.write(buffer, 0, bytesRead)
                downloadedBytes += bytesRead
                if (totalSize > 0) {
                    val progress = ((downloadedBytes * 100) / totalSize).toInt()
                    _downloadState.value = DownloadState.Downloading(progress, model)
                }
            }

            outputStream.close(); inputStream.close()
            if (tempFile.renameTo(targetFile)) {
                _downloadState.value = DownloadState.Completed(model)
            } else {
                tempFile.copyTo(targetFile, overwrite = true); tempFile.delete()
                _downloadState.value = DownloadState.Completed(model)
            }
            Timber.d("模型下载完成: ${targetFile.length()/1024/1024}MB")

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

    fun getModelPath(model: ModelOption = selectedModel.value): String = File(filesDir, model.fileName).absolutePath

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
