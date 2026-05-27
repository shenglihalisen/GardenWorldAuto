package com.gardenworld.auto.vision

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.nio.ByteBuffer
import kotlin.coroutines.resume

/**
 * 屏幕捕获管理器
 * 用于捕获游戏画面供AI分析
 */
class ScreenCaptureManager(private val context: Context) {
    
    companion object {
        const val REQUEST_CODE = 1001
        const val VIRTUAL_DISPLAY_NAME = "GardenWorldCapture"
        const val MAX_IMAGES = 2
    }
    
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    
    private var screenWidth = 1080
    private var screenHeight = 2400
    private var screenDensity = 400
    
    /**
     * 获取屏幕捕获意图
     */
    fun getCaptureIntent(): Intent {
        val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return projectionManager.createScreenCaptureIntent()
    }
    
    /**
     * 初始化屏幕捕获
     */
    fun initialize(resultCode: Int, data: Intent) {
        val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        
        // 获取屏幕尺寸
        val displayMetrics = context.resources.displayMetrics
        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels
        screenDensity = displayMetrics.densityDpi
        
        Timber.d("Screen size: ${screenWidth}x${screenHeight}, density: $screenDensity")
        
        // 创建ImageReader
        imageReader = ImageReader.newInstance(
            screenWidth, screenHeight,
            PixelFormat.RGBA_8888, MAX_IMAGES
        )
        
        // 创建虚拟显示
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, handler
        )
        
        Timber.d("Screen capture initialized")
    }
    
    /**
     * 捕获屏幕截图
     */
    suspend fun captureScreen(): Result<Bitmap> = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            try {
                val reader = imageReader
                if (reader == null) {
                    continuation.resume(Result.failure(Exception("ImageReader not initialized")))
                    return@suspendCancellableCoroutine
                }
                
                // 获取最新图像
                val image: Image? = reader.acquireLatestImage()
                if (image == null) {
                    continuation.resume(Result.failure(Exception("Failed to acquire image")))
                    return@suspendCancellableCoroutine
                }
                
                try {
                    val bitmap = imageToBitmap(image)
                    continuation.resume(Result.success(bitmap))
                } finally {
                    image.close()
                }
                
            } catch (e: Exception) {
                Timber.e(e, "Screen capture failed")
                continuation.resume(Result.failure(e))
            }
        }
    }
    
    /**
     * 将Image转换为Bitmap
     */
    private fun imageToBitmap(image: Image): Bitmap {
        val planes = image.planes
        val buffer: ByteBuffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * screenWidth
        
        // 创建Bitmap
        val bitmap = Bitmap.createBitmap(
            screenWidth + rowPadding / pixelStride,
            screenHeight,
            Bitmap.Config.ARGB_8888
        )
        
        bitmap.copyPixelsFromBuffer(buffer)
        
        // 如果有padding，裁剪到实际屏幕大小
        return if (rowPadding > 0) {
            Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
        } else {
            bitmap
        }
    }
    
    /**
     * 捕获游戏区域（如果游戏不是全屏）
     */
    suspend fun captureGameArea(x: Int, y: Int, width: Int, height: Int): Result<Bitmap> {
        return captureScreen().map { fullScreen ->
            Bitmap.createBitmap(fullScreen, x, y, width, height)
        }
    }
    
    /**
     * 连续捕获多张截图
     */
    suspend fun captureSequence(count: Int, intervalMs: Long): List<Bitmap> {
        val bitmaps = mutableListOf<Bitmap>()
        repeat(count) {
            captureScreen().onSuccess { bitmap ->
                bitmaps.add(bitmap)
            }
            if (it < count - 1) {
                kotlinx.coroutines.delay(intervalMs)
            }
        }
        return bitmaps
    }
    
    /**
     * 释放资源
     */
    fun release() {
        try {
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
            
            virtualDisplay = null
            imageReader = null
            mediaProjection = null
            
            Timber.d("Screen capture released")
        } catch (e: Exception) {
            Timber.e(e, "Error releasing screen capture")
        }
    }
    
    /**
     * 检查是否已初始化
     */
    fun isInitialized(): Boolean {
        return mediaProjection != null && imageReader != null
    }
}
