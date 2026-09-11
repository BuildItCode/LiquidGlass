package com.builditcode.glass

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Picture
import android.os.Looper
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 28)
class HardwarePictureCaptureTest {
    @Test fun hardwarePictureRendersAndReadsBackOffMainWithoutPromotingToLiveLayerSnapshots() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val state = BackdropState(scope, 0.5f, 0L, isUpdateEnabled = { false })
        var renderedOnMain: Boolean? = null
        val picture = object : Picture() {
            override fun requiresHardwareAcceleration() = true
            override fun draw(canvas: Canvas) {
                renderedOnMain = Looper.myLooper() == Looper.getMainLooper()
                super.draw(canvas)
            }
        }.apply { beginRecording(80, 60).drawColor(Color.RED); endRecording() }
        try {
            withContext(Dispatchers.Main) {
                state.updateSourceRect(Rect(0f, 0f, 80f, 60f))
                state.registerRegion(1, Rect(0f, 0f, 80f, 60f), 3)
                state.onPictureRecorded(picture, 80, 60)
            }
            withTimeout(5_000) { scope.coroutineContext[Job]!!.children.toList().joinAll() }
            withContext(Dispatchers.Main) {
                assertEquals(false, renderedOnMain)
                assertFalse(state.shouldUseHardwareSnapshot)
                val output = state.getResult(1)!!.fallbackBitmap!!.asAndroidBitmap()
                assertEquals(40, output.width)
                assertEquals(30, output.height)
                assertEquals(Color.RED, output.getPixel(20, 15))
            }
        } finally {
            withContext(Dispatchers.Main) { state.dispose() }
            scope.cancel()
        }
    }
}
