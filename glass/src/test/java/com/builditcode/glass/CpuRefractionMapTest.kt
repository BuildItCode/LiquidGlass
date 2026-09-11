package com.builditcode.glass

import android.graphics.Bitmap
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.min

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CpuRefractionMapTest {
    @Test
    fun cachedGeometryMatchesOriginalPixelsAcrossFramesSizesAndFilters() {
        val maps = CpuRefractionMapCache(maxPixels = 4096)
        for ((w, h) in listOf(1 to 9, 17 to 1, 17 to 31, 31 to 17, 90 to 70)) {
            for ((refraction, edge) in listOf(0f to 0f, 0.24f to 0.18f, 1f to 0.7f, -1f to 0.5f)) {
                repeat(3) { frame ->
                    val original = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                        setPixels(IntArray(w * h) { i ->
                            ((64 + (i + frame) % 192) shl 24) or ((i * 3779 + frame * 99991) and 0xffffff)
                        }, 0, w, 0, 0, w, h)
                    }
                    val actual = original.copy(Bitmap.Config.ARGB_8888, true)
                    try {
                        referenceRefraction(original, refraction, edge, IntArray(w * h), IntArray(w * h))
                        applyCpuGlassRefraction(actual, refraction, edge, IntArray(w * h), IntArray(w * h), maps)
                        assertTrue("${w}x$h frame=$frame refraction=$refraction edge=$edge", original.sameAs(actual))
                    } finally { original.recycle(); actual.recycle() }
                }
            }
        }
    }

    @Test
    fun cacheReusesGeometryAndEvictsWithinItsPixelAndEntryBudgets() {
        val maps = CpuRefractionMapCache(maxPixels = 200, maxEntries = 2)
        val first = maps.indices(10, 10, 0.24f, 0.18f)
        val second = maps.indices(5, 10, 0.24f, 0.18f)
        assertSame(first, maps.indices(10, 10, 0.24f, 0.18f))
        maps.indices(4, 10, 0.24f, 0.18f)
        assertSame(first, maps.indices(10, 10, 0.24f, 0.18f))
        assertNotSame(second, maps.indices(5, 10, 0.24f, 0.18f))
        assertNull(maps.indices(20, 20, 0.24f, 0.18f))
        assertNotSame(first, maps.indices(10, 10, 0.8f, 0.18f))
        maps.clear()
        assertNotSame(first, maps.indices(10, 10, 0.24f, 0.18f))
    }

    @Test
    fun preparedFrameCanReleaseTransientPixelsWithoutReusingStaleResults() {
        val cache = CpuBlurCache()
        val first = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        val next = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
        try {
            val old = cache.glassRefraction(first, 0, BackdropFilter.Glass(), IntSize(32, 24))
            cache.clearResults()
            assertTrue(old.isRecycled)
            val actual = cache.glassRefraction(next, 0, BackdropFilter.Glass(), IntSize(32, 24))
            assertTrue(next.sameAs(actual))
        } finally { cache.clear(); first.recycle(); next.recycle() }
    }

    // Frozen pre-optimization sampler: verifies exact pixels, including alpha and edge rounding.
    private fun referenceRefraction(
        bitmap: Bitmap,
        refraction: Float,
        edge: Float,
        pixels: IntArray,
        output: IntArray
    ): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 1 || h <= 1) return bitmap

        val count = w * h
        if (pixels.size < count || output.size < count) return bitmap

        val refractionAmount = refraction.coerceAtLeast(0f)
        val edgeAmount = edge.coerceAtLeast(0f)
        if (refractionAmount <= 0f && edgeAmount <= 0f) return bitmap

        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val halfW = w * 0.5f
        val halfH = h * 0.5f
        val minExt = min(halfW, halfH).coerceAtLeast(1f)
        val edgeWidth = (minExt * (0.10f + edgeAmount * 0.28f) * 1.45f).coerceAtLeast(1f)
        val radialStrength = refractionAmount * minExt * 0.08f
        val edgeStrength = (edgeAmount * 0.22f + refractionAmount * 0.12f) * minExt

        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val idx = y * w + x
                val fx = x + 0.5f
                val fy = y + 0.5f

                val left = fx
                val right = w - fx
                val top = fy
                val bottom = h - fy
                val nearestHorizontal = min(left, right)
                val nearestVertical = min(top, bottom)
                val nearest = min(nearestHorizontal, nearestVertical)

                var nx = 0f
                var ny = 0f
                if (nearestHorizontal <= nearestVertical) {
                    nx = if (left <= right) -1f else 1f
                } else {
                    ny = if (top <= bottom) -1f else 1f
                }

                val normalizedX = ((fx - halfW) / halfW).coerceIn(-1f, 1f)
                val normalizedY = ((fy - halfH) / halfH).coerceIn(-1f, 1f)
                val radial = (normalizedX * normalizedX + normalizedY * normalizedY).coerceIn(0f, 1f)
                val radialBend = radialStrength * (1f - radial)
                val edgeMask = 1f - cpuSmoothStep(0f, edgeWidth, nearest)
                val edgeBend = edgeStrength * edgeMask * edgeMask

                val sampleX = fx - normalizedX * radialBend - nx * edgeBend
                val sampleY = fy - normalizedY * radialBend - ny * edgeBend
                val sx = sampleX.toInt().coerceIn(0, w - 1)
                val sy = sampleY.toInt().coerceIn(0, h - 1)
                output[idx] = pixels[sy * w + sx]

                x++
            }
            y++
        }

        bitmap.setPixels(output, 0, w, 0, 0, w, h)
        return bitmap
    }

    private fun cpuSmoothStep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

}
