package com.builditcode.glass

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
// Robolectric's API 33/34 native runtime uses Android 12 Skia, which cannot compile
// AGSL child-shader eval(). API 35 uses the current native graphics implementation.
@Config(sdk = [35], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CaptureDrawCacheTest {
    @Test
    fun glassEffectRebuildsForUniformChangesAndReusesUnchangedFrames() {
        val cache = CaptureDrawCache()
        val glass = BackdropFilter.Glass()
        val radii = GlassCornerRadii(12f, 12f, 12f, 12f)
        cache.applyGlassUniforms(glass, 100f, 60f, radii)
        var effect = cache.glassRenderEffect(6f)
        cache.applyGlassUniforms(glass.copy(), 100f, 60f, radii)
        assertSame(effect, cache.glassRenderEffect(6f))

        for (updated in listOf(
            glass.copy(tint = Color.Red), glass.copy(refraction = 0.8f),
            glass.copy(dispersion = 0.7f), glass.copy(edge = 0.5f)
        )) {
            cache.applyGlassUniforms(updated, 100f, 60f, radii)
            val next = cache.glassRenderEffect(6f)
            assertNotSame(effect, next)
            effect = next
        }
        cache.applyGlassUniforms(glass.copy(edge = 0.5f), 160f, 90f, radii)
        val resized = cache.glassRenderEffect(6f)
        assertNotSame(effect, resized)
        cache.applyGlassUniforms(glass.copy(edge = 0.5f), 160f, 90f, GlassCornerRadii.Zero)
        assertNotSame(resized, cache.glassRenderEffect(6f))
    }

    @Test
    fun fontScaleParticipatesInOutlineCache() {
        val shape = object : Shape {
            override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
                Outline.Rounded(RoundRect(0f, 0f, size.width, size.height, CornerRadius(density.fontScale * 10f)))
        }
        val cache = CaptureDrawCache()
        val size = Size(100f, 100f)
        assertEquals(10f, cache.cornerRadii(shape, size, LayoutDirection.Ltr, Density(1f, 1f)).topLeft, 0f)
        assertEquals(20f, cache.cornerRadii(shape, size, LayoutDirection.Ltr, Density(1f, 2f)).topLeft, 0f)
    }

    @Test
    fun equivalentMorphShapesReuseOutlineCache() {
        val first = LiquidMorphShape(RoundedCornerShape(12.dp), 0.4f)
        val second = LiquidMorphShape(RoundedCornerShape(12.dp), 0.4f)
        val cache = CaptureDrawCache()
        val radii = cache.cornerRadii(first, Size(100f, 100f), LayoutDirection.Ltr, Density(1f))
        assertSame(radii, cache.cornerRadii(second, Size(100f, 100f), LayoutDirection.Ltr, Density(1f)))
    }

    @Test
    fun fortyConsumersSharingAFilterKeepTheirShaderEffectsIndependent() {
        val glass = BackdropFilter.Glass()
        val caches = List(40) { CaptureDrawCache() }
        val effects = caches.mapIndexed { index, cache ->
            cache.applyGlassUniforms(glass, 60f + index, 40f + index, GlassCornerRadii.Zero)
            cache.glassRenderEffect(6f)
        }
        caches[0].applyGlassUniforms(glass.copy(tint = Color.Red), 200f, 100f, GlassCornerRadii.Zero)
        assertNotSame(effects[0], caches[0].glassRenderEffect(6f))
        for (index in 1 until caches.size) {
            assertNotSame(effects[0], effects[index])
            assertSame(effects[index], caches[index].glassRenderEffect(6f))
        }
    }
}
