package com.builditcode.liquidglass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.builditcode.glass.BackdropFilter
import com.builditcode.glass.QuadLevelLayers
import com.builditcode.glass.QuadLevelLayout
import com.builditcode.glass.layeredBackdropCapture
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class NestedGlassRenderingTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun retainedThreeLevelGlassRendersBackgroundChangesWithoutSnapshotDelay() {
        val background = mutableStateOf(Color.Red)
        val filter = BackdropFilter.Glass(blurRadiusIntensity = 2f, edge = 0f)
        compose.setContent {
            QuadLevelLayout(
                modifier = Modifier.size(220.dp),
                enableHardwareCapture = true,
                // Live GPU output must not wait for the CPU snapshot interval.
                debounceMs = 60_000L,
                background = {
                    Box(Modifier.fillMaxSize().drawBehind { drawRect(background.value) })
                },
                midground = {
                    Box(Modifier.size(180.dp).layeredBackdropCapture(QuadLevelLayers.Background, filter = filter))
                },
                foreground = {
                    Box(Modifier.size(140.dp).layeredBackdropCapture(QuadLevelLayers.Midground, filter = filter))
                },
                overlay = {
                    Box(Modifier.size(100.dp).testTag("top-glass")
                        .layeredBackdropCapture(QuadLevelLayers.Foreground, filter = filter))
                }
            )
        }
        for (expected in listOf(Color.Red, Color.Blue, Color.Green, Color.Red)) {
            compose.runOnIdle { background.value = expected }
            val pixels = compose.onNodeWithTag("top-glass").captureToImage().toPixelMap()
            val actual = pixels[pixels.width / 2, pixels.height / 2]
            assertTrue("Top glass must show $expected; found $actual",
                kotlin.math.abs(actual.red - expected.red) < 0.04f &&
                    kotlin.math.abs(actual.green - expected.green) < 0.04f &&
                    kotlin.math.abs(actual.blue - expected.blue) < 0.04f && actual.alpha > 0.96f)
        }
    }
}
