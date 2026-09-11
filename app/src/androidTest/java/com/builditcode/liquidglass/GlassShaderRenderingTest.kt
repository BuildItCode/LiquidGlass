package com.builditcode.liquidglass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
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
import com.builditcode.glass.TriLevelLayout
import com.builditcode.glass.TrilevelLayers
import com.builditcode.glass.layeredBackdropCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class GlassShaderRenderingTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun tintPreservesTranslucentSourceCoverage() {
        val filter = BackdropFilter.Glass(blurRadiusIntensity = 0f, refraction = 0f,
            dispersion = 0f, edge = 0f, tint = Color.Red.copy(alpha = 0.5f))
        compose.setContent {
            TriLevelLayout(
                modifier = Modifier.size(160.dp).background(Color.Black),
                background = {
                    Box(Modifier.fillMaxSize().drawBehind { drawRect(Color.White.copy(alpha = 0.25f)) })
                },
                overlay = {
                    Box(Modifier.fillMaxSize().testTag("glass")
                        .layeredBackdropCapture(TrilevelLayers.Background, filter = filter))
                }
            )
        }
        val pixels = compose.onNodeWithTag("glass").captureToImage().toPixelMap()
        val center = pixels[pixels.width / 2, pixels.height / 2]
        // A 25%-covered tinted capture over the visible 25%-white source on black.
        assertEquals(0.4375f, center.red, 0.025f)
        assertEquals(0.3125f, center.green, 0.025f)
        assertEquals(0.3125f, center.blue, 0.025f)
    }

    @Test
    fun squareCornerRefractionHasNoDiagonalDiscontinuity() {
        val filter = BackdropFilter.Glass(blurRadiusIntensity = 0f, refraction = 0.4f,
            dispersion = 0f, edge = 0f, shape = RoundedCornerShape(0.dp))
        compose.setContent {
            TriLevelLayout(
                modifier = Modifier.size(160.dp),
                background = {
                    Box(Modifier.fillMaxSize().drawBehind {
                        drawRect(Brush.horizontalGradient(listOf(Color.Black, Color.Red)))
                    })
                },
                overlay = {
                    Box(Modifier.fillMaxSize().testTag("glass")
                        .layeredBackdropCapture(TrilevelLayers.Background, filter = filter))
                }
            )
        }
        val pixels = compose.onNodeWithTag("glass").captureToImage().toPixelMap()
        val inset = (pixels.width * 0.04f).toInt().coerceAtLeast(5)
        val y = pixels.height - 1 - inset
        var largestJump = 0f
        for (x in inset - 4..inset + 4) {
            largestJump = maxOf(largestJump, abs(pixels[x + 1, y].red - pixels[x, y].red))
        }
        assertTrue("The bevel must stay continuous across its corner diagonal; jump=$largestJump",
            largestJump < 0.035f)
    }

    @Test
    fun dispersionLeavesTheFlatInteriorAchromatic() {
        val filter = BackdropFilter.Glass(blurRadiusIntensity = 0f, refraction = 0.2f,
            dispersion = 1f, edge = 0f, shape = RoundedCornerShape(16.dp))
        compose.setContent {
            TriLevelLayout(
                modifier = Modifier.size(160.dp),
                background = {
                    Box(Modifier.fillMaxSize().drawBehind {
                        drawRect(Brush.horizontalGradient(listOf(Color.Black, Color.White)))
                    })
                },
                overlay = {
                    Box(Modifier.fillMaxSize().testTag("glass")
                        .layeredBackdropCapture(TrilevelLayers.Background, filter = filter))
                }
            )
        }
        val pixels = compose.onNodeWithTag("glass").captureToImage().toPixelMap()
        val interior = pixels[pixels.width / 4, pixels.height / 2]
        assertEquals(interior.red, interior.green, 0.003f)
        assertEquals(interior.green, interior.blue, 0.003f)
        val rim = pixels[(pixels.width * 0.02f).toInt(), pixels.height / 2]
        assertTrue("The bending rim must retain chromatic separation", abs(rim.blue - rim.red) > 0.015f)
    }
}
