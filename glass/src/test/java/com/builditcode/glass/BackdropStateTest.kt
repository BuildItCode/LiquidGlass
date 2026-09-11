package com.builditcode.glass

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class BackdropStateTest {
    @Test
    fun movingOneRegionKeepsTheOtherResultAndReusesTheMaster() = runTest {
        val state = BackdropState(backgroundScope, 0.5f, 0L, true, isUpdateEnabled = { false })
        state.updateSourceRect(Rect(0f, 0f, 100f, 100f))
        state.registerRegion(1, Rect(0f, 0f, 20f, 20f), 0)
        state.registerRegion(2, Rect(40f, 40f, 60f, 60f), 0)
        val bitmap = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888).asImageBitmap()
        val session = state.beginHardwareCapture(IntSize(50, 50))
        state.applyHardwareImageCapture(bitmap, emptyMap(), session)
        try {
            val stationary = state.getResult(2)
            state.registerRegion(1, Rect(10.5f, 0f, 30.5f, 20f), 0)
            assertSame(stationary, state.getResult(2))
            assertSame(bitmap, state.getResult(1)?.masterImage)
            assertEquals(-0.5f, state.getResult(1)!!.sampleOffset.x, 0f)
            state.clearSourceCapture()
            assertNull(state.getResult(1))
            assertTrue(bitmap.asAndroidBitmap().isRecycled)
        } finally {
            state.dispose()
        }
    }

    @Test
    fun overlappingBlurWorkersDoNotShareScratchMemoryWhileActive() = runTest {
        val state = BackdropState(backgroundScope, 1f, 0L, true, isUpdateEnabled = { false })
        val colors = listOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt())
        try {
            colors.map { color ->
                async(Dispatchers.Default) {
                    val master = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888)
                    master.eraseColor(color)
                    try {
                        val blurred = state.prepareBlurredMasters(master, setOf(1, 3, 7))
                        try {
                            assertEquals(3, blurred.size)
                            blurred.values.forEach { assertEquals(color, it.asAndroidBitmap().getPixel(40, 30)) }
                        } finally {
                            blurred.values.forEach { it.safeRecycle() }
                        }
                    } finally {
                        master.recycle()
                    }
                }
            }.awaitAll()
        } finally {
            state.dispose()
        }
    }
}
