package com.builditcode.glass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Picture
import androidx.compose.ui.graphics.asImageBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class HardwarePictureReaderTest {
    private fun picture(width: Int, height: Int, draw: (Canvas) -> Unit) = object : Picture() {
        // Production uses this reader only for hardware pictures. Compare both captures
        // on the GPU; software Canvas has different fractional edge coverage.
        override fun requiresHardwareAcceleration() = true
    }.apply {
        val canvas = beginRecording(width, height)
        try { draw(canvas) } finally { endRecording() }
    }

    private fun Bitmap.pixels() = IntArray(width * height).also {
        getPixels(it, 0, width, 0, 0, width, height)
    }

    @Test fun successiveTransparentFramesAndResizingMatchPlatformSnapshots() = runBlocking {
        val reader = HardwarePictureReader().apply { retain() }
        try {
            withContext(Dispatchers.Default) {
                repeat(24) { frame ->
                    val width = if (frame < 12) 64 else 80
                    val height = if (frame < 12) 48 else 60
                    val picture = picture(width, height) { canvas ->
                        if (frame % 2 == 0) canvas.drawColor(Color.GREEN)
                        canvas.drawRect(frame.toFloat(), 5f, frame + 20f, 25f,
                            Paint().apply { color = Color.argb(128, 220, 20, 120) })
                    }
                    val expected = Bitmap.createBitmap(picture, width / 2, height / 2, Bitmap.Config.ARGB_8888)
                    val actual = reader.capture(picture, width / 2, height / 2)
                    try { assertArrayEquals("frame=$frame", expected.pixels(), actual.pixels()) }
                    finally { expected.recycle(); actual.recycle() }
                }
            }
        } finally { reader.retire(); reader.release() }
    }

    @Test fun hardwareBitmapScalingMatchesThePlatformSnapshot() = runBlocking {
        val software = Bitmap.createBitmap(96, 72, Bitmap.Config.ARGB_8888).apply {
            setPixels(IntArray(96 * 72) { index ->
                Color.argb(128 + index % 128, index % 256, (index * 3) % 256, (index * 7) % 256)
            }, 0, 96, 0, 0, 96, 72)
        }
        val hardware = software.copy(Bitmap.Config.HARDWARE, false)
        val reader = HardwarePictureReader().apply { retain() }
        try {
            withContext(Dispatchers.Default) {
                val picture = picture(96, 72) { canvas ->
                    canvas.translate(3.25f, -1.5f)
                    canvas.drawBitmap(hardware, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
                }
                assertTrue(picture.requiresHardwareAcceleration())
                val expected = Bitmap.createBitmap(picture, 48, 36, Bitmap.Config.ARGB_8888)
                val actual = reader.capture(picture, 48, 36)
                try { assertArrayEquals(expected.pixels(), actual.pixels()) }
                finally { expected.recycle(); actual.recycle() }
            }
        } finally { reader.retire(); reader.release(); hardware.recycle(); software.recycle() }
    }

    @Test fun retirementWaitsForAnAlreadyRunningCapture() = runBlocking {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val reader = HardwarePictureReader().apply { retain() }
        val picture = object : Picture() {
            override fun draw(canvas: Canvas) {
                entered.countDown()
                check(resume.await(5, TimeUnit.SECONDS))
                super.draw(canvas)
            }
        }.apply { beginRecording(40, 30).drawColor(Color.BLUE); endRecording() }
        val worker = async(Dispatchers.Default) { reader.capture(picture, 40, 30) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            reader.retire()
            resume.countDown()
            val result = worker.await()
            try { assertEquals(Color.BLUE, result.getPixel(20, 15)) }
            finally { result.recycle() }
        } finally {
            resume.countDown()
            worker.join()
            reader.retire()
            reader.release()
        }
    }

    @Test fun failedSurfaceCaptureContinuesPublishingThroughThePlatformFallback() = runBlocking {
        val reader = HardwarePictureReader().apply { retain() }
        var draws = 0
        val picture = object : Picture() {
            override fun requiresHardwareAcceleration() = true
            override fun draw(canvas: Canvas) {
                if (++draws == 1) throw IllegalStateException("Simulated surface recording failure")
                super.draw(canvas)
            }
        }.apply { beginRecording(40, 30).drawColor(Color.RED); endRecording() }
        try {
            withContext(Dispatchers.Default) {
                repeat(2) {
                    val result = reader.capture(picture, 40, 30)
                    try { assertEquals(Color.RED, result.getPixel(20, 15)) }
                    finally { result.recycle() }
                }
                assertEquals(3, draws)
            }
        } finally { reader.retire(); reader.release() }
    }

    @Test fun retainedRecordingKeepsLowerLayerPixelsAliveUntilItIsReplaced() = runBlocking {
        val lower = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val lease = BackdropBitmapLease().apply { retain(lower.asImageBitmap()) }
        val reader = HardwarePictureReader().apply { retain() }
        try {
            withContext(Dispatchers.Default) {
                val first = picture(40, 30) { it.drawBitmap(lower, 0f, 0f, null) }
                reader.capture(first, 40, 30, lease).recycle()
                lease.close()
                lower.safeRecycle()
                assertFalse("The retained display list still owns the lower bitmap", lower.isRecycled)
                val second = picture(40, 30) { it.drawColor(Color.BLUE) }
                val result = reader.capture(second, 40, 30)
                try { assertEquals(Color.BLUE, result.getPixel(20, 15)) }
                finally { result.recycle() }
                assertTrue("Replacing the recording releases its old pixels", lower.isRecycled)
            }
        } finally { lease.close(); reader.retire(); reader.release(); lower.safeRecycle() }
    }
}
