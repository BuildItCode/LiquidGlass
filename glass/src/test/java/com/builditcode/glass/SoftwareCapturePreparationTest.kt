package com.builditcode.glass

import android.graphics.Bitmap
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SoftwareCapturePreparationTest {
    private fun image(frame: Int): ImageBitmap = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888).apply {
        val pixels = IntArray(2500) { index ->
            0xff000000.toInt() or (((index * 13 + frame * 47) and 255) shl 16) or
                (((index * 19 + frame * 29) and 255) shl 8) or ((index * 31 + frame * 11) and 255)
        }
        setPixels(pixels, 0, 50, 0, 0, 50, 50)
    }.asImageBitmap()

    private fun Bitmap.pixels() = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }

    @Test
    fun animatedFramesPrepareFortyConsumersWithoutChangingPixelsOrRecroppingOnPublication() = runTest {
        val state = BackdropState(backgroundScope, 0.5f, 0L, true)
        val reference = CpuBlurCache()
        val filters = List(40) { BackdropFilter.Glass(refraction = 0.1f + it * 0.01f, edge = 0.2f + it * 0.005f) }
        state.updateSourceRect(Rect(0f, 0f, 100f, 100f))
        filters.forEachIndexed { id, glass ->
            val x = (id % 8) * 10f
            val y = (id / 8) * 16f
            state.registerRegion(id, Rect(x, y, x + 20f, y + 20f), 3 + id % 2, glass.cpuGlassParameters())
        }
        var previousFrame: IntArray? = null
        try {
            repeat(3) { frame ->
                val master = image(frame)
                val session = state.beginHardwareCapture(IntSize(50, 50))
                val requests = state.snapshotSoftwareRegions()
                val prepared = withContext(Dispatchers.Default) { state.prepareSoftwareCapture(master, requests, session) }
                assertEquals(setOf(3, 4), prepared.blurredMasters.keys)
                val results = prepared.regions.toMap()
                results.forEach { (id, result) ->
                    val expected = reference.glassRefraction(result.fallbackBitmap!!.asAndroidBitmap(), 0, filters[id], result.drawSize)
                    assertArrayEquals(expected.pixels(), result.preparedGlassFor(filters[id], 0)!!.asAndroidBitmap().pixels())
                }
                val reads = mutableSetOf<Any>()
                Snapshot.observe({ reads += it }, null) {
                    state.applyHardwareImageCapture(master, prepared.blurredMasters, session, prepared)
                }
                assertTrue(reads.isEmpty())
                results.forEach { (id, result) ->
                    assertSame("Publication must use worker output directly", result, state.getResult(id))
                    assertSame(master, result.masterImage)
                    assertFalse(result.fallbackBitmap!!.asAndroidBitmap().isRecycled)
                    assertFalse(result.preparedGlass!!.bitmap.asAndroidBitmap().isRecycled)
                }
                val pixels = state.getResult(0)!!.preparedGlass!!.bitmap.asAndroidBitmap().pixels()
                previousFrame?.let { assertFalse("Animated source must advance", it.contentEquals(pixels)) }
                previousFrame = pixels
            }
        } finally {
            reference.clear()
            state.dispose()
        }
    }

    @Test
    fun pendingCaptureHandlesMovementRemovalNewConsumersAndChangedFilters() = runTest {
        val state = BackdropState(backgroundScope, 0.5f, 0L, true)
        val glass = BackdropFilter.Glass()
        state.updateSourceRect(Rect(0f, 0f, 100f, 100f))
        repeat(4) { state.registerRegion(it, Rect(10f, 10f, 30f, 30f), 3, glass.cpuGlassParameters()) }
        val master = image(0)
        try {
            val session = state.beginHardwareCapture(IntSize(50, 50))
            val requests = state.snapshotSoftwareRegions()
            val prepared = withContext(Dispatchers.Default) { state.prepareSoftwareCapture(master, requests, session) }
            val old = prepared.regions.toMap()
            state.registerRegion(0, Rect(10.25f, 10.25f, 30.25f, 30.25f), 3, glass.cpuGlassParameters())
            state.unregisterRegion(1)
            state.registerRegion(2, Rect(40f, 40f, 60f, 60f), 3, glass.cpuGlassParameters())
            val changed = glass.copy(refraction = 0.9f)
            state.updateRegionFilter(3, 3, changed.cpuGlassParameters())
            state.registerRegion(4, Rect(60f, 60f, 80f, 80f), 3, glass.cpuGlassParameters())
            state.applyHardwareImageCapture(master, prepared.blurredMasters, session, prepared)

            assertSame(old[0]!!.fallbackBitmap, state.getResult(0)!!.fallbackBitmap)
            assertSame(old[0]!!.preparedGlass, state.getResult(0)!!.preparedGlass)
            assertEquals(-0.25f, state.getResult(0)!!.sampleOffset.x, 0f)
            assertFalse(old[0]!!.preparedGlass!!.bitmap.asAndroidBitmap().isRecycled)
            assertNull(state.getResult(1))
            for (id in listOf(1, 2)) {
                assertTrue(old[id]!!.fallbackBitmap!!.asAndroidBitmap().isRecycled)
                assertTrue(old[id]!!.preparedGlass!!.bitmap.asAndroidBitmap().isRecycled)
            }
            assertNotSame(old[2]!!.fallbackBitmap, state.getResult(2)!!.fallbackBitmap)
            assertNull("A changed filter must not display stale refraction", state.getResult(3)!!.preparedGlassFor(changed, 0))
            assertNotNull(state.getResult(4)!!.fallbackBitmap)
            assertTrue(prepared.regions.isEmpty())
            state.dispose()
            assertTrue(old[0]!!.preparedGlass!!.bitmap.asAndroidBitmap().isRecycled)
        } finally { state.dispose() }
    }

    @Test
    fun independentWorkersAndDiscardedCapturesKeepBitmapOwnershipIsolated() = runTest {
        val state = BackdropState(backgroundScope, 0.5f, 0L, true)
        state.updateSourceRect(Rect(0f, 0f, 100f, 100f))
        val glass = BackdropFilter.Glass()
        state.registerRegion(1, Rect(0f, 0f, 80f, 60f), 3, glass.cpuGlassParameters())
        val requests = state.snapshotSoftwareRegions()
        val session = state.beginHardwareCapture(IntSize(50, 50))
        try {
            (0..2).map { frame ->
                async(Dispatchers.Default) {
                    val master = image(frame)
                    val reference = CpuBlurCache()
                    try {
                        val prepared = state.prepareSoftwareCapture(master, requests, session)
                        val result = prepared.regions.getValue(1)
                        val expected = reference.glassRefraction(result.fallbackBitmap!!.asAndroidBitmap(), 0, glass, result.drawSize)
                        assertArrayEquals(expected.pixels(), result.preparedGlass!!.bitmap.asAndroidBitmap().pixels())
                        prepared.recycle()
                        assertTrue(result.fallbackBitmap.asAndroidBitmap().isRecycled)
                        assertTrue(result.preparedGlass!!.bitmap.asAndroidBitmap().isRecycled)
                        assertTrue(prepared.blurredMasters.values.all { it.asAndroidBitmap().isRecycled })
                        assertFalse(master.asAndroidBitmap().isRecycled)
                    } finally {
                        reference.clear()
                        master.safeRecycle()
                    }
                }
            }.awaitAll()
        } finally { state.dispose() }
    }
}
