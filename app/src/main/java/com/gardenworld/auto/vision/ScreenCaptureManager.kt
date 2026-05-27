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
    
    fun getCaptureIntent(): Intent {
        val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return projectionManager.createScreenCaptureIntent()
    }
    
    fun initialize(resultCode: Int, data: Intent) {
        val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        
        val displayMetrics = context.resources.displayMetrics
        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels
        screenDensity = displayMetrics.densityDpi
        
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, MAX_IMAGES)
        
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME, screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, handler
        )
        
        Timber.d("Screen capture initialized: ${screenWidth}x${screenHeight}")
    }
    
    suspend fun captureScreen(): Result<Bitmap> = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            try {
                val reader = imageReader ?: run {
                    continuation.resume(Result.failure(Exception("ImageReader not initialized")))
                    return@suspendCancellableCoroutine
                }
                
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
    
    private fun imageToBitmap(image: Image): Bitmap {
        val planes = image.planes
        val buffer: ByteBuffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * screenWidth
        
        val bitmap = Bitmap.createBitmap(screenWidth + rowPadding / pixelStride, screenHeight, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        
        return if (rowPadding > 0) Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight) else bitmap
    }
    
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
    
    fun isInitialized(): Boolean = mediaProjection != null && imageReader != null
}
