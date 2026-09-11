package com.builditcode.glass

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.Picture
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A serial, reusable GPU readback surface for already-recorded pictures.
 * Retain before launching each worker and release on job completion, including cancellation
 * before dispatch. Retirement then closes the surface only after every worker has finished.
 */
@RequiresApi(29)
internal class HardwarePictureReader {
    private val mutex = Mutex()
    private var reader: ImageReader? = null
    private var renderer: HardwareRenderer? = null
    private var node: RenderNode? = null
    private var recordedBitmapLease: BackdropBitmapLease? = null
    private var references = 0
    private var retired = false
    private var reusableSurfaceUnavailable = false

    @Synchronized fun retain() { check(!retired); references++ }

    @Synchronized fun release() {
        check(references > 0)
        if (--references == 0 && retired) clear()
    }

    @Synchronized fun retire() {
        retired = true
        if (references == 0) clear()
    }

    suspend fun capture(picture: Picture, width: Int, height: Int, bitmapLease: BackdropBitmapLease? = null): Bitmap = mutex.withLock {
        if (!reusableSurfaceUnavailable) {
            val retained = bitmapLease?.copy()
            var transferred = false
            try {
                val bitmap = captureOnSurface(picture, width, height)
                val previous = recordedBitmapLease
                recordedBitmapLease = retained
                transferred = true
                previous?.close()
                return@withLock bitmap
            } catch (error: CancellationException) {
                throw error
            } catch (_: FrameUnavailableException) {
                // A dropped frame or a stopped/lost surface is transient (e.g. resume).
                // Recreate it on the next capture instead of permanently disabling reuse.
                clear()
            } catch (_: Exception) {
                // Driver/surface support varies. Do not retry a failing surface every frame
                // or stop publishing: the platform picture snapshot remains the fallback.
                reusableSurfaceUnavailable = true
                clear()
            } finally {
                if (!transferred) retained?.close()
            }
        }
        Bitmap.createBitmap(picture, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun captureOnSurface(picture: Picture, width: Int, height: Int): Bitmap {
        if (reader?.width != width || reader?.height != height) {
            clear()
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
            node = RenderNode("Glass picture readback").apply { setPosition(0, 0, width, height) }
            renderer = HardwareRenderer()
            checkNotNull(renderer).apply {
                setSurface(checkNotNull(reader).surface)
                setContentRoot(node)
                isOpaque = false
            }
        }
        val content = checkNotNull(node)
        val canvas = content.beginRecording(width, height)
        try {
            canvas.scale(width.toFloat() / picture.width, height.toFloat() / picture.height)
            canvas.drawPicture(picture)
        } finally { content.endRecording() }
        val result = checkNotNull(renderer).createRenderRequest().setWaitForPresent(true).syncAndDraw()
        if (result != HardwareRenderer.SYNC_OK && result != HardwareRenderer.SYNC_REDRAW_REQUESTED) {
            throw FrameUnavailableException()
        }
        return (checkNotNull(reader).acquireLatestImage() ?: throw FrameUnavailableException()).use { image ->
            checkNotNull(image.hardwareBuffer).use { buffer ->
                val hardware = checkNotNull(Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB)))
                try { checkNotNull(hardware.copy(Bitmap.Config.ARGB_8888, true)) }
                finally { hardware.recycle() }
            }
        }
    }

    private class FrameUnavailableException : RuntimeException()

    private fun clear() {
        renderer?.destroy()
        renderer = null
        node?.discardDisplayList()
        node = null
        reader?.close()
        reader = null
        recordedBitmapLease?.close()
        recordedBitmapLease = null
    }
}
