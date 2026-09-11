package com.builditcode.liquidglass

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.builditcode.glass.BackdropFilter
import com.builditcode.glass.TriLevelLayout
import com.builditcode.glass.TrilevelLayers
import com.builditcode.glass.layeredBackdropCapture
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 31, maxSdkVersion = 32)
class SoftwareGlassRenderingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pictureCapturesRefreshMultipleConsumersAfterAnIdleSourceStartsMoving() = verify(false)
    @Test fun hardwareSnapshotsRefreshMultipleConsumersAfterAnIdleSourceStartsMoving() = verify(true)

    private fun verify(hardwareImage: Boolean) {
        val pixels = Bitmap.createBitmap(400, 160, Bitmap.Config.ARGB_8888).apply {
            setPixels(IntArray(400 * 160) { if (it % 400 < 200) android.graphics.Color.RED else android.graphics.Color.BLUE },
                0, 400, 0, 0, 400, 160)
        }
        val source = if (hardwareImage) checkNotNull(pixels.copy(Bitmap.Config.HARDWARE, false)) else pixels
        val image = source.asImageBitmap()
        val translation = mutableFloatStateOf(0f)
        var sourceWidth = 0f
        val visible = mutableStateOf(true)
        compose.setContent {
            if (!visible.value) return@setContent
            sourceWidth = with(LocalDensity.current) { 240.dp.toPx() }
            TriLevelLayout(
                modifier = Modifier.size(240.dp, 160.dp),
                background = {
                    Box(Modifier.fillMaxSize().graphicsLayer { translationX = translation.floatValue }
                        .drawBehind { drawImage(image, dstSize = IntSize((size.width * 2).toInt(), size.height.toInt())) })
                },
                // Hides the original source so a missing/stale capture cannot pass by
                // simply revealing the live background underneath a transparent box.
                foreground = { Box(Modifier.fillMaxSize().background(Color.Black)) },
                overlay = {
                    repeat(2) { index ->
                        Box(Modifier.offset(x = (20 + index * 120).dp, y = 40.dp).size(60.dp)
                            .testTag("glass$index").layeredBackdropCapture(TrilevelLayers.Background,
                                filter = BackdropFilter.Glass(blurRadiusIntensity = 1f)))
                    }
                }
            )
        }
        fun bothShow(red: Boolean): Boolean = (0..1).all { index ->
            val captured = compose.onNodeWithTag("glass$index").captureToImage().toPixelMap()
            val center = captured[captured.width / 2, captured.height / 2]
            if (red) center.red > 0.9f && center.blue < 0.1f else center.blue > 0.9f && center.red < 0.1f
        }
        compose.waitUntil(timeoutMillis = 5_000) { bothShow(true) }
        // Several identical snapshots exercise the reuse path before a property-only
        // graphicsLayer change, which does not re-record the child's drawing commands.
        Thread.sleep(200)
        compose.runOnIdle { translation.floatValue = -sourceWidth }
        compose.waitUntil(timeoutMillis = 5_000) { bothShow(false) }
        compose.runOnIdle { translation.floatValue = 0f }
        compose.waitUntil(timeoutMillis = 5_000) { bothShow(true) }
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        if (source !== pixels) source.recycle()
        pixels.recycle()
    }
}
