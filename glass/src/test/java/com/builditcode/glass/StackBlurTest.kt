package com.builditcode.glass

import android.graphics.Bitmap
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class StackBlurTest {
    @Test
    fun blurMatchesTriangularConvolutionIncludingClampedEdges() {
        for ((width, height) in listOf(1 to 1, 1 to 9, 9 to 1, 13 to 7)) {
            for (radius in listOf(1, 3, 7)) {
                val pixels = pixels(width * height)
                val bitmap = bitmap(width, height, pixels)
                try {
                    applyStackBlur(bitmap, radius)
                    assertArrayEquals(
                        "${width}x$height, radius $radius",
                        referenceBlur(pixels, width, height, radius),
                        bitmap.pixels()
                    )
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }

    @Test
    fun reusedWorkspacePreservesPixelsAcrossSizesAndRadii() {
        val workspace = StackBlurWorkspace()
        for ((width, height, radius) in listOf(
            Triple(27, 19, 20), Triple(3, 2, 1), Triple(1, 31, 6), Triple(40, 7, 9)
        )) {
            val source = pixels(width * height)
            val fresh = bitmap(width, height, source)
            val reused = bitmap(width, height, source)
            try {
                applyStackBlur(fresh, radius)
                applyStackBlur(reused, radius, workspace)
                assertArrayEquals(fresh.pixels(), reused.pixels())
            } finally {
                fresh.recycle()
                reused.recycle()
            }
        }
    }

    @Test
    fun steadyStateBlurReusesAllLargeScratchBuffers() {
        val workspace = StackBlurWorkspace()
        val bitmap = bitmap(32, 24, pixels(32 * 24))
        try {
            applyStackBlur(bitmap, 8, workspace)
            val buffers = listOf(workspace.pixels, workspace.red, workspace.green, workspace.blue, workspace.alpha)
            repeat(3) { applyStackBlur(bitmap, 8, workspace) }
            val nextBuffers = listOf(workspace.pixels, workspace.red, workspace.green, workspace.blue, workspace.alpha)
            buffers.zip(nextBuffers).forEach { (before, after) -> assertSame(before, after) }
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun hugeRadiusUsesConsistentBoundedKernel() {
        val source = pixels(6)
        val bounded = bitmap(3, 2, source)
        val huge = bitmap(3, 2, source)
        try {
            applyStackBlur(bounded, 254)
            applyStackBlur(huge, Int.MAX_VALUE)
            assertArrayEquals(bounded.pixels(), huge.pixels())
        } finally {
            bounded.recycle()
            huge.recycle()
        }
    }

    private fun pixels(count: Int): IntArray {
        val random = Random(173)
        return IntArray(count) { 0xff000000.toInt() or random.nextInt(0x1000000) }
    }

    private fun bitmap(width: Int, height: Int, pixels: IntArray): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }

    private fun Bitmap.pixels() = IntArray(width * height).also {
        getPixels(it, 0, width, 0, 0, width, height)
    }

    // Deliberately slow, independent convolution oracle. These radii have exact binary
    // normalization, so the optimized reciprocal tables must produce identical pixels.
    private fun referenceBlur(input: IntArray, width: Int, height: Int, radius: Int): IntArray {
        val divisor = (radius + 1) * (radius + 1)
        fun pass(source: IntArray, horizontal: Boolean): IntArray = IntArray(source.size) { index ->
            val x = index % width
            val y = index / width
            var color = 0
            for (shift in listOf(0, 8, 16, 24)) {
                var sum = 0
                for (offset in -radius..radius) {
                    val sx = if (horizontal) (x + offset).coerceIn(0, width - 1) else x
                    val sy = if (horizontal) y else (y + offset).coerceIn(0, height - 1)
                    sum += ((source[sy * width + sx] ushr shift) and 255) * (radius + 1 - abs(offset))
                }
                color = color or ((sum / divisor) shl shift)
            }
            color
        }
        return pass(pass(input, true), false)
    }
}
