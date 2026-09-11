package com.builditcode.glass

import android.graphics.Bitmap
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MultiComponentCaptureTest {
    private fun state(scope: CoroutineScope) = BackdropState(scope, 0.5f, 0L, true).apply {
        updateSourceRect(Rect(0f, 0f, 100f, 100f))
    }

    private fun image(color: Int = 0xff123456.toInt()): ImageBitmap =
        Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }.asImageBitmap()

    private fun BackdropState.publish(blurred: Map<Int, ImageBitmap> = emptyMap()): ImageBitmap {
        val master = image()
        applyHardwareImageCapture(master, blurred, beginHardwareCapture(IntSize(50, 50)))
        return master
    }

    @Test
    fun publishingSharedCaptureDoesNotSubscribeTheSourceToItsConsumers() = runTest {
        val state = state(backgroundScope)
        try {
            repeat(40) { state.registerRegion(it, Rect(0f, 0f, 20f, 20f), 0) }
            val reads = mutableSetOf<Any>()
            Snapshot.observe(readObserver = { reads += it }, writeObserver = null) { state.publish() }
            assertTrue("Capture bookkeeping must not become draw dependencies: ${reads.size}", reads.isEmpty())
        } finally { state.dispose() }
    }

    @Test
    fun joiningAnActiveSourceUsesTheExistingMaster() = runTest {
        val state = state(backgroundScope)
        try {
            state.registerRegion(1, Rect(0f, 0f, 20f, 20f), 0)
            val master = state.publish()
            val invalidations = state.sourceInvalidator
            repeat(40) { state.registerRegion(it + 2, Rect(20f, 20f, 40f, 40f), 0) }
            assertEquals(invalidations, state.sourceInvalidator)
            assertSame(master, state.getResult(41)?.masterImage)
        } finally { state.dispose() }
    }

    @Test
    fun requestsFromManyComponentsCoalesceUntilCaptureStarts() = runTest {
        val state = state(backgroundScope)
        try {
            val before = state.sourceInvalidator
            repeat(40) {
                state.registerRegion(it, Rect(0f, 0f, 20f, 20f), 0)
                state.requestCapture()
            }
            assertEquals(before + 1, state.sourceInvalidator)
        } finally { state.dispose() }
    }

    @Test
    fun fractionalMovementReusesTheSamePixelsWithoutRecyclingThem() = runTest {
        val state = state(backgroundScope)
        try {
            state.registerRegion(1, Rect(10f, 10f, 30f, 30f), 0)
            state.publish()
            val old = state.getResult(1)!!
            state.registerRegion(1, Rect(10.25f, 10.25f, 30.25f, 30.25f), 0)
            val moved = state.getResult(1)!!
            assertNotSame(old, moved)
            assertSame(old.fallbackBitmap, moved.fallbackBitmap)
            assertFalse(moved.fallbackBitmap!!.asAndroidBitmap().isRecycled)
            assertEquals(-0.25f, moved.sampleOffset.x, 0f)
        } finally { state.dispose() }
    }

    @Test
    fun switchingToAnotherComponentsPreparedRadiusNeedsNoRecapture() = runTest {
        val state = state(backgroundScope)
        try {
            state.registerRegion(1, Rect(0f, 0f, 20f, 20f), 3)
            state.registerRegion(2, Rect(30f, 0f, 50f, 20f), 7)
            state.publish(mapOf(3 to image(0xffff0000.toInt()), 7 to image(0xff00ff00.toInt())))
            val invalidations = state.sourceInvalidator
            state.updateRegionBlurRadius(1, 7)
            val updated = state.getResult(1)!!
            assertEquals(7, updated.fallbackBlurRadiusPx)
            assertEquals(0xff00ff00.toInt(), updated.fallbackBitmap!!.asAndroidBitmap().getPixel(0, 0))
            assertEquals(invalidations, state.sourceInvalidator)
        } finally { state.dispose() }
    }

    @Test
    fun offSourceComponentsDoNotKeepCaptureOrBlurWorkAlive() = runTest {
        val state = state(backgroundScope)
        try {
            repeat(40) { state.registerRegion(it, Rect(200f, 200f, 220f, 220f), it + 1) }
            assertFalse(state.shouldCapture)
            assertTrue(state.pendingCpuBlurRadii().isEmpty())
            state.requestCapture(force = true)
            assertTrue("Explicit priming must remain supported", state.shouldCapture)
        } finally { state.dispose() }
    }

    @Test
    fun removingTheLastConsumerCancelsItsPendingRequest() = runTest {
        val state = state(backgroundScope)
        try {
            state.registerRegion(1, Rect(0f, 0f, 20f, 20f), 3)
            assertTrue(state.shouldCapture)
            state.unregisterRegion(1)
            assertFalse(state.shouldCapture)
        } finally { state.dispose() }
    }

    @Test
    fun componentsLeavingAndReenteringTheSourceRestartCapture() = runTest {
        val state = state(backgroundScope)
        try {
            state.registerRegion(1, Rect(0f, 0f, 20f, 20f), 3)
            state.publish(mapOf(3 to image()))
            state.registerRegion(1, Rect(200f, 200f, 220f, 220f), 3)
            assertFalse(state.shouldCapture)
            assertEquals(null, state.getResult(1))
            val invalidations = state.sourceInvalidator
            state.registerRegion(1, Rect(0f, 0f, 20f, 20f), 3)
            assertTrue(state.shouldCapture)
            assertEquals(invalidations + 1, state.sourceInvalidator)
            assertTrue(state.getResult(1) != null)
            state.updateSourceRect(Rect(200f, 200f, 300f, 300f))
            assertFalse(state.shouldCapture)
            assertTrue(state.pendingCpuBlurRadii().isEmpty())
            state.updateSourceRect(Rect(0f, 0f, 100f, 100f))
            assertTrue(state.shouldCapture)
            assertEquals(setOf(3), state.pendingCpuBlurRadii())
        } finally { state.dispose() }
    }

    @Test
    fun explicitPrimingSurvivesRemovalOfTheLastConsumer() = runTest {
        val state = state(backgroundScope)
        try {
            state.registerRegion(1, Rect(0f, 0f, 20f, 20f), 0)
            state.requestCapture(force = true)
            state.unregisterRegion(1)
            assertTrue(state.shouldCapture)
            state.publish()
            assertFalse(state.shouldCapture)
        } finally { state.dispose() }
    }

    @Test
    fun identicalLayerNamesInDifferentManagersCannotSuppressEachOther() = runTest {
        val first = BackdropLayerManager(backgroundScope, 0.5f, 0L, true)
        val second = BackdropLayerManager(backgroundScope, 0.5f, 0L, true)
        val outer = first.getState("background")
        val inner = second.getState("background")
        try {
            drawingBackdropSource(outer) {
                assertTrue(isDrawingBackdropSource(outer))
                assertFalse(isDrawingBackdropSource(inner))
                recordingBackdropSource(inner) {
                    assertTrue(isRecordingBackdropSource(inner))
                    assertFalse(isRecordingBackdropSource(outer))
                }
                assertFalse(isRecordingBackdropSource(inner))
            }
            assertFalse(isDrawingBackdropSource(outer))
        } finally {
            first.disposeAll()
            second.disposeAll()
        }
    }

    @Test
    fun resumingUpdatesReissuesRequestsConsumedWhileFrozen() = runTest {
        val manager = BackdropLayerManager(backgroundScope, 0.5f, 0L, true)
        val state = manager.getState("background")
        try {
            state.updateSourceRect(Rect(0f, 0f, 100f, 100f))
            state.registerRegion(1, Rect(0f, 0f, 20f, 20f), 0)
            val beforePause = state.sourceInvalidator
            manager.stopUpdates()
            assertFalse(state.shouldCapture)
            manager.startUpdates()
            assertTrue(state.shouldCapture)
            assertEquals(beforePause + 1, state.sourceInvalidator)
        } finally { manager.disposeAll() }
    }
}
