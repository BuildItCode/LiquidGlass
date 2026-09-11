package com.builditcode.glass

import android.graphics.Bitmap
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.random.Random
import kotlin.system.measureNanoTime

@RunWith(AndroidJUnit4::class)
class NativeStackBlurTest {
    private fun image(width: Int, height: Int, seed: Int = 713, opaque: Boolean = false): Bitmap {
        val random = Random(seed)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(IntArray(width * height) { random.nextInt().let { if (opaque) it or 0xff000000.toInt() else it } }, 0, width, 0, 0, width, height)
        }
    }

    private fun Bitmap.pixels() = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }

    @Test fun translucentOrNonSrgbBitmapsKeepTheConversionPreservingPath() {
        assertTrue(NativeStackBlur.available)
        val sources = mutableListOf(image(33, 21))
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            sources += Bitmap.createBitmap(33, 21, Bitmap.Config.ARGB_8888, true,
                android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.DISPLAY_P3)).apply {
                eraseColor(android.graphics.Color.rgb(210, 60, 170))
            }
            sources += Bitmap.createBitmap(33, 21, Bitmap.Config.RGBA_F16).apply {
                eraseColor(android.graphics.Color.argb(160, 120, 210, 40))
            }
        }
        for (source in sources) {
            val expected = source.copy(source.config!!, true)
            try {
                assertFalse(NativeStackBlur.applyOpaque(source, IntArray(33 * 21), 3, 512, 13))
                stackBlur(expected, 3, StackBlurWorkspace())
                applyStackBlur(source, 3, StackBlurWorkspace())
                assertArrayEquals(expected.pixels(), source.pixels())
            } finally { source.recycle(); expected.recycle() }
        }
    }

    @Test fun acceleratedBlurMatchesKotlinForEveryRadiusIncludingAlphaAndClampedEdges() {
        assertTrue("The device APK must contain and load its native blur library", NativeStackBlur.available)
        val nativeWorkspace = StackBlurWorkspace()
        val kotlinWorkspace = StackBlurWorkspace()
        for ((width, height) in listOf(1 to 1, 1 to 19, 19 to 1, 17 to 23, 2 to 2, 18 to 24)) {
            val source = image(width, height, opaque = width == 18)
            if (width == 2) source.eraseColor(android.graphics.Color.WHITE)
            try {
                for (radius in 1..254) {
                    val expected = source.copy(Bitmap.Config.ARGB_8888, true)
                    val actual = source.copy(Bitmap.Config.ARGB_8888, true)
                    try {
                        stackBlur(expected, radius, kotlinWorkspace)
                        applyStackBlur(actual, radius, nativeWorkspace)
                        assertArrayEquals("${width}x$height radius=$radius", expected.pixels(), actual.pixels())
                    } finally { expected.recycle(); actual.recycle() }
                }
            } finally { source.recycle() }
        }
    }

    @Test fun concurrentWorkersKeepTheirPixelsAndScratchStorageIndependent() {
        assertTrue(NativeStackBlur.available)
        val workers = Executors.newFixedThreadPool(3)
        try {
            workers.invokeAll((0..8).map { seed -> Callable {
                val source = image(180 + seed, 250 - seed, seed)
                val expected = source.copy(Bitmap.Config.ARGB_8888, true)
                try {
                    val radius = 3 + seed * 4
                    stackBlur(expected, radius, StackBlurWorkspace())
                    applyStackBlur(source, radius, StackBlurWorkspace())
                    assertArrayEquals("worker=$seed", expected.pixels(), source.pixels())
                } finally { source.recycle(); expected.recycle() }
            } }).forEach { it.get() }
        } finally { workers.shutdownNow() }
    }

    @Test fun invalidNativeBuffersAreRejectedWithoutChangingPixels() {
        val pixels = IntArray(4) { it }
        val expected = pixels.copyOf()
        assertFalse(NativeStackBlur.apply(pixels, pixels, 2, 2, 1, 512, 11))
        assertFalse(NativeStackBlur.apply(pixels, IntArray(3), 2, 2, 1, 512, 11))
        assertFalse(NativeStackBlur.apply(pixels, IntArray(4), 2, 2, 255, 512, 11))
        assertFalse(NativeStackBlur.apply(pixels, IntArray(4), Int.MAX_VALUE, 2, 1, 512, 11))
        assertArrayEquals(expected, pixels)
    }

    @Test fun fullScreenCaptureBenchmarkPreservesPixelsAndReportsCost() {
        assertTrue(NativeStackBlur.available)
        val nativeImage = image(720, 1560, opaque = true)
        val kotlinImage = nativeImage.copy(Bitmap.Config.ARGB_8888, true)
        val nativeWorkspace = StackBlurWorkspace()
        val kotlinWorkspace = StackBlurWorkspace()
        try {
            repeat(2) {
                applyStackBlur(nativeImage, 18, nativeWorkspace)
                stackBlur(kotlinImage, 18, kotlinWorkspace)
            }
            val nativeMs = measureNanoTime { repeat(8) { applyStackBlur(nativeImage, 18, nativeWorkspace) } } / 8_000_000.0
            val kotlinMs = measureNanoTime { repeat(8) { stackBlur(kotlinImage, 18, kotlinWorkspace) } } / 8_000_000.0
            assertArrayEquals("Benchmark outputs must remain identical", kotlinImage.pixels(), nativeImage.pixels())
            assertEquals(0, nativeWorkspace.red.size)
            assertEquals("Opaque blur should not copy pixels into a JVM array", 0, nativeWorkspace.pixels.size)
            Log.i("NativeBlurBenchmark", "720x1560 radius=18 native=$nativeMs ms Kotlin=$kotlinMs ms")
        } finally { nativeImage.recycle(); kotlinImage.recycle() }
    }
}
